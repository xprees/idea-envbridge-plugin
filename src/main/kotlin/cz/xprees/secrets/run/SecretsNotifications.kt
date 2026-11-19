package cz.xprees.secrets.run

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.ui.settings.SecretsConfigurable
import cz.xprees.secrets.ui.toolwindow.SecretsToolWindowFactory

object SecretsNotifications {
    private const val GROUP = "EnvBridge"

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)

    fun launchFailed(
        project: Project,
        configurationName: String,
        failure: SecretProviderException,
        retry: () -> Unit,
        runWithoutSecrets: () -> Unit,
    ) {
        val notification = group().createNotification(
            SecretsBundle.message("run.failed.title", configurationName),
            escape(failure.message.orEmpty()).replace("\n", "<br>"),
            NotificationType.ERROR,
        )
        if (failure.kind == SecretProviderException.Kind.AUTH_REQUIRED) {
            notification.addAction(NotificationAction.createSimpleExpiring(SecretsBundle.message("action.sign.in")) {
                SecretsToolWindowFactory.show(project)
            })
        }
        notification.addAction(NotificationAction.createSimpleExpiring(SecretsBundle.message("action.retry"), retry))
        notification.addAction(NotificationAction.createSimpleExpiring(SecretsBundle.message("action.run.without.secrets"), runWithoutSecrets))
        notification.addAction(NotificationAction.createSimple(SecretsBundle.message("action.settings")) {
            SecretsConfigurable.show(project)
        })
        notification.notify(project)
    }

    fun launchWarnings(project: Project, configurationName: String, warnings: List<String>) {
        group().createNotification(
            SecretsBundle.message("run.warnings.title", configurationName),
            warnings.joinToString("<br>") { escape(it) },
            NotificationType.WARNING,
        ).notify(project)
    }

    fun info(project: Project?, title: String, content: String = "") {
        group().createNotification(title, escape(content), NotificationType.INFORMATION).notify(project)
    }

    fun error(project: Project?, title: String, content: String) {
        group().createNotification(title, escape(content).replace("\n", "<br>"), NotificationType.ERROR).notify(project)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
