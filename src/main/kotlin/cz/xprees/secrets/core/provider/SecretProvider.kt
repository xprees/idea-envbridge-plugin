package cz.xprees.secrets.core.provider

import com.intellij.openapi.extensions.ExtensionPointName
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import javax.swing.Icon

/**
 * A secret manager the plugin can read from. Infisical is the first one; everything outside a provider (run configuration
 * injection, references, the tool window, caching, credential storage) is shared by all of them.
 */
interface SecretProvider {
    /** Stable identifier, stored in settings (`infisical`). */
    val id: String

    val displayName: String

    val icon: Icon

    /** Scheme of secret references for this provider: `infisical` for `infisical://KEY`. */
    val referenceScheme: String

    /** Instances offered when a connection is created (name -> base URL); a custom URL is always possible. */
    val instancePresets: List<Pair<String, String>>

    val authMethods: List<AuthMethod>

    fun authMethod(id: String): AuthMethod? = authMethods.firstOrNull { it.id == id }

    /** A client for [connection]. Cheap: callers create one per operation. */
    fun createClient(connection: SecretConnection): SecretsClient

    /**
     * Reads the provider's project file for [projectDir] (and its parents, as the provider's CLI does), or null when there
     * is none. Called off the EDT.
     */
    fun readProjectFile(projectDir: java.nio.file.Path): ProjectFileDefaults? = null

    /**
     * Dashboard address of a project / environment / folder (and secret [key], if given) for "Open in dashboard", or null when
     * unknown. [organizationId] is the organization the project belongs to, when known.
     */
    fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String? = null, key: String? = null): String? = null

    companion object {
        val EP_NAME: ExtensionPointName<SecretProvider> = ExtensionPointName.create("cz.xprees.secrets.provider")

        fun all(): List<SecretProvider> = EP_NAME.extensionList

        fun byId(id: String): SecretProvider? = all().firstOrNull { it.id == id }

        fun byScheme(scheme: String): SecretProvider? = all().firstOrNull { it.referenceScheme.equals(scheme, ignoreCase = true) }
    }
}

/** How a connection signs in. The provider implements the flow; the UI only shows [displayName] and [description]. */
data class AuthMethod(
    val id: String,
    val displayName: String,
    val description: String,
    /** Option keys the user fills in for this method (shown in the connection dialog), see [AuthOption]. */
    val options: List<AuthOption> = emptyList(),
    /** Secret fields (stored in PasswordSafe, never in settings). */
    val secretOptions: List<AuthOption> = emptyList(),
)

data class AuthOption(val key: String, val label: String, val required: Boolean = true, val hint: String = "")

/**
 * Read access to one connection. All calls block and do network I/O: never call them on the EDT. They throw
 * [SecretProviderException] for every expected failure.
 */
interface SecretsClient {
    /** Signs in interactively if needed (may open the system browser). Returns a short description of the account. */
    fun signIn(interaction: SignInInteraction): String

    /** Ends the session: forgets local credentials and, where the provider supports it, revokes them on the server. */
    fun signOut()

    /** Whether credentials are stored (they may still be expired; calls then refresh or fail with AUTH_REQUIRED). */
    fun hasCredentials(): Boolean

    fun listProjects(): List<ProviderProject>

    fun listFolders(scope: SecretScope): List<SecretFolder>

    /** Secrets of [scope] with imports applied (local secrets win over imported ones) and references expanded. */
    fun listSecrets(scope: SecretScope): List<SecretEntry>

    /**
     * Creates the shared secret [key] in the folder of [scope]. The only write the plugin does, always on an explicit user
     * action. Fails with [SecretProviderException.Kind.CONFIGURATION] when this connection can't write (e.g. a read-only
     * token) or the secret already exists.
     */
    fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String = ""): CreateOutcome =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't create secrets")
}

/** What [SecretsClient.createSecret] did. */
enum class CreateOutcome {
    CREATED,

    /** The folder has a change policy: a change request was opened and the secret exists once it is approved. */
    APPROVAL_REQUESTED,
}

/** What a sign-in flow may need from the UI. */
interface SignInInteraction {
    /** Opens [url] in the system browser. */
    fun openBrowser(url: String)

    /** Lets the flow wait cooperatively; it should stop when this returns true. */
    fun isCancelled(): Boolean

    /** Status text for a progress indicator. */
    fun status(text: String) {}
}

class SecretProviderException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** Not signed in, or the session expired and could not be renewed. */
        AUTH_REQUIRED,

        /** Signed in, but not allowed to read this. */
        FORBIDDEN,
        NOT_FOUND,

        /** The instance could not be reached. */
        NETWORK,

        /** The instance answered with an error. */
        SERVER,

        /** Wrong input or configuration (malformed reference, missing project, …). */
        CONFIGURATION,
        CANCELLED,
    }
}
