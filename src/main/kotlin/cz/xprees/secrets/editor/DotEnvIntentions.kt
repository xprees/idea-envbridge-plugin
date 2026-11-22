package cz.xprees.secrets.editor

import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
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
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.core.varlock.DEFAULT_INSTANCE
import cz.xprees.secrets.core.varlock.VarlockProvider
import cz.xprees.secrets.core.varlock.VarlockSecrets
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
    line.value.isNotBlank() && !LaunchSecretResolver.getInstance(project).hasReferences(mapOf(line.name to line.value)) &&
        !(SecretsAppSettings.getInstance().state.varlockCompatibility && isVarlockValue(line.value))

/** A varlock function value (`infisical()`, `concat(…)`) or interpolation (`${OTHER}`): not a plaintext secret. */
private fun isVarlockValue(value: String): Boolean = VARLOCK_CALL.matches(value.trim()) || "\${" in value

private val VARLOCK_CALL = Regex("""[A-Za-z][A-Za-z0-9_]*\(.*\)""", RegexOption.DOT_MATCHES_ALL)

/**
 * Binds the line [name] to [reference]: with a binding comment (`KEY= # infisical://KEY`), or, in the varlock mode when the value
 * is cleared, with the varlock function that reads it (`KEY=infisical()`, see [varlockCall]).
 */
private fun rebind(project: Project, editor: Editor, name: String, reference: String, keepValue: Boolean) {
    val call = if (!keepValue && SecretsAppSettings.getInstance().state.varlockCompatibility) varlockCall(editor.document.text, name, reference) else null
    WriteCommandAction.runWriteCommandAction(project, SecretsBundle.message("dotenv.bind.intention.family"), null, {
        if (call != null) DotEnvLines.setValue(editor.document, name, call) else DotEnvLines.bind(editor.document, name, reference, keepValue)
    })
}

/**
 * The varlock function for [reference] as the value of [name] in the `.env` file [text], relative to the file's default
 * `@init…` instance (its project, environment, path prefix), or null when varlock can't name it that way.
 */
internal fun varlockCall(text: String, name: String, reference: String): String? {
    val parsed = runCatching { SecretReference.parse(reference, SecretProvider.all().map { it.referenceScheme }) }.getOrNull() ?: return null
    val provider = VarlockProvider.entries.firstOrNull { it.scheme.equals(parsed.scheme, ignoreCase = true) } ?: return null
    val init = VarlockSecrets.staticInits(EnvSpecParser.parse(text))[provider to DEFAULT_INSTANCE].orEmpty()
    return VarlockSecrets.call(parsed, name, init)
}

/**
 * *Bind to Secret…*: pick any secret of the bound environment for this `.env` variable (same and similar names
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
                    .setRenderer(textListCellRenderer { choice ->
                        val comment = choice?.comment?.lines()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "  —  $it" }.orEmpty()
                        choice?.let { "${it.key}    ${it.path}$comment" }
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
 * *Extract Value to Secret Manager…*: creates a secret from a plaintext `.env` value (New Secret dialog, prefilled), then binds
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
        val snapshot = KeysIndex.getInstance(project).current() ?: return false
        return SecretsService.getInstance().writesAllowed(snapshot.providerId)
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
