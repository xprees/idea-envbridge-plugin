package cz.xprees.secrets.editor

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.LocalOverrides
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.run.SecretsNotifications
import cz.xprees.secrets.ui.SecretClipboard

/** Actions on a secret named in the editor; values are fetched in the background and never kept in the editor. */
object SecretValueActions {
    /** Fetches the value of [key] in [environment] (bound one when null) and [folder] (root when null), then calls [then] on the EDT. */
    private fun withValue(project: Project, key: String, environment: String?, folder: String? = null, then: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                val provider = SecretProvider.all().first()
                val binding = SecretBindingService.getInstance(project).binding(provider)
                val scope = SecretScope(binding.defaults().projectId, environment ?: binding.environment, SecretScope.normalizePath(folder ?: "/"))
                SecretsService.getInstance().secrets(binding.connection, scope).firstOrNull { it.key == key && !it.valueHidden }?.value?.reveal()
                    ?: error(SecretsBundle.message("editor.secret.missing", key, scope.environment))
            }
            ApplicationManager.getApplication().invokeLater({
                outcome.onSuccess(then).onFailure { SecretsNotifications.error(project, SecretsBundle.message("editor.secret.failed", key), it.message.orEmpty()) }
            }, ModalityState.any(), project.disposed)
        }
    }

    /** Shows the value in a hint next to the caret; it disappears on the next keystroke or caret move. */
    fun reveal(project: Project, editor: Editor, key: String, environment: String?, folder: String? = null) = withValue(project, key, environment, folder) { value ->
        if (!editor.isDisposed) HintManager.getInstance().showInformationHint(editor, "$key = $value")
    }

    fun copy(project: Project, key: String, environment: String?, folder: String? = null) = withValue(project, key, environment, folder, SecretClipboard::copy)

    /** Asks for a local value of [key] (kept in the credential store) or removes the existing one. */
    fun editOverride(project: Project, key: String) {
        val value = Messages.showPasswordDialog(project, SecretsBundle.message("override.prompt", key), SecretsBundle.message("override.title"), null)
            ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val overrides = LocalOverrides.getInstance(project)
            if (value.isEmpty()) overrides.remove(key) else overrides.set(key, value)
        }
    }
}
