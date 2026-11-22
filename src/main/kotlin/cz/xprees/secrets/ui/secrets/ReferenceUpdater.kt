package cz.xprees.secrets.ui.secrets

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.GlobalUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.MovedReferences
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.DotEnvLines
import cz.xprees.secrets.run.EnvPatcher

/**
 * After secrets moved or one was renamed, updates the project's references to them, so everything keeps reading the same secrets:
 * run configurations' environment variables, `.env` files (values and binding comments) and the terminal's references.
 *
 * All changes are one command, *Update Secret References*, undoable as a whole with Edit › Undo: the files through their
 * documents, run configurations and settings through a global undoable action that restores what they held.
 */
class ReferenceUpdater(private val project: Project) {

    /** One variable of a run configuration whose value changes. */
    internal data class VariableChange(val name: String, val before: String, val after: String)

    /** The changes that would be made; [apply] makes them. */
    class Plan internal constructor(
        internal val files: List<Pair<VirtualFile, List<MovedReferences.Edit>>>,
        internal val configurations: List<Pair<RunnerAndConfigurationSettings, List<VariableChange>>>,
        internal val terminal: Pair<String, String>?,
        /** For a rename (else a move): only the summary's wording differs. */
        internal val rename: Boolean = false,
        /** References to the secrets left as they were because they follow the selected environment. */
        val following: Int = 0,
        /** The same plan, also rewriting the references that follow the selected environment (off the EDT, in a read action). */
        internal val includingFollowing: (() -> Plan)? = null,
    ) {
        /** How many references [apply] changed (some found ones may have changed meanwhile and are left), or null before. */
        internal var applied: Int? = null

        val references: Int get() = files.sumOf { it.second.size } + configurations.sumOf { it.second.size } + (if (terminal != null) 1 else 0)

        val isEmpty: Boolean get() = files.isEmpty() && configurations.isEmpty() && terminal == null

        fun summary(): String = buildString {
            if (!isEmpty) {
                append(SecretsBundle.message(if (rename) "rename.references.summary" else "move.references.summary", applied ?: references, files.size, configurations.size))
            }
            if (following > 0) {
                if (isNotEmpty()) append('\n')
                append(SecretsBundle.message("references.following.left", following))
            }
        }
    }

    /**
     * Finds the references to [keys], moved from [source] to [destination] on [connection]. Reads files: call in a read action
     * off the EDT (or in tests). [projectAliases]: other names of the source project references may use.
     */
    fun plan(
        connection: SecretConnection,
        keys: Collection<String>,
        source: SecretScope,
        destination: SecretScope,
        includeFollowing: Boolean = false,
        projectAliases: Set<String> = emptySet(),
    ): Plan = plan(connection, keys, source, destination, emptyMap(), includeFollowing, projectAliases)

    /**
     * Finds the references to [oldKey] of the folder of [scope] on [connection], renamed to [newKey]. Reads files: call in a read
     * action off the EDT (or in tests).
     */
    fun planRename(
        connection: SecretConnection,
        scope: SecretScope,
        oldKey: String,
        newKey: String,
        includeFollowing: Boolean = false,
        projectAliases: Set<String> = emptySet(),
    ): Plan = plan(connection, listOf(oldKey), scope, scope, mapOf(oldKey to newKey), includeFollowing, projectAliases)

    private fun plan(
        connection: SecretConnection,
        keys: Collection<String>,
        source: SecretScope,
        destination: SecretScope,
        renames: Map<String, String>,
        includeFollowing: Boolean,
        projectAliases: Set<String>,
    ): Plan {
        val provider = SecretProvider.byId(connection.providerId) ?: return Plan(emptyList(), emptyList(), null)
        val bindings = SecretBindingService.getInstance(project)

        // A reference reads the connection and defaults the launch would use: the bound connection, or the one it names
        // (?connection=), with a run configuration's environment override. Cached: a binding may read the project file.
        val resolved = HashMap<Pair<String?, String?>, SecretBindingService.Binding?>()
        fun bindingOf(reference: SecretReference, override: String?) =
            resolved.getOrPut(override to reference.connectionId) { runCatching { bindings.binding(provider, override, reference.connectionId) }.getOrNull() }
        fun defaultsFor(override: String?): (SecretReference) -> SecretReference.Defaults? = { reference ->
            bindingOf(reference, override)?.takeIf { it.connection.id == connection.id }?.let { binding ->
                (reference.projectId ?: binding.projectId)?.let {
                    provider.referenceDefaults(binding.connection, reference, SecretReference.Defaults(it, binding.environment, binding.path))
                }
            }
        }

        var overrideInEffect: String? = null
        val moved = MovedReferences(
            provider.referenceScheme, keys.toSet(), source, destination,
            isMovedConnection = { true }, // checked per reference by defaultsFor
            renames = renames,
            projectAliases = projectAliases,
            followsSelection = { reference -> provider.followsSelection(connection, reference) && overrideInEffect == null },
            includeFollowing = includeFollowing,
            // References written in another form (Vault's full paths under an environment layout) name the same folders
            alsoNamed = listOfNotNull(
                provider.equivalentScope(connection, source)?.let { from -> provider.equivalentScope(connection, destination)?.let { from to it } },
            ),
        )
        val defaults = defaultsFor(null)

        val files = mutableListOf<Pair<VirtualFile, List<MovedReferences.Edit>>>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && DotEnvLines.isDotEnv(file.name) && file.length < MAX_FILE_BYTES) {
                val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull()
                val varlock = SecretsAppSettings.getInstance().state.varlockCompatibility
                val edits = text?.let { moved.edits(it, defaults) + if (varlock) moved.varlockEdits(it, defaults) else emptyList() }.orEmpty()
                if (edits.isNotEmpty()) files += file to edits
            }
            true
        }

        val configurations = RunManager.getInstance(project).allSettings.mapNotNull { settings ->
            val (_, envs) = EnvPatcher.find(settings.configuration) ?: return@mapNotNull null
            // A configuration may fix another environment for its references
            overrideInEffect = SecretBindingService.environmentOverride(envs)
            val own = defaultsFor(overrideInEffect)
            val changes = envs.mapNotNull { (name, value) -> moved.rewriteText(value, own)?.let { VariableChange(name, value, it) } }
            overrideInEffect = null
            if (changes.isEmpty()) null else settings to changes
        }

        val terminalText = ProjectSecretsSettings.getInstance(project).state.terminalReferences
        val terminal = terminalText?.let { moved.rewriteText(it, defaults) }?.let { terminalText to it }

        val again = if (!includeFollowing && moved.followingSkipped > 0) {
            { plan(connection, keys, source, destination, renames, includeFollowing = true, projectAliases) }
        } else {
            null
        }
        return Plan(files, configurations, terminal, rename = renames.isNotEmpty(), following = if (includeFollowing) 0 else moved.followingSkipped, includingFollowing = again)
    }

    /** Makes the [plan]'s changes as one undoable command. On the EDT. Returns how many references changed. */
    fun apply(plan: Plan): Int {
        if (plan.isEmpty) return 0.also { plan.applied = 0 }
        var count = 0
        WriteCommandAction.writeCommandAction(project)
            .withName(SecretsBundle.message("move.references.command"))
            .withGlobalUndo()
            .run<RuntimeException> {
                for ((file, edits) in plan.files) {
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
                    // Only where the text is still what was found (the file may have changed since)
                    edits.sortedByDescending { it.start }.forEach { edit ->
                        if (edit.end <= document.textLength && document.getText(com.intellij.openapi.util.TextRange(edit.start, edit.end)) == edit.old) {
                            document.replaceString(edit.start, edit.end, edit.replacement)
                            count++
                        }
                    }
                }
                val action = SettingsChange(project, plan)
                count += action.change(old = false)
                UndoManager.getInstance(project).undoableActionPerformed(action)
            }
        plan.applied = count
        return count
    }

    /**
     * Run configurations and settings are no documents: this action puts back (undo) or makes again (redo) their changes.
     * Only values that are still what the change left (or found) are touched, so edits made since are kept.
     */
    private class SettingsChange(private val project: Project, private val plan: Plan) : GlobalUndoableAction() {
        override fun undo() {
            change(old = true)
        }

        override fun redo() {
            change(old = false)
        }

        /** Sets the old (undo) or new values where the current ones are the other side. Returns how many changed. */
        fun change(old: Boolean): Int {
            var count = 0
            for ((settings, changes) in plan.configurations) {
                val current = EnvPatcher.find(settings.configuration)?.second ?: continue
                val updated = LinkedHashMap(current)
                for (change in changes) {
                    val (from, to) = if (old) change.after to change.before else change.before to change.after
                    if (current[change.name] == from) {
                        updated[change.name] = to
                        count++
                    }
                }
                if (updated != current) EnvPatcher.store(project, settings, updated)
            }
            plan.terminal?.let { (before, after) ->
                val (from, to) = if (old) after to before else before to after
                val settings = ProjectSecretsSettings.getInstance(project)
                if (settings.state.terminalReferences == from) {
                    settings.update { terminalReferences = to }
                    count++
                }
            }
            return count
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 1024 * 1024L

        fun getInstance(project: Project) = ReferenceUpdater(project)

        /** [plan] in a read action, for callers off the EDT (reads the project list first, outside it). */
        fun planInReadAction(project: Project, connection: SecretConnection, keys: Collection<String>, source: SecretScope, destination: SecretScope): Plan {
            val aliases = projectAliases(connection, source.projectId)
            return ReadAction.compute<Plan, RuntimeException> { ReferenceUpdater(project).plan(connection, keys, source, destination, projectAliases = aliases) }
        }

        /** [planRename] in a read action, for callers off the EDT (reads the project list first, outside it). */
        fun planRenameInReadAction(project: Project, connection: SecretConnection, scope: SecretScope, oldKey: String, newKey: String): Plan {
            val aliases = projectAliases(connection, scope.projectId)
            return ReadAction.compute<Plan, RuntimeException> { ReferenceUpdater(project).planRename(connection, scope, oldKey, newKey, projectAliases = aliases) }
        }

        /** The plan that also rewrites the references [plan] left because they follow the selected environment. Off the EDT. */
        fun includingFollowing(plan: Plan): Plan? = plan.includingFollowing?.let { again -> ReadAction.compute<Plan, RuntimeException> { again() } }

        /**
         * The notification action that also updates the references [plan] left because they follow the selected
         * environment, or none.
         */
        fun followUpActions(project: Project, plan: Plan?): List<Pair<String, () -> Unit>> {
            if (plan?.includingFollowing == null) return emptyList()
            return listOf(SecretsBundle.message("references.following.update") to {
                com.intellij.openapi.progress.ProgressManager.getInstance().run(object : com.intellij.openapi.progress.Task.Backgroundable(
                    project, SecretsBundle.message("move.references.command"), false,
                ) {
                    private var again: Plan? = null

                    override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                        again = includingFollowing(plan)
                    }

                    override fun onSuccess() {
                        val next = again ?: return
                        getInstance(project).apply(next)
                        cz.xprees.secrets.run.SecretsNotifications.info(project, SecretsBundle.message("move.references.command"), next.summary())
                    }
                })
            })
        }

        /** Names a reference may give the project instead of its ID (1Password references usually name the vault). */
        private fun projectAliases(connection: SecretConnection, projectId: String): Set<String> =
            runCatching { SecretsService.getInstance().projects(connection) }.getOrNull()
                ?.firstOrNull { it.id == projectId }?.let { setOf(it.name, it.slug).filter(String::isNotBlank).toSet() }.orEmpty()
    }
}
