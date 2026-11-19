package cz.xprees.secrets.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleListCellRenderer
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.CreateOutcome
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.ui.secrets.NewSecretDialog

/** The `.env` line under the caret, if any. */
private fun lineAtCaret(editor: Editor?, file: PsiFile?): DotEnvLines.Line? {
    if (editor == null || file == null || !DotEnvLines.isDotEnv(file.name)) return null
    val document = editor.document
    val lineNumber = document.getLineNumber(editor.caretModel.offset)
    val range = TextRange(document.getLineStartOffset(lineNumber), document.getLineEndOffset(lineNumber))
    return DotEnvLines.parse(document.text).firstOrNull { range.containsOffset(it.valueRange.startOffset) }
}

private fun isPlaintext(project: Project, line: DotEnvLines.Line): Boolean =
    line.value.isNotBlank() && !LaunchSecretResolver.getInstance(project).hasReferences(mapOf(line.name to line.value))

private fun rebind(project: Project, editor: Editor, name: String, reference: String, keepValue: Boolean) {
    WriteCommandAction.runWriteCommandAction(project, SecretsBundle.message("dotenv.bind.intention.family"), null, {
        DotEnvLines.bind(editor.document, name, reference, keepValue)
    })
}

/**
 * *Bind to Infisical secret…*: pick any secret of the bound environment for this `.env` variable (same and similar names
 * first); a line with a value then asks whether to clear it or keep it as the local fallback.
 */
class DotEnvBindToSecretIntention : IntentionAction {
    override fun getFamilyName(): String = SecretsBundle.message("dotenv.bind.choose.family")

    override fun getText(): String = familyName

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val line = lineAtCaret(editor, file) ?: return false
        return line.binding == null && KeysIndex.getInstance(project).current()?.boundKeys?.isNotEmpty() == true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val line = lineAtCaret(editor, file) ?: return
        val snapshot = KeysIndex.getInstance(project).current() ?: return
        val keys = DotEnvLines.rankKeys(line.name, snapshot.boundKeys.keys)
        JBPopupFactory.getInstance().createPopupChooserBuilder(keys)
            .setTitle(SecretsBundle.message("dotenv.bind.choose.title", line.name, snapshot.boundEnvironment, snapshot.path))
            .setRenderer(SimpleListCellRenderer.create("") { key ->
                val comment = snapshot.boundKeys[key]?.comment?.lines()?.firstOrNull()?.takeIf { it.isNotBlank() }
                if (comment == null) key else "$key  —  $comment"
            })
            .setNamerForFiltering { it }
            .setItemChosenCallback { key ->
                val reference = SecretReference(snapshot.scheme, key).format()
                if (!isPlaintext(project, line)) rebind(project, editor!!, line.name, reference, keepValue = true) else chooseValueHandling(project, editor!!, line, reference)
            }
            .createPopup()
            .showInBestPositionFor(editor!!)
    }

    private fun chooseValueHandling(project: Project, editor: Editor, line: DotEnvLines.Line, reference: String) {
        val clear = SecretsBundle.message("dotenv.value.clear")
        val keep = SecretsBundle.message("dotenv.value.keep")
        JBPopupFactory.getInstance().createPopupChooserBuilder(listOf(clear, keep))
            .setTitle(SecretsBundle.message("dotenv.value.title", line.name))
            .setItemChosenCallback { choice -> rebind(project, editor, line.name, reference, keepValue = choice == keep) }
            .createPopup()
            .showInBestPositionFor(editor)
    }
}

/**
 * *Extract value to Infisical…*: creates a secret from a plaintext `.env` value (New Secret dialog, prefilled), then binds
 * the line to it and clears the value, so the file no longer holds the secret.
 */
class DotEnvExtractToSecretIntention : IntentionAction {
    override fun getFamilyName(): String = SecretsBundle.message("dotenv.extract.family")

    override fun getText(): String = familyName

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val line = lineAtCaret(editor, file) ?: return false
        if (line.binding != null || !isPlaintext(project, line)) return false
        return KeysIndex.getInstance(project).current() != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val line = lineAtCaret(editor, file) ?: return
        val snapshot = KeysIndex.getInstance(project).current() ?: return
        val provider = SecretProvider.byId(snapshot.providerId) ?: return
        val binding = SecretBindingService.getInstance(project).bindingOrNull(provider) ?: return
        val projectId = binding.projectId ?: return
        NewSecretDialog(
            project, binding.connection, projectId, snapshot.environments.keys.toList(),
            environment = binding.environment, path = binding.path, key = line.name, value = line.value,
        ) { location, outcome ->
            // Bind only to a secret that exists; a pending change request would make launches fail
            if (outcome != CreateOutcome.CREATED || location.key == null) return@NewSecretDialog
            val sameScope = location.environment == binding.environment && location.path == binding.path
            val reference = SecretReference(
                snapshot.scheme, location.key,
                environment = if (sameScope) null else location.environment,
                path = if (sameScope) null else location.path,
            ).format()
            rebind(project, editor!!, line.name, reference, keepValue = false)
        }.show()
    }
}
