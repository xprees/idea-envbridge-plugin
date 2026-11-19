package cz.xprees.secrets.terminal

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.run.SecretsNotifications
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Puts secrets into the shell of the **current** terminal tab, in that shell's own syntax (see [ShellSyntax]): PowerShell,
 * cmd, bash / zsh / sh (also Git Bash and WSL) or fish, decided from the tab's shell. Values travel through a short-lived
 * owner-only file that the shell loads and deletes, never through the terminal's screen or history.
 */
class LoadSecretsIntoTerminalAction : DumbAwareAction(AllIcons.Actions.Download) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && currentWidget(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val widget = currentWidget(project) ?: return
        val command = widget.shellCommand ?: runCatching {
            ParametersListUtil.parse(TerminalProjectOptionsProvider.getInstance(project).shellPath)
        }.getOrNull()
        val syntax = ShellSyntax.detect(command, SystemInfo.isWindows)
        if (syntax == null) {
            SecretsNotifications.error(
                project,
                SecretsBundle.message("terminal.load.unsupported.title"),
                SecretsBundle.message("terminal.load.unsupported", command?.firstOrNull() ?: "?"),
            )
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            // Typing into a running program would hand it the command instead of the shell
            @Suppress("UnstableApiUsage")
            val busy = runCatching { widget.isCommandRunning() }.getOrDefault(false)
            ApplicationManager.getApplication().invokeLater {
                if (busy) {
                    SecretsNotifications.error(project, SecretsBundle.message("terminal.load.busy.title"), SecretsBundle.message("terminal.load.busy"))
                } else {
                    TerminalSecrets.fetch(project) { resolved -> load(project, widget, syntax, resolved.environment, resolved.summary()) }
                }
            }
        }
    }

    private fun load(project: Project, widget: TerminalWidget, syntax: ShellSyntax, environment: Map<String, String>, summary: String?) {
        val skipped = syntax.unsupported(environment)
        val file = try {
            writeOwnerOnly(syntax.file(environment), syntax.extension)
        } catch (e: Exception) {
            LOG.warn("Could not write the terminal secrets file", e)
            SecretsNotifications.error(project, SecretsBundle.message("terminal.failed"), e.message.orEmpty())
            return
        }
        // The shell deletes the file right away; this is only the fallback when it couldn't run the command
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ runCatching { Files.deleteIfExists(file) } }, 2, TimeUnit.MINUTES)
        widget.sendCommandToExecute(syntax.loadCommand(file))
        SecretsNotifications.info(
            project,
            SecretsBundle.message("terminal.loaded", syntax.displayName),
            listOfNotNull(summary, skipped.takeIf { it.isNotEmpty() }?.let { SecretsBundle.message("terminal.load.skipped", it.joinToString()) })
                .joinToString("\n"),
        )
    }

    companion object {
        private val LOG = logger<LoadSecretsIntoTerminalAction>()

        /** The terminal tab selected in the Terminal tool window. */
        fun currentWidget(project: Project): TerminalWidget? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
            val content = toolWindow.contentManager.selectedContent ?: return null
            return TerminalToolWindowManager.findWidgetByContent(content)
        }

        /** A new file only the current user can read, in a private directory of the temp folder. */
        fun writeOwnerOnly(text: String, extension: String): Path {
            val posix = runCatching { Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).supportsFileAttributeView("posix") }.getOrDefault(false)
            val file = if (posix) {
                val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                Files.createTempFile("envbridge-", extension, ownerOnly)
            } else {
                Files.createTempFile("envbridge-", extension)
            }
            Files.writeString(file, text)
            return file
        }
    }
}
