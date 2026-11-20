package cz.xprees.secrets.ui.secrets

import com.intellij.openapi.application.ModalityState

import com.intellij.openapi.application.ApplicationManager

import com.intellij.ui.TextFieldWithAutoCompletion

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.run.SecretsNotifications
import cz.xprees.secrets.ui.toolwindow.SecretLocation
import java.util.Arrays
import javax.swing.JComponent

/**
 * Creates a secret in the secret manager: key, value (masked unless *Show* is ticked), comment, environment and folder. The
 * value goes straight to the provider; it isn't kept anywhere in the IDE. The dialog stays open with the reason when the
 * provider refuses (read-only token, missing permission, the key exists).
 */
class NewSecretDialog(
    private val project: Project,
    private val connection: SecretConnection,
    private val projectId: String,
    environments: List<String>,
    environment: String,
    path: String,
    key: String = "",
    value: String = "",
    comment: String = "",
    private val onCreated: (SecretLocation, WriteOutcome) -> Unit = { _, _ -> },
) : DialogWrapper(project) {
    private val keyField = JBTextField(key, 30)
    private val valueField = JBPasswordField().apply { text = value; columns = 30 }
    private val showValue = JBCheckBox(SecretsBundle.message("new.secret.show")).apply {
        addActionListener { valueField.echoChar = if (isSelected) 0.toChar() else '•' }
    }
    private val commentField = JBTextField(comment, 30)
    private val environmentCombo = ComboBox((environments.ifEmpty { listOf(environment) }).toTypedArray()).apply { selectedItem = environment }
    // Completion (Ctrl+Space, and as you type) of the environment's folders, loaded in the background
    private val pathField = TextFieldWithAutoCompletion.create(project, listOf(path), true, path)

    init {
        environmentCombo.addActionListener { loadFolders() }
        loadFolders()
        title = SecretsBundle.message("new.secret.title")
        setOKButtonText(SecretsBundle.message("new.secret.create"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("new.secret.key")) { cell(keyField).align(AlignX.FILL).focused() }
        row(SecretsBundle.message("new.secret.value")) {
            cell(valueField).align(AlignX.FILL).resizableColumn()
            cell(showValue)
        }
        row(SecretsBundle.message("new.secret.comment")) { cell(commentField).align(AlignX.FILL) }
        row(SecretsBundle.message("new.secret.environment")) { cell(environmentCombo) }
        row(SecretsBundle.message("new.secret.folder")) { cell(pathField).align(AlignX.FILL).comment(SecretsBundle.message("new.secret.folder.comment")) }
        row { comment(SecretsBundle.message("new.secret.note", connection.name)) }
    }

    /** Offers the folders of the selected environment (a stale answer for another environment is dropped). */
    private fun loadFolders() {
        val environment = environmentCombo.selectedItem as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val folders = runCatching { SecretsService.getInstance().allFolders(connection, projectId, environment) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (environmentCombo.selectedItem == environment) pathField.setVariants(folders)
            }, ModalityState.any())
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (keyField.text.isEmpty()) keyField else valueField

    override fun doValidate(): ValidationInfo? = when {
        !KEY.matches(keyField.text.trim()) -> ValidationInfo(SecretsBundle.message("new.secret.invalid.key"), keyField)
        !pathField.text.trim().let { it.isEmpty() || it.startsWith("/") } -> ValidationInfo(SecretsBundle.message("new.secret.invalid.folder"), pathField)
        else -> null
    }

    override fun doOKAction() {
        val key = keyField.text.trim()
        val environment = environmentCombo.selectedItem as? String ?: return
        val scope = SecretScope(projectId, environment, SecretScope.normalizePath(pathField.text.trim()))
        if (ProdGuard.needsConfirmation(project, environment) && Messages.showYesNoDialog(
                project,
                SecretsBundle.message("new.secret.prod.message", key, environment),
                SecretsBundle.message("prod.guard.title"),
                SecretsBundle.message("new.secret.create"),
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        val chars = valueField.password
        val value = SecretValue(String(chars))
        Arrays.fill(chars, ' ')
        val outcome = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<WriteOutcome, SecretProviderException>(
                { SecretsService.getInstance().createSecret(connection, scope, key, value, commentField.text.trim()) },
                SecretsBundle.message("new.secret.creating", key),
                true,
                project,
            )
        } catch (e: SecretProviderException) {
            setErrorText(e.message ?: e.kind.name)
            return
        }
        KeysIndex.getInstance(project).refreshAsync()
        // Where the secret ended up (a 1Password secret created at a vault's root is a new item)
        val (createdPath, createdKey) = SecretProvider.byId(connection.providerId)?.createdSecretLocation(scope, key) ?: (scope.path to key)
        val where = "${scope.environment}:$createdPath"
        SecretsNotifications.info(
            project,
            SecretsBundle.message(if (outcome == WriteOutcome.APPLIED) "new.secret.created" else "new.secret.approval", createdKey, where),
        )
        super.doOKAction()
        onCreated(SecretLocation(connection.id, projectId, scope.environment, createdPath, createdKey), outcome)
    }

    companion object {
        private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")
    }
}
