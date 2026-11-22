package cz.xprees.secrets.vault

import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.provider.MetadataEditor
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.KeysIndexScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.provider.renameTarget
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import cz.xprees.secrets.core.reference.ReferenceGrammar
import javax.swing.Icon

/**
 * HashiCorp Vault and OpenBao (same API): KV secrets (version 1 and 2), namespaces. Projects are KV mounts, environments are
 * namespaces, folders are secret paths and a secret's fields are its keys. Writes change fields of secrets (KV v2 with
 * check-and-set).
 */
class VaultProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "HashiCorp Vault / OpenBao"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    // A namespace / the single environment is account structure, not a selection users switch
    override val switchesEnvironments: Boolean get() = false

    override val referenceScheme: String = "vault"
    override val referenceGrammar: ReferenceGrammar get() = VaultGrammar
    override val supportsWrites: Boolean get() = true

    // Every Vault is self-hosted or a dedicated HCP cluster: no shared instance to offer
    override val instancePresets: List<Pair<String, String>> = emptyList()

    override val authMethods: List<AuthMethod> = VaultAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String =
        VaultGrammar.normalizeNamespace(connection.option(VaultAuthMethods.OPTION_NAMESPACE))

    override fun createClient(connection: SecretConnection): SecretsClient = VaultClient(connection)

    /** A secret path's `custom_metadata` (KV v2): read when the user hovers over its field or folder, never while listing. */
    override fun hasSecretDetails(connection: SecretConnection, entry: SecretEntry?): Boolean = true

    /**
     * Only the bound secret path and what is below it: walking a whole mount costs a request per path (up to
     * [VaultClient.MAX_RECURSIVE_PATHS]), and a mount's root holds no fields, so no namespace's root is read.
     */
    override fun keysIndexScope(bound: SecretScope, environments: List<ProviderEnvironment>): KeysIndexScope = KeysIndexScope.boundFolder(bound)

    /**
     * The Vault UI's page of the scope (OpenBao's UI uses the same routes). Called on the EDT, so the mount's KV version is only
     * taken from what listing it found ([VaultKvVersions]); unknown counts as KV v2.
     */
    override fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String?, key: String?): String {
        if (scope == null) return "${connection.baseUrl}/ui/"
        val namespace = scope.environment.takeIf { it != VaultApi.ROOT_NAMESPACE }?.let { "?namespace=" + VaultHttp.segment(it) }.orEmpty()
        val path = VaultHttp.path(scope.path)
        val mount = "${connection.baseUrl}/ui/vault/secrets/${VaultHttp.segment(scope.projectId)}"
        // KV v1: /list (the mount's root) and /show/<path>; KV v2: /kv/list and /kv/<path, encoded as one segment>/details
        if (VaultKvVersions.get(connection, scope.environment, scope.projectId) == 1) {
            return if (path.isEmpty()) "$mount/list$namespace" else "$mount/show/$path$namespace"
        }
        val target = if (path.isEmpty()) "list" else "${VaultHttp.segment(path)}/details"
        return "$mount/kv/$target$namespace"
    }

    companion object {
        const val ID = "vault"
    }
}

object VaultAuthMethods {
    const val OIDC = "oidc"
    const val TOKEN = "token"
    const val APPROLE = "approle"
    const val USERPASS = "userpass"
    const val LDAP = "ldap"

    const val OPTION_NAMESPACE = "namespace"
    const val OPTION_NAMESPACES = "namespaces"
    const val OPTION_MOUNTS = "mounts"
    const val OPTION_MOUNT = "authMount"
    const val OPTION_ROLE = "role"
    const val OPTION_CALLBACK_PORT = "callbackPort"
    const val OPTION_ROLE_ID = "roleId"
    const val OPTION_USERNAME = "username"
    const val OPTION_TOKEN = "token"
    const val OPTION_SECRET_ID = "secretId"
    const val OPTION_PASSWORD = "password"

    const val DEFAULT_CALLBACK_PORT = 8250
    const val CALLBACK_PATH = "/oidc/callback"

    /** Options every method has: where to sign in and what to show. */
    private val COMMON = listOf(
        AuthOption(
            OPTION_NAMESPACE,
            SecretsBundle.message("auth.vault.option.namespace.label"),
            required = false,
            hint = SecretsBundle.message("auth.vault.option.namespace.hint"),
        ),
        AuthOption(
            OPTION_NAMESPACES,
            SecretsBundle.message("auth.vault.option.namespaces.label"),
            required = false,
            hint = SecretsBundle.message("auth.vault.option.namespaces.hint"),
        ),
        AuthOption(
            OPTION_MOUNTS,
            SecretsBundle.message("auth.vault.option.mounts.label"),
            required = false,
            hint = SecretsBundle.message("auth.vault.option.mounts.hint"),
        ),
    )

    private fun mount(default: String) =
        AuthOption(OPTION_MOUNT, SecretsBundle.message("auth.vault.option.authMount.label"), required = false, hint = default)

    val ALL = listOf(
        AuthMethod(
            id = OIDC,
            displayName = SecretsBundle.message("auth.vault.oidc.name"),
            description = SecretsBundle.message("auth.vault.oidc.description"),
            options = listOf(
                AuthOption(
                    OPTION_ROLE,
                    SecretsBundle.message("auth.vault.option.role.label"),
                    required = false,
                    hint = SecretsBundle.message("auth.vault.option.role.hint"),
                ),
                mount(OIDC),
                AuthOption(
                    OPTION_CALLBACK_PORT,
                    SecretsBundle.message("auth.vault.option.callbackPort.label"),
                    required = false,
                    hint = DEFAULT_CALLBACK_PORT.toString(),
                ),
            ) + COMMON,
        ),
        AuthMethod(
            id = TOKEN,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink(SecretsBundle.message("auth.link.vault.ui")) { it.trimEnd('/') + "/ui/" },
            ),
            displayName = SecretsBundle.message("auth.vault.token.name"),
            description = SecretsBundle.message("auth.vault.token.description"),
            options = COMMON,
            secretOptions = listOf(AuthOption(
                OPTION_TOKEN,
                SecretsBundle.message("auth.vault.option.token.label"),
                required = false,
                hint = SecretsBundle.message("auth.vault.option.token.hint"),
            )),
        ),
        AuthMethod(
            id = APPROLE,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink.fixed(SecretsBundle.message("auth.link.approle"), "https://developer.hashicorp.com/vault/docs/auth/approle"),
            ),
            displayName = SecretsBundle.message("auth.vault.approle.name"),
            description = SecretsBundle.message("auth.vault.approle.description"),
            options = listOf(AuthOption(OPTION_ROLE_ID, SecretsBundle.message("auth.vault.option.roleId.label")), mount(APPROLE)) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_SECRET_ID, SecretsBundle.message("auth.vault.option.secretId.label"))),
        ),
        AuthMethod(
            id = USERPASS,
            displayName = SecretsBundle.message("auth.vault.userpass.name"),
            description = SecretsBundle.message("auth.vault.userpass.description"),
            options = listOf(
                AuthOption(OPTION_USERNAME, SecretsBundle.message("auth.vault.option.username.label")),
                mount(USERPASS),
            ) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_PASSWORD, SecretsBundle.message("auth.vault.option.password.label"))),
        ),
        AuthMethod(
            id = LDAP,
            displayName = SecretsBundle.message("auth.vault.ldap.name"),
            description = SecretsBundle.message("auth.vault.ldap.description"),
            options = listOf(
                AuthOption(OPTION_USERNAME, SecretsBundle.message("auth.vault.option.username.label")),
                mount(LDAP),
            ) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_PASSWORD, SecretsBundle.message("auth.vault.option.password.label"))),
        ),
    )
}

/**
 * [SecretsClient] for one Vault / OpenBao connection, on KV version 1 and 2 mounts (the version is detected per mount, see
 * [kvVersion]). Writes change one field of a secret: the secret is read, the field changed and the whole secret written back,
 * so every other field keeps its value and type. On KV v2 the write is a new version with check-and-set, so a change made
 * meanwhile is refused instead of lost; KV v1 has no versions, so there the last write wins.
 */
class VaultClient(private val connection: SecretConnection) : SecretsClient {
    private val api = VaultApi(VaultHttp(connection.baseUrl))
    private val auth = VaultAuth(connection, api)

    override fun signIn(interaction: SignInInteraction): String = auth.signIn(interaction)

    override fun signOut() {
        auth.signOut()
        VaultKvVersions.forgetAll(connection)
    }

    override fun hasCredentials(): Boolean = auth.hasCredentials()

    /** The KV mounts, version 1 and 2 (configured, else listed from the server), each with the namespaces as environments. */
    override fun listProjects(): List<ProviderProject> {
        val configured = csv(connection.option(VaultAuthMethods.OPTION_MOUNTS)).map { it.trim('/') }.filter { it.isNotEmpty() }
        val mounts = configured.ifEmpty {
            val listed = auth.withToken { api.kvMounts(it, auth.loginNamespace) }.orEmpty()
            // The listing says each mount's version: the first read needn't ask again
            listed.forEach { VaultKvVersions.put(connection, auth.loginNamespace, it.path, it.version) }
            listed.map { it.path }
        }.ifEmpty {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "No KV secret engine found for '${connection.name}'. If the token can't list them, enter the mounts " +
                    "(for example: secret) in the connection's settings.",
            )
        }
        val environments = namespaces().map { ProviderEnvironment(slug = it, name = it) }
        return mounts.distinct().map { ProviderProject(id = it, name = it, slug = it, environments = environments) }
    }

    /** Child paths of the scope's path: folders and secrets alike (a secret is shown as a folder of its fields). */
    override fun listFolders(scope: SecretScope): List<SecretFolder> {
        val listing = withKv(scope, scope.path) { token, kv -> api.list(token, scope.environment, scope.projectId, scope.path, kv) }
        return (listing.folders + listing.secrets).distinct().sorted().map { name ->
            SecretFolder(name, SecretScope.normalizePath("${scope.path}/$name"))
        }
    }

    /** Fields of the secret at the scope's path; with `recursive`, of every secret below it too. */
    override fun listSecrets(scope: SecretScope): List<SecretEntry> {
        val paths = if (scope.recursive) secretPaths(scope) else listOf(scope.path)
        return paths.flatMap { path ->
            withKv(scope, path) { token, kv -> api.read(token, scope.environment, scope.projectId, path, kv) }.orEmpty().map { (field, value) ->
                SecretEntry(key = field, value = SecretValue(value), path = path, environment = scope.environment)
            }
        }
    }

    /** The scope's path and the secret paths below it, breadth first, at most [MAX_RECURSIVE_PATHS]. */
    private fun secretPaths(scope: SecretScope): List<String> {
        val result = mutableListOf(scope.path)
        val folders = ArrayDeque(listOf(scope.path))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val listing = withKv(scope, folder) { token, kv -> api.list(token, scope.environment, scope.projectId, folder, kv) }
            for (name in listing.secrets) result += SecretScope.normalizePath("$folder/$name")
            for (name in listing.folders) folders += SecretScope.normalizePath("$folder/$name")
            if (result.size > MAX_RECURSIVE_PATHS) {
                throw SecretProviderException(
                    SecretProviderException.Kind.CONFIGURATION,
                    "More than $MAX_RECURSIVE_PATHS secrets below ${scope.projectId}${scope.path}; import a narrower path",
                )
            }
        }
        return result.distinct()
    }

    // ---- KV version --------------------------------------------------------------------------------------------------

    /**
     * Runs [call] with a valid token and the KV version of the scope's mount. A failure forgets the version, so the next call
     * detects it again (a mount may have been upgraded to KV v2 meanwhile).
     */
    private fun <T> withKv(scope: SecretScope, path: String, call: (token: String, kv: Int) -> T): T = auth.withToken { token ->
        val kv = kvVersion(token, scope.environment, scope.projectId, path)
        try {
            call(token, kv)
        } catch (e: SecretProviderException) {
            VaultKvVersions.forget(connection, scope.environment, scope.projectId)
            throw e
        }
    }

    /**
     * The KV version of [mount] in [namespace]: remembered ([VaultKvVersions]), else asked the way the Vault CLI asks
     * ([VaultApi.kvVersion]), else probed at [path] ([VaultApi.probeKvVersion]). When nothing tells, KV v2, as before KV v1
     * was supported, and not remembered (see docs/DESIGN.md, "KV version 1").
     */
    private fun kvVersion(token: String, namespace: String, mount: String, path: String): Int {
        VaultKvVersions.get(connection, namespace, mount)?.let { return it }
        val detected = api.kvVersion(token, namespace, mount) ?: api.probeKvVersion(token, namespace, mount, path) ?: return 2
        VaultKvVersions.put(connection, namespace, mount, detected)
        return detected
    }

    // ---- Writes ------------------------------------------------------------------------------------------------------

    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        val path = secretPath(scope)
        withKv(scope, path) { token, kv ->
            val current = api.readVersioned(token, scope.environment, scope.projectId, path, kv)
            if (current.fields.has(key)) {
                throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(scope)} already has a field $key")
            }
            current.fields.addProperty(key, value.reveal())
            api.write(token, scope.environment, scope.projectId, path, current.fields, current.version, kv)
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Changes the field's value; with a [newKey], the field is renamed in the same write (keeping its place among the fields;
     * on KV v2 with check-and-set), refused before writing when the secret already has a field [newKey].
     */
    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?, newKey: String?): WriteOutcome {
        val path = secretPath(scope)
        val rename = renameTarget(key, newKey)
        withKv(scope, path) { token, kv ->
            val current = api.readVersioned(token, scope.environment, scope.projectId, path, kv)
            if (!current.fields.has(key)) throw missing(scope, key)
            if (rename == null) {
                current.fields.addProperty(key, value.reveal())
                api.write(token, scope.environment, scope.projectId, path, current.fields, current.version, kv)
                return@withKv
            }
            if (current.fields.has(rename)) {
                throw SecretProviderException(
                    SecretProviderException.Kind.CONFIGURATION,
                    "${location(scope)} already has a field $rename; choose another name or delete that one first",
                )
            }
            // A value left as it was keeps its JSON type (a number or an object stays one); a changed one is written as text
            val old = current.fields.get(key)
            val unchanged = VaultApi.textOf(old) == value.reveal()
            val renamed = JsonObject()
            for ((name, element) in current.fields.entrySet()) {
                if (name == key) renamed.add(rename, if (unchanged) element.deepCopy() else JsonPrimitive(value.reveal())) else renamed.add(name, element.deepCopy())
            }
            api.write(token, scope.environment, scope.projectId, path, renamed, current.version, kv)
        }
        return WriteOutcome.APPLIED
    }

    override fun deleteSecret(scope: SecretScope, key: String): WriteOutcome {
        val path = secretPath(scope)
        withKv(scope, path) { token, kv ->
            val current = api.readVersioned(token, scope.environment, scope.projectId, path, kv)
            if (!current.fields.has(key)) throw missing(scope, key)
            current.fields.remove(key)
            removeFields(token, scope, path, current, kv)
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Copies the fields to the destination secret (one write: on KV v2 a new version, values and types as stored), then removes
     * them from the source secret (another write). A field already at the destination stops the move before anything is
     * written unless [overwrite]; a failure to remove the source copy is reported, never a lost field. Source and destination
     * may be on mounts of different KV versions.
     */
    override fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        val sourcePath = secretPath(source)
        val destinationPath = secretPath(destination)
        if (source.projectId == destination.projectId && source.environment == destination.environment && sourcePath == destinationPath) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(source)} is already where the fields are; nothing was moved")
        }
        val keys = secrets.map { it.key }
        val notRemoved = linkedMapOf<String, String>()
        val copied = withKv(destination, destinationPath) { token, toKv ->
            val fromKv = kvVersion(token, source.environment, source.projectId, sourcePath)
            val from = api.readVersioned(token, source.environment, source.projectId, sourcePath, fromKv)
            val to = api.readVersioned(token, destination.environment, destination.projectId, destinationPath, toKv)
            // Check everything before writing anything, so a refused move changes nothing
            val missing = keys.filter { !from.fields.has(it) }
            if (missing.isNotEmpty()) {
                throw SecretProviderException(
                    SecretProviderException.Kind.NOT_FOUND,
                    "${location(source)} has no ${missing.joinToString { "\"$it\"" }} any more; nothing was moved. Refresh and try again",
                )
            }
            val conflicts = keys.filter { to.fields.has(it) }
            if (conflicts.isNotEmpty() && !overwrite) {
                throw SecretProviderException(
                    SecretProviderException.Kind.CONFIGURATION,
                    "${location(destination)} already has ${conflicts.joinToString { "\"$it\"" }}; nothing was moved. Tick Overwrite to replace their values",
                )
            }
            val copied = mutableListOf<String>()
            for (key in keys) {
                to.fields.add(key, from.fields.get(key).deepCopy())
                copied += key
            }
            if (copied.isNotEmpty()) api.write(token, destination.environment, destination.projectId, destinationPath, to.fields, to.version, toKv)
            copied
        }
        if (copied.isEmpty()) return MoveResult(moved = emptyList(), notRemoved = notRemoved)
        try {
            withKv(source, sourcePath) { token, kv ->
                // Read again: the copy is safe at the destination, the source may have changed since
                val from = api.readVersioned(token, source.environment, source.projectId, sourcePath, kv)
                copied.forEach { from.fields.remove(it) }
                removeFields(token, source, sourcePath, from, kv)
            }
        } catch (e: SecretProviderException) {
            copied.forEach { notRemoved[it] = "copied, but not removed from ${location(source)}: ${e.message}" }
            return MoveResult(moved = emptyList(), notRemoved = notRemoved)
        }
        return MoveResult(moved = copied, notRemoved = notRemoved)
    }

    // ---- custom_metadata (KV v2) -------------------------------------------------------------------------------------

    /**
     * The `custom_metadata` of the secret at the scope's path (the folder itself or the path of field [key] alike: metadata
     * belongs to the path): its `description` key as the description, the other keys as tags. One `GET <mount>/metadata/<path>`;
     * null on KV v1 (no metadata) and where there is no secret.
     */
    override fun secretDetails(scope: SecretScope, key: String?): SecretMetadata? {
        if (scope.path == "/") return null
        return withKv(scope, scope.path) { token, kv ->
            if (kv == 1) null else api.customMetadata(token, scope.environment, scope.projectId, scope.path)?.let(::toMetadata)
        }
    }

    /** KV v2: a description and `key=value` tags, saved as the path's `custom_metadata` (shared by all its fields). KV v1: none. */
    override fun metadataEditor(scope: SecretScope, key: String?): MetadataEditor? {
        if (key == null) return null
        val path = secretPath(scope)
        return withKv(scope, path) { token, kv ->
            if (kv == 1) return@withKv null
            val current = api.customMetadata(token, scope.environment, scope.projectId, path).orEmpty()
            MetadataEditor(current = toMetadata(current), freeForm = true, note = "custom_metadata of ${location(scope)}, shared by all its fields")
        }
    }

    /** Replaces the path's `custom_metadata` (`POST <mount>/metadata/<path>`); refused on KV v1, which has none. */
    override fun updateMetadata(scope: SecretScope, key: String, metadata: SecretMetadata): WriteOutcome {
        val path = secretPath(scope)
        withKv(scope, path) { token, kv ->
            if (kv == 1) throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(scope)} is on a KV version 1 engine, which has no metadata")
            api.writeCustomMetadata(token, scope.environment, scope.projectId, path, fromMetadata(metadata))
        }
        return WriteOutcome.APPLIED
    }

    override fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment =
        throw SecretProviderException(
            SecretProviderException.Kind.CONFIGURATION,
            "Namespaces are created by a Vault administrator. To show an existing one, add it to the connection's More namespaces.",
        )

    /**
     * Writes [current] without the removed fields. KV v2: a secret left with none has its latest version deleted (restorable).
     * KV v1: a secret left with none is deleted (it holds nothing any more; KV v1 can't restore it).
     */
    private fun removeFields(token: String, scope: SecretScope, path: String, current: VaultApi.Versioned, kv: Int) {
        if (kv == 1) {
            if (current.fields.size() == 0) api.deleteLatest(token, scope.environment, scope.projectId, path, kv)
            else api.write(token, scope.environment, scope.projectId, path, current.fields, 0, kv)
            return
        }
        // Written with check-and-set either way, so a version someone wrote meanwhile is refused, not deleted; the empty version
        // is then deleted too (only it can be the latest now, apart from a write in the instant between the two calls)
        api.write(token, scope.environment, scope.projectId, path, current.fields, current.version, kv)
        if (current.fields.size() == 0) api.deleteLatest(token, scope.environment, scope.projectId, path, kv)
    }

    /** The secret a write goes to: the scope's path, which must name a secret (KV has none at the mount's root). */
    private fun secretPath(scope: SecretScope): String {
        if (scope.path.split('/').any { it == "." || it == ".." }) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "Write the secret's full path, without . or ..")
        }
        if (scope.path == "/") {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "Choose a secret path below the mount (for example /app/db): fields live in secrets, and ${scope.projectId}/ itself isn't one",
            )
        }
        return scope.path
    }

    private fun location(scope: SecretScope): String =
        scope.projectId + scope.path + if (scope.environment == VaultApi.ROOT_NAMESPACE) "" else " (namespace ${scope.environment})"

    private fun missing(scope: SecretScope, key: String) =
        SecretProviderException(SecretProviderException.Kind.NOT_FOUND, "${location(scope)} has no field $key")

    private fun namespaces(): List<String> {
        val login = auth.loginNamespace
        return (listOf(login) + csv(connection.option(VaultAuthMethods.OPTION_NAMESPACES)).map(VaultGrammar::normalizeNamespace)).distinct()
    }

    companion object {
        const val MAX_RECURSIVE_PATHS = 500

        /** The `custom_metadata` key shown as the description; the other keys are tags. */
        const val DESCRIPTION_KEY = "description"

        /** `custom_metadata` as shown: [DESCRIPTION_KEY] as the description, the other keys as tags (sorted). */
        fun toMetadata(custom: Map<String, String>): SecretMetadata =
            SecretMetadata(description = custom[DESCRIPTION_KEY].orEmpty(), tags = custom.filterKeys { it != DESCRIPTION_KEY }.toSortedMap())

        /** The `custom_metadata` to write for [metadata]: its tags, and its description under [DESCRIPTION_KEY] when not blank. */
        fun fromMetadata(metadata: SecretMetadata): Map<String, String> = buildMap {
            putAll(metadata.tags.filterKeys { it.isNotBlank() && it != DESCRIPTION_KEY })
            if (metadata.description.isNotBlank()) put(DESCRIPTION_KEY, metadata.description)
        }

        private fun csv(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
