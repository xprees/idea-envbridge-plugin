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
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.editor.DotEnvLines
import cz.xprees.secrets.run.EnvPatcher

/**
 * After secrets moved, updates the project's references to them, so everything keeps reading the same secrets:
 * run configurations' environment variables, `.env` files (values and binding comments) and the terminal's references.
 *
 * All changes are one command, *Update Secret References*, undoable as a whole with Edit › Undo: the files through their
 * documents, run configurations and settings through a global undoable action that restores what they held.
 */
class ReferenceUpdater(private val project: Project) {

    /** The changes that would be made; [apply] makes them. */
    class Plan internal constructor(
        internal val files: List<Pair<VirtualFile, List<MovedReferences.Edit>>>,
        internal val configurations: List<Triple<RunnerAndConfigurationSettings, Map<String, String>, Map<String, String>>>,
        internal val terminal: Pair<String, String>?,
    ) {
        val references: Int get() = files.sumOf { it.second.size } + configurations.sumOf { (_, old, new) -> old.count { (k, v) -> new[k] != v } } +
            (if (terminal != null) 1 else 0)

        val isEmpty: Boolean get() = files.isEmpty() && configurations.isEmpty() && terminal == null

        fun summary(): String = SecretsBundle.message("move.references.summary", references, files.size, configurations.size)
    }

    /**
     * Finds the references to [keys], moved from [source] to [destination] on [connection]. Reads files: call in a read action
     * off the EDT (or in tests).
     */
    fun plan(connection: SecretConnection, keys: Collection<String>, source: SecretScope, destination: SecretScope): Plan {
        val provider = SecretProvider.byId(connection.providerId) ?: return Plan(emptyList(), emptyList(), null)
        val bindings = SecretBindingService.getInstance(project)
        val binding = bindings.bindingOrNull(provider)
        // A reference without ?connection= reads the bound connection of its provider
        val boundIsMoved = binding?.connection?.id == connection.id
        val moved = MovedReferences(provider.referenceScheme, keys.toSet(), source, destination) { named ->
            if (named == null) boundIsMoved else named == connection.id || named.equals(connection.name, ignoreCase = true)
        }
        val defaults = SecretReference.Defaults(binding?.projectId ?: source.projectId, binding?.environment ?: source.environment, binding?.path ?: "/")

        val files = mutableListOf<Pair<VirtualFile, List<MovedReferences.Edit>>>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && DotEnvLines.isDotEnv(file.name) && file.length < MAX_FILE_BYTES) {
                val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull()
                val edits = text?.let { moved.edits(it, defaults) }.orEmpty()
                if (edits.isNotEmpty()) files += file to edits
            }
            true
        }

        val configurations = RunManager.getInstance(project).allSettings.mapNotNull { settings ->
            val (_, envs) = EnvPatcher.find(settings.configuration) ?: return@mapNotNull null
            // A configuration may pick another environment for its references
            val own = SecretBindingService.environmentOverride(envs)?.let { defaults.copy(environment = it) } ?: defaults
            val updated = envs.mapValues { (_, value) -> moved.rewriteText(value, own) ?: value }
            if (updated == envs) null else Triple(settings, LinkedHashMap(envs), LinkedHashMap(updated))
        }

        val terminalText = ProjectSecretsSettings.getInstance(project).state.terminalReferences
        val terminal = terminalText?.let { moved.rewriteText(it, defaults) }?.let { terminalText to it }

        return Plan(files, configurations, terminal)
    }

    /** Makes the [plan]'s changes as one undoable command. On the EDT. */
    fun apply(plan: Plan) {
        if (plan.isEmpty) return
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
                        }
                    }
                }
                val action = SettingsChange(project, plan)
                action.redo()
                UndoManager.getInstance(project).undoableActionPerformed(action)
            }
    }

    /** Run configurations and settings are no documents: this action puts back (undo) or makes again (redo) their changes. */
    private class SettingsChange(private val project: Project, private val plan: Plan) : GlobalUndoableAction() {
        override fun undo() = set(old = true)

        override fun redo() = set(old = false)

        private fun set(old: Boolean) {
            for ((settings, before, after) in plan.configurations) EnvPatcher.store(project, settings, if (old) before else after)
            plan.terminal?.let { (before, after) -> ProjectSecretsSettings.getInstance(project).update { terminalReferences = if (old) before else after } }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 1024 * 1024L

        fun getInstance(project: Project) = ReferenceUpdater(project)

        /** [plan] in a read action, for callers off the EDT. */
        fun planInReadAction(project: Project, connection: SecretConnection, keys: Collection<String>, source: SecretScope, destination: SecretScope): Plan =
            ReadAction.compute<Plan, RuntimeException> { ReferenceUpdater(project).plan(connection, keys, source, destination) }
    }
}
