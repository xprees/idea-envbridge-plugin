package cz.xprees.secrets.ui.secrets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.run.SecretsNotifications
import cz.xprees.secrets.onepassword.OnePasswordProvider
import java.util.Arrays
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Changes the value (and comment) of a secret, and renames it when its key is changed. The current value is filled in masked;
 * *Show* reveals it, and a value left as it is stays as it is. Like [NewSecretDialog]: production-like environments ask
 * first, the dialog stays open with the provider's reason on failure, and the value isn't kept in the IDE. After a rename the
 * project's references to the old key are updated (one command, Edit › Undo puts them back) unless that is turned off.
 */
class EditSecretDialog(
    private val project: Project,
    private val connection: SecretConnection,
    private val scope: SecretScope,
    private val entry: SecretEntry,
    /** Called after saving, with the secret's key afterwards (the new one after a rename). */
    private val onSaved: (outcome: WriteOutcome, key: String) -> Unit = { _, _ -> },
) : DialogWrapper(project) {
    private val keyField = JBTextField(entry.key, 30)
    private val valueField = JBPasswordField().apply { text = entry.value.reveal(); columns = 30 }
    private val showValue = JBCheckBox(SecretsBundle.message("new.secret.show")).apply {
        addActionListener { valueField.echoChar = if (isSelected) 0.toChar() else '•' }
    }
    private val commentField = JBTextField(entry.comment, 30)
    private val updateReferences = JBCheckBox(SecretsBundle.message("move.update.references"), true)

    /** A secret rotation writes its value and name (Infisical): those fields are read-only, the comment and tags aren't. */
    private val rotated = entry.metadata?.rotation?.managed == true

    /** Tags and other metadata the provider lets the dialog change (filled in once read). */
    private val metadataFields = MetadataFields()

    /** Keys of the other secrets defined in the folder (loaded in the background; empty until then). */
    @Volatile
    private var folderKeys: Set<String> = emptySet()

    private val renamed = object : ComponentPredicate() {
        override fun invoke(): Boolean = newKey() != null

        override fun addListener(listener: (Boolean) -> Unit) {
            keyField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = listener(invoke())
            })
        }
    }

    init {
        title = SecretsBundle.message("edit.secret.title", entry.key)
        setOKButtonText(SecretsBundle.message("edit.secret.save"))
        if (rotated) {
            keyField.isEditable = false
            valueField.isEditable = false
        }
        init()
        loadFolderKeys()
        loadMetadataEditor()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("edit.secret.where")) { label("${scope.environment}:${scope.path}") }
        row(SecretsBundle.message("edit.secret.key")) { cell(keyField).align(AlignX.FILL) }
        row(SecretsBundle.message("new.secret.value")) {
            cell(valueField).align(AlignX.FILL).resizableColumn().focused()
            cell(showValue)
        }
        row(SecretsBundle.message("new.secret.comment")) { cell(commentField).align(AlignX.FILL) }
        if (connection.providerId == cz.xprees.secrets.aws.AwsSecretsManagerProvider.ID) {
            row { comment(SecretsBundle.message("edit.secret.comment.aws", scope.environment), maxLineLength = 70) }
        }
        row { cell(metadataFields.component).align(AlignX.FILL) }
        if (rotated) row { comment(SecretsBundle.message("edit.secret.rotated", entry.key), maxLineLength = 70) }
        row { cell(updateReferences).comment(SecretsBundle.message("edit.secret.update.references.comment")) }.visibleIf(renamed)
        row { comment(SecretsBundle.message("edit.secret.note")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (rotated) commentField else valueField

    /** Reads what the provider lets the dialog change of the secret's metadata, with its current value (off the EDT). */
    private fun loadMetadataEditor() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { SecretsService.getInstance().metadataEditor(connection, scope, entry.key) }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                result.onSuccess { editor -> editor?.let(metadataFields::show) }
                    .onFailure { e -> metadataFields.showError((e as? SecretProviderException)?.message ?: e.javaClass.simpleName) }
                pack()
            }, ModalityState.any())
        }
    }

    /** The new key when the key field names another one, else null. */
    private fun newKey(): String? = keyField.text.trim().takeIf { it != entry.key }

    private fun loadFolderKeys() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val secrets = runCatching { SecretsService.getInstance().secrets(connection, scope.copy(recursive = false)) }.getOrNull() ?: return@executeOnPooledThread
            val keys = secrets.filter { it.origin != SecretOrigin.IMPORTED && it.path == scope.path && it.key != entry.key }.map { it.key }.toSet()
            ApplicationManager.getApplication().invokeLater({ folderKeys = keys }, ModalityState.any())
        }
    }

    override fun doValidate(): ValidationInfo? {
        metadataFields.validate()?.let { return it }
        val key = newKey() ?: return null
        // 1Password field labels may contain spaces ("api key"); keys elsewhere are variable-like names
        val spacesAllowed = connection.providerId == OnePasswordProvider.ID
        return when {
            key.isEmpty() -> ValidationInfo(SecretsBundle.message("edit.secret.key.empty"), keyField)
            !spacesAllowed && key.any { it.isWhitespace() } -> ValidationInfo(SecretsBundle.message("edit.secret.key.whitespace"), keyField)
            key in folderKeys -> ValidationInfo(SecretsBundle.message("edit.secret.key.exists", key, "${scope.environment}:${scope.path}"), keyField)
            else -> null
        }
    }

    override fun doOKAction() {
        val newKey = newKey()
        val prodMessage = if (newKey == null) {
            SecretsBundle.message("edit.secret.prod.message", entry.key, scope.environment)
        } else {
            SecretsBundle.message("edit.secret.prod.rename.message", entry.key, newKey, scope.environment)
        }
        if (ProdGuard.needsConfirmation(project, scope.environment) && Messages.showYesNoDialog(
                project,
                prodMessage,
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
        val update = newKey != null && updateReferences.isSelected
        val metadata = metadataFields.result()
        // Only the metadata changed: the value isn't written again (no new version, no write permission on the value needed)
        val metadataOnly = metadata != null && newKey == null && comment == null && value == entry.value
        // Set when a rename went through but a later step of it failed (a provider's note, say): reported, not an error
        var warning: String? = null
        val (outcome, plan) = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Pair<WriteOutcome, ReferenceUpdater.Plan?>, SecretProviderException>(
                {
                    val service = SecretsService.getInstance()
                    val outcome = if (metadataOnly) WriteOutcome.APPLIED else try {
                        service.updateSecret(connection, scope, entry.key, value, comment, newKey)
                    } catch (e: SecretProviderException) {
                        // A rename that failed after renaming: the secret has its new name, so finish (references, refresh) and tell
                        if (newKey == null || !renamed(newKey)) throw e
                        warning = e.message ?: e.kind.name
                        WriteOutcome.APPLIED
                    }
                    // The metadata is a write of its own (Vault: another endpoint and policy): its failure keeps the value saved
                    if (metadata != null && outcome == WriteOutcome.APPLIED) {
                        try {
                            service.updateMetadata(connection, scope, newKey ?: entry.key, metadata)
                        } catch (e: SecretProviderException) {
                            if (metadataOnly) throw e
                            warning = listOfNotNull(warning, SecretsBundle.message("edit.secret.metadata.failed", e.message ?: e.kind.name)).joinToString("\n")
                        }
                    }
                    // A rename waiting for approval hasn't happened yet: its references stay
                    outcome to newKey?.takeIf { update && outcome == WriteOutcome.APPLIED }?.let {
                        ReferenceUpdater.planRenameInReadAction(project, connection, scope, entry.key, it)
                    }
                },
                SecretsBundle.message("edit.secret.saving", entry.key),
                // Not cancellable: a write half done would go unreported
                false,
                project,
            )
        } catch (e: SecretProviderException) {
            setErrorText(e.message ?: e.kind.name)
            return
        }
        plan?.let { ReferenceUpdater.getInstance(project).apply(it) }
        KeysIndex.getInstance(project).refreshAsync()
        val where = "${scope.environment}:${scope.path}"
        val message = when {
            outcome != WriteOutcome.APPLIED -> SecretsBundle.message("edit.secret.approval", entry.key, where)
            newKey != null -> SecretsBundle.message("edit.secret.renamed", entry.key, newKey, where)
            metadataOnly -> SecretsBundle.message("edit.secret.metadata.saved", entry.key, where)
            else -> SecretsBundle.message("edit.secret.saved", entry.key, where)
        }
        val details = listOfNotNull(warning, plan?.summary()?.takeIf { it.isNotEmpty() }).joinToString("\n")
        SecretsNotifications.info(project, message, details, ReferenceUpdater.followUpActions(project, plan))
        super.doOKAction()
        onSaved(outcome, if (outcome == WriteOutcome.APPLIED) newKey ?: entry.key else entry.key)
    }

    /** Whether the folder now has [newKey] and not the old key (read fresh). Off the EDT. */
    private fun renamed(newKey: String): Boolean = runCatching {
        val keys = SecretsService.getInstance().secrets(connection, scope.copy(recursive = false), fresh = true)
            .filter { it.path == scope.path && it.origin == cz.xprees.secrets.core.model.SecretOrigin.LOCAL }.map { it.key }
        newKey in keys && entry.key !in keys
    }.getOrDefault(false)
}
