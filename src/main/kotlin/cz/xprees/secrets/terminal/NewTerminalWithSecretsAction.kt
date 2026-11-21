package cz.xprees.secrets.terminal

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.EnvResolution
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.run.SecretsNotifications
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens a terminal tab whose shell (any shell: PowerShell, cmd, bash, zsh, …) starts with secrets in its environment, without
 * turning the terminal integration on for every tab. A running shell's environment can't be changed from outside, so this is
 * how a shell gets secrets: they are fetched first, then handed to the next tab the terminal starts.
 *
 * What the tab gets: the project's terminal references (Settings › Tools › EnvBridge › This Project) or, when none are set,
 * every secret of the bound folder.
 */
class NewTerminalWithSecretsAction : DumbAwareAction(AllIcons.Nodes.Padlock) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        TerminalSecrets.fetch(project) { resolved ->
            TerminalSecretsHandoff.offer(resolved.environment)
            if (!openTab(project)) {
                // Nothing will take the offer: withdraw it, so a tab opened later doesn't get the secrets
                TerminalSecretsHandoff.take()
                SecretsNotifications.error(project, SecretsBundle.message("terminal.open.failed"), "")
                return@fetch
            }
            resolved.summary()?.let { SecretsNotifications.info(project, SecretsBundle.message("terminal.opened"), it) }
        }
    }

    /** Opens a terminal tab; false when none could be opened. */
    private fun openTab(project: Project): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
        val newTab = ActionManager.getInstance().getAction(NEW_TAB_ACTION_ID)
        if (toolWindow != null && newTab != null) {
            toolWindow.activate {
                ActionManager.getInstance().tryToExecute(newTab, null, toolWindow.component, ActionPlaceName, true)
            }
            return true
        } else {
            // What the terminal's own New Tab action does (it replaces the deprecated TerminalToolWindowManager.createNewSession()).
            // The shell still starts through SecretsTerminalCustomizer, which takes the offer.
            try {
                @Suppress("UnstableApiUsage")
                TerminalToolWindowTabsManager.getInstance(project).createTabBuilder().requestFocus(true).createTab()
                return true
            } catch (e: NoClassDefFoundError) {
                // The terminal's frontend module isn't loaded here (e.g. the backend of a remote development session)
                LOG.warn("Could not open a terminal tab", e)
                return false
            }
        }
    }

    private companion object {
        val LOG = logger<NewTerminalWithSecretsAction>()
        const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
        const val NEW_TAB_ACTION_ID = "Terminal.NewTab"
        const val ActionPlaceName = "EnvBridge.NewTerminalWithSecrets"
    }
}

/**
 * Hands resolved variables to the next terminal tab that starts (see [SecretsTerminalCustomizer]). An offer is taken once and
 * expires quickly, so a tab opened much later never gets it.
 */
object TerminalSecretsHandoff {
    private const val TTL_MS = 30_000L

    private class Offer(val environment: Map<String, String>, val expiresAt: Long)

    private val pending = AtomicReference<Offer?>(null)

    fun offer(environment: Map<String, String>) {
        pending.set(Offer(environment, System.currentTimeMillis() + TTL_MS))
    }

    /** The offered variables, once; null when nothing is offered or the offer expired. */
    fun take(): Map<String, String>? = pending.getAndSet(null)?.takeIf { it.expiresAt >= System.currentTimeMillis() }?.environment
}

/** Fetching what terminals get, shared by the terminal actions. */
object TerminalSecrets {
    /**
     * Resolves the project's terminal references (or, when none are set, every secret of the bound folder) in the background,
     * asks before production-like environments, reports warnings and hands the result to [then] on the EDT.
     */
    fun fetch(project: Project, then: (EnvResolution.Result) -> Unit) {
        val configured = SecretsTerminalCustomizer.parse(ProjectSecretsSettings.getInstance(project).state.terminalReferences.orEmpty())
        val references = configured.ifEmpty { SecretsTerminalCustomizer.parse(ProjectSecretsSettings.DEFAULT_TERMINAL_REFERENCES) }
        val resolver = LaunchSecretResolver.getInstance(project)
        val plan = resolver.plan(references)

        object : Task.Backgroundable(project, SecretsBundle.message("terminal.fetching"), true) {
            private var result: EnvResolution.Result? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = resolver.resolve(plan)
            }

            override fun onSuccess() {
                val resolved = result ?: return
                val guarded = resolved.environments.filter { ProdGuard.needsConfirmation(project, it) }
                if (guarded.isNotEmpty() && Messages.showYesNoDialog(
                        project,
                        SecretsBundle.message("prod.guard.message", SecretsBundle.message("terminal.tab.name"), guarded.joinToString()),
                        SecretsBundle.message("prod.guard.title"),
                        SecretsBundle.message("prod.guard.run"),
                        Messages.getCancelButton(),
                        Messages.getWarningIcon(),
                    ) != Messages.YES
                ) return
                if (resolved.warnings.isNotEmpty()) {
                    SecretsNotifications.launchWarnings(project, SecretsBundle.message("terminal.tab.name"), resolved.warnings)
                }
                then(resolved)
            }

            override fun onThrowable(error: Throwable) {
                if ((error as? SecretProviderException)?.kind == SecretProviderException.Kind.CANCELLED) return
                SecretsNotifications.error(project, SecretsBundle.message("terminal.failed"), error.message.orEmpty())
            }
        }.queue()
    }
}
