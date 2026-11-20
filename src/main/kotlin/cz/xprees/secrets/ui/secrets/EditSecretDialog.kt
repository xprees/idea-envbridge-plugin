package cz.xprees.secrets.ui.secrets

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.run.SecretsNotifications
import java.util.Arrays
import javax.swing.JComponent

/**
 * Changes the value (and comment) of a secret. The current value is filled in masked; *Show* reveals it. Like
 * [NewSecretDialog]: production-like environments ask first, the dialog stays open with the provider's reason on failure,
 * and the value isn't kept in the IDE.
 */
class EditSecretDialog(
    private val project: Project,
    private val connection: SecretConnection,
    private val scope: SecretScope,
    private val entry: SecretEntry,
    private val onSaved: (WriteOutcome) -> Unit = {},
) : DialogWrapper(project) {
    private val valueField = JBPasswordField().apply { text = entry.value.reveal(); columns = 30 }
    private val showValue = JBCheckBox(SecretsBundle.message("new.secret.show")).apply {
        addActionListener { valueField.echoChar = if (isSelected) 0.toChar() else '•' }
    }
    private val commentField = JBTextField(entry.comment, 30)

    init {
        title = SecretsBundle.message("edit.secret.title", entry.key)
        setOKButtonText(SecretsBundle.message("edit.secret.save"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("edit.secret.where")) { label("${scope.environment}:${scope.path}") }
        row(SecretsBundle.message("new.secret.value")) {
            cell(valueField).align(AlignX.FILL).resizableColumn().focused()
            cell(showValue)
        }
        row(SecretsBundle.message("new.secret.comment")) { cell(commentField).align(AlignX.FILL) }
        row { comment(SecretsBundle.message("edit.secret.note")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = valueField

    override fun doOKAction() {
        if (ProdGuard.matches(scope.environment) && Messages.showYesNoDialog(
                project,
                SecretsBundle.message("edit.secret.prod.message", entry.key, scope.environment),
                SecretsBundle.message("prod.guard.title"),
                SecretsBundle.message("edit.secret.save"),
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        val chars = valueField.password
        val value = SecretValue(String(chars))
        Arrays.fill(chars, ' ')
        val comment = commentField.text.trim().takeIf { it != entry.comment }
        val outcome = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<WriteOutcome, SecretProviderException>(
                { SecretsService.getInstance().updateSecret(connection, scope, entry.key, value, comment) },
                SecretsBundle.message("edit.secret.saving", entry.key),
                true,
                project,
            )
        } catch (e: SecretProviderException) {
            setErrorText(e.message ?: e.kind.name)
            return
        }
        KeysIndex.getInstance(project).refreshAsync()
        val where = "${scope.environment}:${scope.path}"
        SecretsNotifications.info(
            project,
            SecretsBundle.message(if (outcome == WriteOutcome.APPLIED) "edit.secret.saved" else "edit.secret.approval", entry.key, where),
        )
        super.doOKAction()
        onSaved(outcome)
    }
}
