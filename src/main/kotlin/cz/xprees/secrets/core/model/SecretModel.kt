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

/**
 * An environment of a provider project (Infisical: `dev`, `staging`, `prod`, …). [branchOf] names the environment this one
 * branches off (a Doppler branch or personal config: `dev_feature` of `dev`), null for an environment of its own. [metadata]
 * is what the provider knows about it besides its name (shown in the tool window), or null.
 */
data class ProviderEnvironment(
    val slug: String,
    val name: String,
    val id: String = slug,
    val branchOf: String? = null,
    val metadata: SecretMetadata? = null,
)

/**
 * Descriptive, non-secret facts a provider may know about an environment or the secret behind it (AWS Secrets Manager: the
 * secret's description, tags and rotation schedule), about a secret ([SecretEntry.metadata]: Infisical tags, rotation and
 * reminder; 1Password item tags) or a folder ([SecretFolder.metadata]: a 1Password item's tags). Shown in the tool window;
 * never holds values.
 */
data class SecretMetadata(
    val description: String = "",
    /** Tags: key -> value, or key -> "" for tags that are only a name (Infisical, 1Password). */
    val tags: Map<String, String> = emptyMap(),
    /** Automatic rotation, when the provider has it; null when it doesn't apply. */
    val rotation: RotationInfo? = null,
    /** A reminder to change the secret by hand (Infisical secret reminders), or null. */
    val reminder: SecretReminder? = null,
) {
    /** Whether there is nothing to show. */
    val isEmpty: Boolean get() = description.isBlank() && tags.isEmpty() && rotation == null && reminder == null
}

/**
 * Automatic rotation of a secret: whether it is on, when it last ran and when it runs next (each null when unknown). [managed]:
 * the rotation writes the secret's value (an Infisical secret rotation), so EnvBridge doesn't change it; [name] names that
 * rotation when known.
 */
data class RotationInfo(
    val enabled: Boolean,
    val lastRotated: java.time.Instant? = null,
    val nextRotation: java.time.Instant? = null,
    val managed: Boolean = false,
    val name: String? = null,
)

/** A reminder to rotate a secret by hand every [repeatDays] days, with the [note] sent with it (blank when none). */
data class SecretReminder(val repeatDays: Int, val note: String = "")

/** A project (Infisical: a Secrets Management project) with its environments. */
data class ProviderProject(
    val id: String,
    val name: String,
    val slug: String,
    val environments: List<ProviderEnvironment>,
    val organizationId: String? = null,
)

/** A folder inside an environment. [path] is absolute (`/backend/api`). [metadata]: what the listing says about it, or null. */
data class SecretFolder(val name: String, val path: String, val metadata: SecretMetadata? = null)

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
    /** The provider's ID of the secret (for moving it); empty when unknown. */
    val id: String = "",
    /** What the listing says about the secret besides its value (tags, rotation, reminder), or null. */
    val metadata: SecretMetadata? = null,
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
