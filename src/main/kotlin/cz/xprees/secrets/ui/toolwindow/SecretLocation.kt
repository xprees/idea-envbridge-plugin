package cz.xprees.secrets.ui.toolwindow

import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.SecretBindingService

/** Where a secret lives (or a folder, without [key]): what the Secrets tool window selects. IDs and slugs only, no values. */
data class SecretLocation(
    val connectionId: String,
    val projectId: String,
    val environment: String,
    val path: String,
    val key: String?,
) {
    /** A compact string form, e.g. for an inlay's click payload. */
    fun encode(): String = listOf(connectionId, projectId, environment, path, key.orEmpty()).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001f"

        fun decode(text: String): SecretLocation? {
            val parts = text.split(SEPARATOR)
            if (parts.size != 5 || parts.take(4).any(String::isEmpty)) return null
            return SecretLocation(parts[0], parts[1], parts[2], parts[3], parts[4].ifEmpty { null })
        }

        /**
         * The location of [key] as the editor sees it: the project binding, with whatever [reference] names explicitly (another
         * environment, folder or project). Null when nothing is bound or the reference names another connection.
         */
        fun of(project: Project, key: String?, reference: SecretReference? = null): SecretLocation? {
            val provider = reference?.let { SecretProvider.byScheme(it.scheme) } ?: SecretProvider.all().firstOrNull() ?: return null
            if (reference?.connectionId != null) return null
            val binding = SecretBindingService.getInstance(project).bindingOrNull(provider) ?: return null
            val projectId = reference?.projectId ?: binding.projectId ?: return null
            return SecretLocation(
                connectionId = binding.connection.id,
                projectId = projectId,
                environment = reference?.environment ?: binding.environment,
                path = reference?.path ?: binding.path,
                key = reference?.key ?: key,
            )
        }
    }
}
