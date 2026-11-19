package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.settings.SecretsAppSettings

/** Environments that need a confirmation before secrets from them are used (default: `prod*, production*, live*`). */
object ProdGuard {
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
