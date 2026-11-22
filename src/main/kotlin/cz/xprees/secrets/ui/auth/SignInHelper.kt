package cz.xprees.secrets.ui.auth

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.run.SecretsNotifications

/** Runs sign-in and sign-out in the background (a browser sign-in waits for the user for up to a few minutes). */
object SignInHelper {
    fun signIn(project: Project?, connection: SecretConnection, onDone: (Boolean) -> Unit = {}) {
        object : Task.Backgroundable(project, SecretsBundle.message("signin.progress", connection.name), true) {
            private var account: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                account = SecretsService.getInstance().client(connection).signIn(IdeSignInInteraction(project, indicator))
            }

            override fun onSuccess() {
                SecretsService.getInstance().invalidate(connection.id)
                SecretsAppSettings.getInstance().fireChanged()
                SecretsNotifications.info(project, SecretsBundle.message("signin.done", connection.name), account.orEmpty())
                onDone(true)
            }

            override fun onThrowable(error: Throwable) {
                if ((error as? SecretProviderException)?.kind != SecretProviderException.Kind.CANCELLED) {
                    SecretsNotifications.error(project, SecretsBundle.message("signin.failed", connection.name), error.message ?: error.toString())
                }
                onDone(false)
            }

            override fun onCancel() = onDone(false)
        }.queue()
    }

    fun signOut(project: Project?, connection: SecretConnection, onDone: () -> Unit = {}) {
        object : Task.Backgroundable(project, SecretsBundle.message("signout.progress", connection.name), false) {
            override fun run(indicator: ProgressIndicator) {
                SecretsService.getInstance().client(connection).signOut()
            }

            override fun onFinished() {
                SecretsService.getInstance().invalidate(connection.id)
                SecretsAppSettings.getInstance().fireChanged()
                onDone()
            }
        }.queue()
    }
}
