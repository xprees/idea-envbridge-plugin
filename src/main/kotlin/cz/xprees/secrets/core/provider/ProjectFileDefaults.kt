package cz.xprees.secrets.core.provider

import java.nio.file.Path

/**
 * What a provider's project file (Infisical: `.infisical.yaml`) says about the project. Contains no secrets.
 * [domain] is only a hint: it selects among the connections the user configured and never makes the plugin send credentials
 * anywhere by itself.
 */
data class ProjectFileDefaults(
    val file: Path,
    val projectId: String?,
    /** Default environment of the file. */
    val environment: String?,
    /** Environment the file maps the current Git branch to, if any. */
    val branchEnvironment: String?,
    val path: String?,
    val domain: String?,
)
