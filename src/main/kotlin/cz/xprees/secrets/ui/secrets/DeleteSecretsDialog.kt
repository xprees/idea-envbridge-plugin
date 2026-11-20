package cz.xprees.secrets.ui.secrets

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.run.SecretsNotifications
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Deletes secrets after you type what is deleted: the key for one secret (a button copies it), or the number of secrets for
 * several. Production-like environments say so in the dialog.
 */
class DeleteSecretsDialog(
    private val project: Project,
    private val connection: SecretConnection,
    private val scope: SecretScope,
    private val keys: List<String>,
    private val onDeleted: (Map<String, Result<WriteOutcome>>) -> Unit = {},
) : DialogWrapper(project) {
    /** What has to be typed. */
    val expected: String = if (keys.size == 1) keys.single() else keys.size.toString()
    private val confirmation = JBTextField(30)

    init {
        title = SecretsBundle.message(if (keys.size == 1) "delete.title.one" else "delete.title.many", keys.size)
        setOKButtonText(SecretsBundle.message("delete.ok"))
        okAction.isEnabled = false
        confirmation.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                okAction.isEnabled = confirmation.text.trim() == expected
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("delete.what")) {
            label(keys.joinToString(", ").let { if (it.length > 90) it.take(90) + "…" else it })
            if (keys.size == 1) {
                button(SecretsBundle.message("delete.copy.name")) { CopyPasteManager.getInstance().setContents(StringSelection(expected)) }
                    .applyToComponent { icon = AllIcons.Actions.Copy }
            }
        }
        row(SecretsBundle.message("edit.secret.where")) { label("${scope.environment}:${scope.path}") }
        if (ProdGuard.isProduction(project, scope.environment)) row { comment(SecretsBundle.message("delete.prod")) }
        row(SecretsBundle.message(if (keys.size == 1) "delete.type.key" else "delete.type.count", expected)) {
            cell(confirmation).align(AlignX.FILL).focused()
        }
        row { comment(SecretsBundle.message("delete.note")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = confirmation

    override fun doValidate(): ValidationInfo? =
        if (confirmation.text.trim() != expected) ValidationInfo(SecretsBundle.message("delete.mismatch", expected), confirmation) else null

    override fun doOKAction() {
        if (confirmation.text.trim() != expected) return
        val results = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Map<String, Result<WriteOutcome>>, SecretProviderException>(
                { SecretsService.getInstance().deleteSecrets(connection, scope, keys) },
                SecretsBundle.message("delete.progress", keys.size),
                true,
                project,
            )
        } catch (e: SecretProviderException) {
            setErrorText(e.message ?: e.kind.name)
            return
        }
        KeysIndex.getInstance(project).refreshAsync()
        val where = "${scope.environment}:${scope.path}"
        val deleted = results.filterValues { it.getOrNull() == WriteOutcome.APPLIED }.keys
        val awaiting = results.filterValues { it.getOrNull() == WriteOutcome.APPROVAL_REQUESTED }.keys
        val failed = results.filterValues { it.isFailure }
        val lines = buildList {
            if (deleted.isNotEmpty()) add(SecretsBundle.message("delete.done", deleted.joinToString(), where))
            if (awaiting.isNotEmpty()) add(SecretsBundle.message("delete.approval", awaiting.joinToString(), where))
            failed.forEach { (key, r) -> add("$key: ${r.exceptionOrNull()?.message.orEmpty()}") }
        }
        if (failed.isEmpty()) SecretsNotifications.info(project, SecretsBundle.message("delete.title.done"), lines.joinToString("\n"))
        else SecretsNotifications.error(project, SecretsBundle.message("delete.title.partial"), lines.joinToString("\n"))
        super.doOKAction()
        onDeleted(results)
    }
}
