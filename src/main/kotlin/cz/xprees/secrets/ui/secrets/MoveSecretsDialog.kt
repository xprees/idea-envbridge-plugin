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
    private val onMoved: (SecretScope, MoveResult) -> Unit = { _, _ -> },
) : DialogWrapper(project) {
    private val environmentCombo = ComboBox(environments.ifEmpty { listOf(destination.environment) }.toTypedArray()).apply { selectedItem = destination.environment }
    private val pathField = TextFieldWithAutoCompletion.create(project, listOf(destination.path), true, destination.path)
    private val overwrite = JBCheckBox(SecretsBundle.message("move.overwrite"))

    init {
        title = SecretsBundle.message("move.title", secrets.size)
        setOKButtonText(SecretsBundle.message("move.ok"))
        environmentCombo.addActionListener { loadFolders() }
        loadFolders()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("move.secrets")) { label(secrets.joinToString(", ") { it.key }.let { if (it.length > 90) it.take(90) + "…" else it }) }
        row(SecretsBundle.message("move.from")) { label("${source.environment}:${source.path}") }
        row(SecretsBundle.message("new.secret.environment")) { cell(environmentCombo) }
        row(SecretsBundle.message("new.secret.folder")) { cell(pathField).align(AlignX.FILL).comment(SecretsBundle.message("new.secret.folder.comment")) }
        row { cell(overwrite) }
        row { comment(SecretsBundle.message("move.note")) }
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
        val result = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<MoveResult, SecretProviderException>(
                { SecretsService.getInstance().moveSecrets(connection, source, secrets, target, overwrite.isSelected) },
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
        KeysIndex.getInstance(project).refreshAsync()
        report(project, result, "${target.environment}:${target.path}")
        super.doOKAction()
        onMoved(target, result)
    }

    companion object {
        /** One notification saying what moved, what waits for approval and what now exists in both places. */
        fun report(project: Project, result: MoveResult, where: String) {
            val lines = buildList {
                if (result.moved.isNotEmpty()) add(SecretsBundle.message("move.done", result.moved.joinToString(), where))
                if (result.awaitingApproval.isNotEmpty()) add(SecretsBundle.message("move.approval", result.awaitingApproval.joinToString(), where))
                result.notRemoved.forEach { (key, reason) -> add(SecretsBundle.message("move.not.removed", key, reason)) }
            }
            if (result.notRemoved.isEmpty()) {
                SecretsNotifications.info(project, SecretsBundle.message("move.title.done"), lines.joinToString("\n"))
            } else {
                SecretsNotifications.error(project, SecretsBundle.message("move.title.partial"), lines.joinToString("\n"))
            }
        }
    }
}
