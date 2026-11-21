package cz.xprees.secrets.core.provider

import cz.xprees.secrets.core.reference.PathGrammar
import cz.xprees.secrets.core.reference.ReferenceGrammar
import com.intellij.openapi.extensions.ExtensionPointName
import cz.xprees.secrets.core.model.ProviderEnvironment
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

    /** How this provider's references are written (default: `scheme://[env:][folder/…/]KEY`). */
    val referenceGrammar: ReferenceGrammar get() = PathGrammar

    /** Name of the provider's project file read by [readProjectFile] (shown in the UI as the source of "Automatic"), or null. */
    val projectFileName: String? get() = null

    /** Environment used when neither the binding nor a project file names one. */
    fun defaultEnvironment(connection: SecretConnection): String = "dev"

    /** Whether this provider's connections can create, change, move and delete secrets (else the write actions are off). */
    val supportsWrites: Boolean get() = true

    /**
     * Where [SecretsClient.createSecret] with [scope] and [key] puts the secret, as (folder path, key): by default the scope's
     * folder and [key]. A provider whose root-level secrets are stored elsewhere (1Password: a new item) says where.
     */
    fun createdSecretLocation(scope: SecretScope, key: String): Pair<String, String> = scope.path to key

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
    /**
     * Whether this method talks to the connection's base URL. False for methods that go through a local program (a CLI
     * talking to its desktop app): the connection dialog then hides the address, and lists don't show it.
     */
    val usesBaseUrl: Boolean = true,
    /** Where to get the credentials this method asks for (a token, a service account), shown as links in the connection dialog. */
    val setupLinks: List<SetupLink> = emptyList(),
)

/**
 * A link to the provider's page for creating credentials. [url] gets the connection's base URL (self-hosted instances have
 * their own dashboards) and returns the page, or null when there is none for that instance. Opened in the system browser.
 */
class SetupLink(val text: String, val url: (baseUrl: String) -> String?) {
    companion object {
        /** A page that doesn't depend on the instance (the provider's documentation). */
        fun fixed(text: String, url: String) = SetupLink(text) { url }
    }
}

/** Whether [connection]'s sign-in method uses its base URL (unknown providers and methods: yes). */
fun usesBaseUrl(connection: SecretConnection): Boolean =
    SecretProvider.byId(connection.providerId)?.authMethod(connection.authMethodId)?.usesBaseUrl != false

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
    fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String = ""): WriteOutcome =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't create secrets")

    /**
     * Changes the shared secret [key] in the folder of [scope]: its [value] and, when not null, its [comment]. Same rules as
     * [createSecret]: explicit user action only, CONFIGURATION when this connection can't write.
     */
    fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String? = null): WriteOutcome =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't change secrets")

    /**
     * Moves [secrets] (defined in the folder of [source]) to the folder of [destination], possibly in another environment.
     * The source copy is only removed once the destination has the secret, so a failure never loses one.
     */
    fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't move secrets")

    /** Deletes the shared secret [key] from the folder of [scope] (or opens a change request where a policy requires one). */
    fun deleteSecret(scope: SecretScope, key: String): WriteOutcome =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't delete secrets")

    /** Creates an environment ([name] shown in the UI, [slug] used in references) in the project [projectId]. */
    fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't create environments")
}

/** What [SecretsClient.moveSecrets] did, per key. */
data class MoveResult(
    /** Now only at the destination. */
    val moved: List<String>,
    /** A change request was opened at the destination; the source copy was kept. */
    val awaitingApproval: List<String> = emptyList(),
    /** Copied to the destination, but the source copy could not be removed (key -> reason): they exist in both places. */
    val notRemoved: Map<String, String> = emptyMap(),
)

/** What a write ([SecretsClient.createSecret], [SecretsClient.updateSecret]) did. */
enum class WriteOutcome {
    APPLIED,

    /** The folder has a change policy: a change request was opened and the change applies once it is approved. */
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
