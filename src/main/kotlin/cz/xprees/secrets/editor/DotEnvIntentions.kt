package cz.xprees.secrets.editor

import com.intellij.codeInsight.intention.IntentionAction
import cz.xprees.secrets.run.SecretsNotifications
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretOrigin
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleListCellRenderer
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
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
        val provider = SecretProvider.byId(snapshot.providerId) ?: return
        val binding = SecretBindingService.getInstance(project).bindingOrNull(provider) ?: return
        val projectId = binding.projectId ?: return

        // Every folder of the bound environment (root and subfolders), plus what the bound folder sees through imports
        object : Task.Backgroundable(project, SecretsBundle.message("dotenv.bind.loading"), true) {
            private var choices: List<DotEnvLines.KeyChoice> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val everywhere = SecretsService.getInstance()
                    .secrets(binding.connection, SecretScope(projectId, binding.environment, "/", recursive = true))
                    .filter { it.origin != SecretOrigin.IMPORTED }
                    .map { DotEnvLines.KeyChoice(it.key, it.path, it.comment) }
                // The root as a bare reference reads it (imports included)
                val root = snapshot.boundKeys.values.map { DotEnvLines.KeyChoice(it.key, "/", it.comment) }
                choices = DotEnvLines.rankChoices(line.name, "/", (root + everywhere).distinctBy { it.key to it.path })
            }

            override fun onSuccess() {
                if (choices.isEmpty()) return
                JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
                    .setTitle(SecretsBundle.message("dotenv.bind.choose.title", line.name, binding.environment, SecretsBundle.message("dotenv.bind.all.folders")))
                    .setRenderer(SimpleListCellRenderer.create("") { choice ->
                        val comment = choice.comment.lines().firstOrNull()?.takeIf { it.isNotBlank() }?.let { "  —  $it" }.orEmpty()
                        "${choice.key}    ${choice.path}$comment"
                    })
                    .setNamerForFiltering { "${it.key} ${it.path}" }
                    .setItemChosenCallback { choice ->
                        val reference = snapshot.reference(choice.key, choice.path)
                        if (!isPlaintext(project, line)) rebind(project, editor!!, line.name, reference, keepValue = true) else chooseValueHandling(project, editor!!, line, reference)
                    }
                    .createPopup()
                    .showInBestPositionFor(editor!!)
            }

            override fun onThrowable(error: Throwable) {
                SecretsNotifications.error(project, SecretsBundle.message("dotenv.bind.failed"), error.message.orEmpty())
            }
        }.queue()
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
        // Read-only (settings): no writes, so no extracting
        return SecretsService.getInstance().writesAllowed && KeysIndex.getInstance(project).current() != null
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
            // The comment above the variable, if any, describes it: prefill it as the secret's comment
            comment = DotEnvLines.commentAbove(editor!!.document.text, line),
        ) { location, outcome ->
            // Bind only to a secret that exists; a pending change request would make launches fail
            if (outcome != WriteOutcome.APPLIED || location.key == null) return@NewSecretDialog
            val reference = snapshot.reference(location.key, location.path, location.environment)
            rebind(project, editor!!, line.name, reference, keepValue = false)
        }.show()
    }
}
