package cz.xprees.secrets.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.run.SecretsNotifications
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer

/**
 * Puts secrets into the environment of new terminal tabs: a tab opened with *New Terminal Tab with Secrets* gets what that
 * action fetched; other tabs get the project's terminal references when enabled for the project (Settings › Tools ›
 * EnvBridge › This Project › Terminal). Fetched when the tab opens; a failure is reported and the tab opens without them.
 */
class SecretsTerminalCustomizer : ShellExecOptionsCustomizer {

    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        TerminalSecretsHandoff.take()?.let { offered ->
            offered.forEach(shellExecOptions::setEnvironmentVariable)
            return
        }
        val state = ProjectSecretsSettings.getInstance(project).state
        if (!state.terminalEnabled) return
        val references = parse(state.terminalReferences.orEmpty())
        if (references.isEmpty()) return
        val resolver = LaunchSecretResolver.getInstance(project)
        try {
            // Never block the UI thread on the network: there, only cached values can be used
            val onEdt = ApplicationManager.getApplication().isDispatchThread
            val result = resolver.resolve(resolver.plan(references), fresh = !onEdt)
            // Scope-import labels are not part of the result; everything in it goes to the shell
            result.environment.forEach(shellExecOptions::setEnvironmentVariable)
        } catch (e: SecretProviderException) {
            SecretsNotifications.error(project, SecretsBundle.message("terminal.failed"), e.message.orEmpty())
        }
    }

    companion object {
        /**
         * One entry per line: `NAME=infisical://…` for a single variable, or a bare scope import (a reference whose last
         * segment is `*`, or `**` with subfolders). Blank lines and `#` comments are ignored.
         */
        fun parse(text: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.forEachIndexed { index, line ->
                val schemeAt = line.indexOf("://")
                val eq = line.indexOf('=')
                if (eq in 1 until schemeAt) {
                    result[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                } else {
                    result["__SECRETS_TERMINAL_SCOPE_$index"] = line
                }
            }
            return result
        }
    }
}
