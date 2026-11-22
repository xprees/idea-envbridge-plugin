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

    /**
     * A launch stopped by varlock values (varlock compatibility mode) that are refused, unsupported or can't be resolved: one line
     * per key and function, never a value. *Open* goes to the first file and line named.
     */
    fun varlockFailed(project: Project, configurationName: String, failure: VarlockLaunchException, retry: () -> Unit) {
        val notification = group().createNotification(
            SecretsBundle.message("varlock.launch.failed.title", configurationName),
            escape(failure.message.orEmpty()).replace("\n", "<br>"),
            NotificationType.ERROR,
        )
        failure.problems.firstNotNullOfOrNull { it.location }?.let { location ->
            notification.addAction(NotificationAction.createSimple(SecretsBundle.message("varlock.launch.open", location.file.fileName.toString())) {
                val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(location.file) ?: return@createSimple
                com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, location.line, 0).navigate(true)
            })
        }
        notification.addAction(NotificationAction.createSimpleExpiring(SecretsBundle.message("action.retry"), retry))
        notification.addAction(NotificationAction.createSimple(SecretsBundle.message("action.settings")) {
            SecretsConfigurable.show(project)
        })
        notification.notify(project)
    }

    /** A launch stopped because required keys (env-spec `@required`) have no value. Names only. */
    fun requiredMissing(project: Project, configurationName: String, keys: List<String>, retry: () -> Unit) {
        val notification = group().createNotification(
            SecretsBundle.message("envspec.launch.required.title", configurationName),
            escape(SecretsBundle.message("envspec.launch.required", keys.size, keys.joinToString(", "))),
            NotificationType.ERROR,
        )
        notification.addAction(NotificationAction.createSimpleExpiring(SecretsBundle.message("action.retry"), retry))
        notification.addAction(NotificationAction.createSimple(SecretsBundle.message("action.settings")) {
            SecretsConfigurable.show(project)
        })
        notification.notify(project)
    }

    /** Non-fatal problems of a launch; [titleKey] picks the title (secrets left out by default). */
    fun launchWarnings(project: Project, configurationName: String, warnings: List<String>, titleKey: String = "run.warnings.title") {
        group().createNotification(
            SecretsBundle.message(titleKey, configurationName),
            warnings.joinToString("<br>") { escape(it) },
            NotificationType.WARNING,
        ).notify(project)
    }

    fun info(project: Project?, title: String, content: String = "", actions: List<Pair<String, () -> Unit>> = emptyList()) {
        val notification = group().createNotification(title, escape(content).replace("\n", "<br>"), NotificationType.INFORMATION)
        actions.forEach { (text, run) ->
            notification.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(text) { run() })
        }
        notification.notify(project)
    }

    fun error(project: Project?, title: String, content: String) {
        group().createNotification(title, escape(content).replace("\n", "<br>"), NotificationType.ERROR).notify(project)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
