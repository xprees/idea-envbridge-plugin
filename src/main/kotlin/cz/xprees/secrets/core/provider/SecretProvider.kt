package cz.xprees.secrets.core.provider

import cz.xprees.secrets.core.reference.PathGrammar
import cz.xprees.secrets.core.reference.ReferenceGrammar
import cz.xprees.secrets.core.reference.SecretReference
import com.intellij.openapi.extensions.ExtensionPointName
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.model.SecretOrigin
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

    /**
     * Whether a project's environment is something users switch between (Infisical environments, Doppler configs), so a
     * reference that names none reads different secrets as the selection changes. False where the "environment" is fixed
     * account structure (a Vault namespace, 1Password's single one).
     */
    val switchesEnvironments: Boolean get() = true

    /**
     * [switchesEnvironments] for one connection, where it depends on the connection's settings (Vault: only with an
     * environment layout). By default [switchesEnvironments].
     */
    fun switchesEnvironments(connection: SecretConnection): Boolean = switchesEnvironments

    /**
     * Whether [reference] (on [connection]) follows the selected environment: it reads another environment when the selection
     * changes. By default: the connection [switchesEnvironments] and the reference names no environment. A provider whose
     * references may name a fixed place in another way (a Vault path) says so.
     */
    fun followsSelection(connection: SecretConnection, reference: SecretReference): Boolean =
        switchesEnvironments(connection) && reference.environment == null

    /**
     * The defaults [reference] reads with on [connection], given the binding's [defaults]: by default [defaults]. A provider whose
     * references don't all take the selected environment (Vault with an environment layout: a full path reads in the
     * connection's namespace, not in an environment) adjusts them. Must not do I/O.
     */
    fun referenceDefaults(connection: SecretConnection, reference: SecretReference, defaults: SecretReference.Defaults): SecretReference.Defaults =
        defaults

    /**
     * The same folder as [scope], as references written in another form name it (Vault with an environment layout: the mount, the
     * connection's namespace and the full path), or null when there is no other form. Lets moves and renames update both kinds of
     * references. Must not do I/O.
     */
    fun equivalentScope(connection: SecretConnection, scope: SecretScope): SecretScope? = null

    /**
     * The project [reference] reads on [connection], as listings identify projects, or null when it names none (it reads the
     * bound one): by default [SecretReference.projectId]. A provider whose references may name a project in another form (Vault
     * with an environment layout: a full path into a layout project) maps it, so *Only use the bound project* accepts it. Must
     * not do I/O.
     */
    fun referencedProject(connection: SecretConnection, reference: SecretReference): String? = reference.projectId

    /** Environment used when neither the binding nor a project file names one. */
    fun defaultEnvironment(connection: SecretConnection): String = "dev"

    /** Whether this provider's connections can create, change, move and delete secrets (else the write actions are off). */
    val supportsWrites: Boolean get() = true

    /**
     * Where [SecretsClient.createSecret] with [scope] and [key] puts the secret, as (folder path, key): by default the scope's
     * folder and [key]. A provider whose root-level secrets are stored elsewhere (1Password: a new item) says where.
     */
    fun createdSecretLocation(scope: SecretScope, key: String): Pair<String, String> = scope.path to key

    /**
     * What the background keys index (completion, hover, inlays, inspections; see `KeysIndex`) reads for a project bound to
     * [bound] (the binding's project, environment and folder). [environments] are the project's environments as listed (only
     * the bound one when they can't be listed). Called off the EDT; it must not do I/O itself.
     *
     * By default: the root folder of the bound environment and of every environment that isn't a branch of another
     * ([ProviderEnvironment.branchOf]), for "which environments define this key", and the bound environment from `/` with
     * every subfolder. A provider whose environments aren't something users switch between, or whose folders are expensive
     * to walk, narrows it (Vault and 1Password: only the bound folder, see [KeysIndexScope.boundFolder]).
     */
    fun keysIndexScope(bound: SecretScope, environments: List<ProviderEnvironment>): KeysIndexScope = KeysIndexScope(
        environments = (listOf(bound.environment) + environments.filter { it.branchOf == null }.map { it.slug }).distinct(),
        folders = SecretScope(bound.projectId, bound.environment, "/", recursive = true),
    )

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

    /**
     * Forgets what the provider keeps in memory for [connectionId] (every connection when null) beyond the shared cache, such as
     * values read ahead. Called whenever `SecretsService` forgets its cached values (after writes, sign-in and sign-out, Refresh).
     */
    fun forget(connectionId: String?) {}

    /**
     * A note about the last listing of [connection]'s projects for the tool window (secrets left out because their names don't
     * fit the connection's settings, say), or null.
     */
    fun listingNote(connection: SecretConnection): String? = null

    /**
     * Whether [SecretsClient.secretDetails] can say more about [entry] (a folder of [connection] when null) than the listing
     * did, so the tool window asks when the user hovers over it (never while listing). False by default.
     */
    fun hasSecretDetails(connection: SecretConnection, entry: SecretEntry?): Boolean = false

    companion object {
        val EP_NAME: ExtensionPointName<SecretProvider> = ExtensionPointName.create("cz.xprees.secrets.provider")

        fun all(): List<SecretProvider> = EP_NAME.extensionList

        fun byId(id: String): SecretProvider? = all().firstOrNull { it.id == id }

        fun byScheme(scheme: String): SecretProvider? = all().firstOrNull { it.referenceScheme.equals(scheme, ignoreCase = true) }
    }
}

/** What the keys index reads for a binding (see [SecretProvider.keysIndexScope]). Keys only are kept from what it reads. */
data class KeysIndexScope(
    /**
     * Environments whose root folder is read (non-recursively): the keys a reference without a folder reads, and the
     * environments hover and inspections name as defining a key. When the bound environment isn't among them, its root keys
     * are taken from [folders] (none when that starts below `/`).
     */
    val environments: List<String>,
    /** Read with its subfolders, in the bound environment: the folders completion and hover know. Null reads none. */
    val folders: SecretScope?,
) {
    companion object {
        /**
         * Only the bound folder and what is below it, no environment's root: for providers whose environment is fixed account
         * structure and whose folder walk costs one request per secret (Vault, 1Password). Bound to `/`, that is everything.
         */
        fun boundFolder(bound: SecretScope) = KeysIndexScope(environments = emptyList(), folders = bound.copy(recursive = true))
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

/**
 * A field of a sign-in method. [validate] returns why a (non-blank) value is unusable, as a sentence for the connection dialog, or
 * null when it is fine.
 */
data class AuthOption(
    val key: String,
    val label: String,
    val required: Boolean = true,
    val hint: String = "",
    val validate: ((String) -> String?)? = null,
)

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
     * [listSecrets], telling the provider whether the caller needs current values ([fresh], launches) or accepts values as
     * fresh as the shared cache's (browsing, the keys index). A provider that can tell cheaply that a secret didn't change may
     * then answer without reading it again. By default the same as [listSecrets].
     */
    fun listSecrets(scope: SecretScope, fresh: Boolean): List<SecretEntry> = listSecrets(scope)

    /**
     * A hint that [scopes] are about to be read (a launch resolving many references), so a provider that can read several
     * secrets in one request may do so now; the following [listSecrets] calls then use what was read. Never fails: problems
     * surface in the [listSecrets] calls. By default nothing.
     */
    fun prefetch(scopes: Collection<SecretScope>) {}

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
     *
     * With a [newKey] (other than [key]) the secret is also renamed to [newKey], in the same write where the provider allows.
     * Fails with CONFIGURATION, before anything is written, when [newKey] isn't a valid name for the provider, or a secret
     * called [newKey] already exists in that folder, or the provider can't rename.
     */
    fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String? = null, newKey: String? = null): WriteOutcome =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't change secrets")

    /**
     * Moves [secrets] (defined in the folder of [source]) to the folder of [destination], possibly in another environment.
     * The source copy is only removed once the destination has the secret, so a failure never loses one.
     */
    fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't move secrets")

    /**
     * Copies [secrets] (defined in the folder of [source]) to the folder of [destination], leaving them where they are. A
     * secret that already exists at the destination isn't changed (it fails). By default each is created from a fresh read
     * of the source (values as listed, so with references expanded); providers that can copy stored values override this.
     */
    fun copySecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope): CopyResult {
        val keys = secrets.map { it.key }.toSet()
        val fresh = listSecrets(source.copy(recursive = false))
            .filter { it.key in keys && it.path == source.path && it.origin == SecretOrigin.LOCAL }.associateBy { it.key }
        val copied = mutableListOf<String>()
        val awaiting = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        for (key in keys) {
            val entry = fresh[key]
            when {
                entry == null -> failed[key] = "not in ${source.environment}:${source.path} any more"
                entry.valueHidden -> failed[key] = "its value can't be read"
                else -> try {
                    if (createSecret(destination, key, entry.value, entry.comment) == WriteOutcome.APPROVAL_REQUESTED) awaiting += key else copied += key
                } catch (e: SecretProviderException) {
                    failed[key] = e.message ?: e.kind.name
                }
            }
        }
        return CopyResult(copied, awaiting, failed)
    }

    /** Deletes the shared secret [key] from the folder of [scope] (or opens a change request where a policy requires one). */
    fun deleteSecret(scope: SecretScope, key: String): WriteOutcome =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't delete secrets")

    /**
     * Starts the provider's rotation of the secret behind [scope] now (AWS Secrets Manager: `RotateSecret` with the secret's
     * rotation settings), where its [SecretMetadata.rotation] is enabled. An explicit user action, guarded like the writes.
     */
    fun rotateSecret(scope: SecretScope): Unit =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't rotate secrets")

    /**
     * Starts the rotation that manages the secret [key] in the folder of [scope] (Infisical: the secret rotation writing it,
     * where [SecretEntry.metadata] says so). By default the rotation of the secret behind [scope] ([rotateSecret]).
     */
    fun rotateSecret(scope: SecretScope, key: String): Unit = rotateSecret(scope)

    /**
     * Details of the secret [key] in the folder of [scope] (of the folder itself when [key] is null) that listings leave out
     * because they cost a request per secret (Vault: the path's `custom_metadata`; Infisical: the schedule of the rotation
     * managing it). Read on demand only (the tool window's tooltip, the Edit dialog); null when there is nothing more. Asked
     * only where [SecretProvider.hasSecretDetails] says so.
     */
    fun secretDetails(scope: SecretScope, key: String?): SecretMetadata? = null

    /**
     * What the Edit Secret dialog ([key]) or New Secret dialog ([key] null) can change of a secret's metadata in the folder of
     * [scope], with its current value; null when nothing (the default). Read when a dialog opens, off the EDT.
     */
    fun metadataEditor(scope: SecretScope, key: String?): MetadataEditor? = null

    /**
     * Replaces what [MetadataEditor] offered of the metadata of [key] in the folder of [scope] with [metadata] (its other parts
     * are ignored). A write: explicit user action only, CONFIGURATION when this connection can't.
     */
    fun updateMetadata(scope: SecretScope, key: String, metadata: SecretMetadata): WriteOutcome =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't change the metadata of secrets")

    /**
     * [createSecret] with the metadata [MetadataEditor] offered for a new secret. By default the secret is created and then
     * given its metadata ([updateMetadata]) unless the creation waits for approval; a provider that can do both in one write
     * (Infisical: `tagIds`) does.
     */
    fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String, metadata: SecretMetadata?): WriteOutcome {
        val outcome = createSecret(scope, key, value, comment)
        if (metadata != null && outcome == WriteOutcome.APPLIED) updateMetadata(scope, key, metadata)
        return outcome
    }

    /** Creates an environment ([name] shown in the UI, [slug] used in references) in the project [projectId]. */
    fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment =
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "This connection can't create environments")
}

/**
 * What the secret dialogs can change of a secret's metadata ([SecretsClient.metadataEditor]): [current] is what is stored now.
 * [tagChoices]: tags are picked from this list (Infisical: the project's tags, by slug; empty when the project has none); null
 * when not. [freeForm]: a description and `key=value` tags are typed in (Vault: `custom_metadata`). [note]: what the user
 * should know (Vault: it applies to every field of the path), or null.
 */
data class MetadataEditor(
    val current: SecretMetadata,
    val tagChoices: List<String>? = null,
    val freeForm: Boolean = false,
    val note: String? = null,
)

/** What [SecretsClient.moveSecrets] did, per key. */
data class MoveResult(
    /** Now only at the destination. */
    val moved: List<String>,
    /** A change request was opened at the destination; the source copy was kept. */
    val awaitingApproval: List<String> = emptyList(),
    /** Copied to the destination, but the source copy could not be removed (key -> reason): they exist in both places. */
    val notRemoved: Map<String, String> = emptyMap(),
)

/** What [SecretsClient.copySecrets] did, per key. */
data class CopyResult(
    val copied: List<String>,
    /** A change request was opened at the destination. */
    val awaitingApproval: List<String> = emptyList(),
    /** Not copied (key -> reason). */
    val failed: Map<String, String> = emptyMap(),
)

/**
 * [newKey] when it renames [key] (trimmed, not blank, different), else null. Throws CONFIGURATION for a blank name or one
 * with surrounding blanks. Shared by the providers' [SecretsClient.updateSecret].
 */
fun renameTarget(key: String, newKey: String?): String? {
    if (newKey == null || newKey == key) return null
    if (newKey.isBlank()) throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "The new name of $key is empty")
    if (newKey != newKey.trim()) {
        throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "The new name \"$newKey\" starts or ends with a blank")
    }
    return newKey
}

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

open class SecretProviderException(
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
