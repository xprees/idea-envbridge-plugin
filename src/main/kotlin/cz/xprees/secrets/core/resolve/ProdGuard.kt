package cz.xprees.secrets.core.resolve

import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.settings.ProjectSecretsSettings

import cz.xprees.secrets.core.settings.SecretsAppSettings

/**
 * Production environments: they are marked (status bar, environment pickers) and, unless turned off in the settings, ask for a
 * confirmation before secrets from them reach a process or a terminal, and before secrets in them are created or changed.
 * A project's own list of production environments (picked in its settings) wins over the name pattern (default
 * `prod*, production*, live*`), for teams whose environments are named differently.
 */
object ProdGuard {
    /** Whether [environment] is a production environment for [project]. */
    fun isProduction(project: Project?, environment: String): Boolean {
        val picked = project?.takeIf { !it.isDisposed }?.let { ProjectSecretsSettings.getInstance(it).productionEnvironments() }.orEmpty()
        return if (picked.isNotEmpty()) environment in picked else matches(environment)
    }

    /** Whether using or changing secrets of [environment] needs a confirmation (production, and the guard is on). */
    fun needsConfirmation(project: Project?, environment: String): Boolean =
        SecretsAppSettings.getInstance().state.prodGuardEnabled && isProduction(project, environment)

    fun matches(environment: String, pattern: String = SecretsAppSettings.getInstance().state.prodGuardPattern.orEmpty()): Boolean =
        pattern.split(',').map(String::trim).filter(String::isNotEmpty).any { glob -> globToRegex(glob).matches(environment) }

    private fun globToRegex(glob: String): Regex = Regex(
        buildString {
            for (c in glob) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        },
        RegexOption.IGNORE_CASE,
    )
}
