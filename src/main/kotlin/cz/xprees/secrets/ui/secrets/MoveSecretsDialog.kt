package cz.xprees.secrets.ui.secrets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.run.SecretsNotifications
import javax.swing.JComponent

/**
 * Confirms (and lets you adjust) moving secrets to another folder or environment, then moves them. Opened by *Move…*,
 * cut and paste, and drag and drop in the Secrets tool window, with the destination filled in.
 */
class MoveSecretsDialog(
    private val project: Project,
    private val connection: SecretConnection,
    private val source: SecretScope,
    private val secrets: List<SecretEntry>,
    environments: List<String>,
    destination: SecretScope,
    /** Copy instead of move: the secrets are created at the destination and the originals (and their references) stay. */
    private val copy: Boolean = false,
    private val onMoved: (SecretScope, MoveResult) -> Unit = { _, _ -> },
) : DialogWrapper(project) {
    private val environmentCombo = ComboBox(environments.ifEmpty { listOf(destination.environment) }.toTypedArray()).apply { selectedItem = destination.environment }
    private val pathField = TextFieldWithAutoCompletion.create(project, listOf(destination.path), true, destination.path)
    private val overwrite = JBCheckBox(SecretsBundle.message("move.overwrite"))
    private val updateReferences = JBCheckBox(SecretsBundle.message("move.update.references"), true)

    init {
        title = SecretsBundle.message(if (copy) "copy.title" else "move.title", secrets.size)
        setOKButtonText(SecretsBundle.message(if (copy) "copy.ok" else "move.ok"))
        environmentCombo.addActionListener { loadFolders() }
        loadFolders()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("move.secrets")) { label(secrets.joinToString(", ") { it.key }.let { if (it.length > 90) it.take(90) + "…" else it }) }
        row(SecretsBundle.message("move.from")) { label("${source.environment}:${source.path}") }
        row(SecretsBundle.message("new.secret.environment")) { cell(environmentCombo) }
        row(SecretsBundle.message("new.secret.folder")) { cell(pathField).align(AlignX.FILL).comment(SecretsBundle.message("new.secret.folder.comment")) }
        if (!copy) {
            row { cell(overwrite) }
            row { cell(updateReferences).comment(SecretsBundle.message("move.update.references.comment")) }
        }
        row { comment(SecretsBundle.message(if (copy) "copy.note" else "move.note")) }
    }

    private fun destination(): SecretScope =
        SecretScope(source.projectId, environmentCombo.selectedItem as String, SecretScope.normalizePath(pathField.text.trim()))

    private fun loadFolders() {
        val environment = environmentCombo.selectedItem as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val folders = runCatching { SecretsService.getInstance().allFolders(connection, source.projectId, environment) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (environmentCombo.selectedItem == environment) pathField.setVariants(folders)
            }, ModalityState.any())
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        !pathField.text.trim().let { it.isEmpty() || it.startsWith("/") } -> ValidationInfo(SecretsBundle.message("new.secret.invalid.folder"), pathField)
        destination().let { it.environment == source.environment && it.path == source.path } -> ValidationInfo(SecretsBundle.message("move.same"), pathField)
        else -> null
    }

    override fun doOKAction() {
        val target = destination()
        val guarded = listOf(source.environment, target.environment).distinct().filter { ProdGuard.needsConfirmation(project, it) }
        if (guarded.isNotEmpty() && Messages.showYesNoDialog(
                project,
                SecretsBundle.message("move.prod.message", secrets.size, guarded.joinToString()),
                SecretsBundle.message("prod.guard.title"),
                SecretsBundle.message("move.ok"),
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        if (copy) return copySecrets(target)
        val update = updateReferences.isSelected
        val (result, plan) = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Pair<MoveResult, ReferenceUpdater.Plan?>, SecretProviderException>(
                {
                    val moved = SecretsService.getInstance().moveSecrets(connection, source, secrets, target, overwrite.isSelected)
                    // The references of what moved (not what waits for approval or exists in both places now)
                    moved to moved.moved.takeIf { update && it.isNotEmpty() }?.let {
                        ReferenceUpdater.planInReadAction(project, connection, it, source, target)
                    }
                },
                SecretsBundle.message("move.progress", secrets.size),
                true,
                project,
            )
        } catch (e: SecretProviderException) {
            setErrorText(e.message ?: e.kind.name)
            return
        } catch (e: IllegalArgumentException) {
            setErrorText(e.message)
            return
        }
        plan?.let { ReferenceUpdater.getInstance(project).apply(it) }
        KeysIndex.getInstance(project).refreshAsync()
        report(project, result, "${target.environment}:${target.path}", plan, UndoMove(project, connection, source, target, result.moved, update))
        super.doOKAction()
        onMoved(target, result)
    }

    /** Creates each secret at [target] with its value and comment; the originals stay. */
    private fun copySecrets(target: SecretScope) {
        val failures = linkedMapOf<String, String>()
        val copied = mutableListOf<String>()
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Unit, SecretProviderException>(
                {
                    val service = SecretsService.getInstance()
                    for (entry in secrets) {
                        if (entry.valueHidden) {
                            failures[entry.key] = SecretsBundle.message("copy.hidden")
                            continue
                        }
                        try {
                            service.createSecret(connection, target, entry.key, entry.value, entry.comment)
                            copied += entry.key
                        } catch (e: SecretProviderException) {
                            failures[entry.key] = e.message ?: e.kind.name
                        }
                    }
                },
                SecretsBundle.message("copy.progress", secrets.size),
                true,
                project,
            )
        } catch (e: SecretProviderException) {
            setErrorText(e.message ?: e.kind.name)
            return
        }
        KeysIndex.getInstance(project).refreshAsync()
        val where = "${target.environment}:${target.path}"
        val lines = buildList {
            if (copied.isNotEmpty()) add(SecretsBundle.message("copy.done", copied.joinToString(), where))
            failures.forEach { (key, reason) -> add(SecretsBundle.message("copy.failed", key, reason)) }
        }
        if (failures.isEmpty()) SecretsNotifications.info(project, SecretsBundle.message("copy.title.done"), lines.joinToString("\n"))
        else SecretsNotifications.error(project, SecretsBundle.message("copy.title.partial"), lines.joinToString("\n"))
        super.doOKAction()
        onMoved(target, MoveResult(moved = emptyList()))
    }

    companion object {
        /** One notification saying what moved, what waits for approval and what now exists in both places. */
        fun report(project: Project, result: MoveResult, where: String, plan: ReferenceUpdater.Plan? = null, undo: UndoMove? = null) {
            val lines = buildList {
                if (result.moved.isNotEmpty()) add(SecretsBundle.message("move.done", result.moved.joinToString(), where))
                if (plan != null && !plan.isEmpty) add(plan.summary())
                if (result.awaitingApproval.isNotEmpty()) add(SecretsBundle.message("move.approval", result.awaitingApproval.joinToString(), where))
                result.notRemoved.forEach { (key, reason) -> add(SecretsBundle.message("move.not.removed", key, reason)) }
            }
            if (result.notRemoved.isEmpty()) {
                val actions = listOfNotNull(undo?.takeIf { result.moved.isNotEmpty() }?.let { SecretsBundle.message("move.undo.action") to it::run })
                SecretsNotifications.info(project, SecretsBundle.message("move.title.done"), lines.joinToString("\n"), actions)
            } else {
                SecretsNotifications.error(project, SecretsBundle.message("move.title.partial"), lines.joinToString("\n"))
            }
        }
    }
}

/**
 * *Undo Move* in the notification after a move: moves the secrets back to where they were (after confirming) and updates the
 * references back. Edit › Undo undoes only the reference updates; the secret manager can't be undone from there.
 */
class UndoMove(
    private val project: Project,
    private val connection: SecretConnection,
    private val from: SecretScope,
    private val to: SecretScope,
    private val keys: List<String>,
    private val updateReferences: Boolean,
) {
    fun run() {
        val guarded = listOf(from.environment, to.environment).distinct().filter { ProdGuard.needsConfirmation(project, it) }
        val message = SecretsBundle.message("move.undo.confirm", keys.size, "${to.environment}:${to.path}", "${from.environment}:${from.path}") +
            if (guarded.isEmpty()) "" else "\n\n" + SecretsBundle.message("move.prod.message", keys.size, guarded.joinToString())
        if (Messages.showYesNoDialog(project, message, SecretsBundle.message("move.undo.title"), SecretsBundle.message("move.undo.action"), Messages.getCancelButton(), Messages.getQuestionIcon()) != Messages.YES) return
        val (result, plan) = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Pair<MoveResult, ReferenceUpdater.Plan?>, SecretProviderException>(
                {
                    val service = SecretsService.getInstance()
                    // As they are now at the destination (their IDs there are new)
                    val entries = service.secrets(connection, to, fresh = true)
                        .filter { it.key in keys && it.path == to.path && it.origin == cz.xprees.secrets.core.model.SecretOrigin.LOCAL }
                    val back = service.moveSecrets(connection, to, entries, from, overwrite = false)
                    back to back.moved.takeIf { updateReferences && it.isNotEmpty() }?.let { ReferenceUpdater.planInReadAction(project, connection, it, to, from) }
                },
                SecretsBundle.message("move.progress", keys.size),
                true,
                project,
            )
        } catch (e: SecretProviderException) {
            SecretsNotifications.error(project, SecretsBundle.message("move.undo.title"), e.message ?: e.kind.name)
            return
        }
        plan?.let { ReferenceUpdater.getInstance(project).apply(it) }
        KeysIndex.getInstance(project).refreshAsync()
        MoveSecretsDialog.report(project, result, "${from.environment}:${from.path}", plan)
    }
}
