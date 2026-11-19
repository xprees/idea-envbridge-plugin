package cz.xprees.secrets.core.model

/**
 * A secret value. [toString] never shows it, so a value that ends up in a log line, an exception message or a debugger
 * watch by accident stays hidden. Call [reveal] where the plain value is really needed.
 */
class SecretValue(private val value: String) {
    fun reveal(): String = value

    val isEmpty: Boolean get() = value.isEmpty()

    override fun toString(): String = MASK

    override fun equals(other: Any?): Boolean = other is SecretValue && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val MASK = "••••••"
    }
}

/** An environment of a provider project (Infisical: `dev`, `staging`, `prod`, …). */
data class ProviderEnvironment(val slug: String, val name: String, val id: String = slug)

/** A project (Infisical: a Secrets Management project) with its environments. */
data class ProviderProject(
    val id: String,
    val name: String,
    val slug: String,
    val environments: List<ProviderEnvironment>,
    val organizationId: String? = null,
)

/** A folder inside an environment. [path] is absolute (`/backend/api`). */
data class SecretFolder(val name: String, val path: String)

/** Where a secret came from, relative to the scope it was listed for. */
enum class SecretOrigin {
    /** Defined in the listed folder (or a subfolder when listing recursively). */
    LOCAL,

    /** Pulled in through a secret import. */
    IMPORTED,

    /** The user's personal override of a shared secret. */
    PERSONAL,
}

data class SecretEntry(
    val key: String,
    val value: SecretValue,
    val comment: String = "",
    /** Folder the secret lives in. */
    val path: String = "/",
    val environment: String = "",
    val origin: SecretOrigin = SecretOrigin.LOCAL,
    /** The caller may see that the secret exists but not read its value ([value] is then empty). */
    val valueHidden: Boolean = false,
)

/**
 * What to read: one folder of one environment of one project, optionally with its subfolders.
 * Secret imports are always included, as the Infisical CLI does.
 */
data class SecretScope(
    val projectId: String,
    val environment: String,
    val path: String = "/",
    val recursive: Boolean = false,
) {
    init {
        require(projectId.isNotBlank()) { "projectId is blank" }
        require(environment.isNotBlank()) { "environment is blank" }
    }

    companion object {
        /** `backend/api/` -> `/backend/api`, blank -> `/`. */
        fun normalizePath(path: String?): String {
            val segments = path.orEmpty().split('/').filter { it.isNotBlank() }
            return "/" + segments.joinToString("/")
        }
    }
}
