package cz.xprees.secrets.vault

import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.reference.ReferenceGrammar
import javax.swing.Icon

/**
 * HashiCorp Vault and OpenBao (same API): KV v2 secrets, namespaces. Projects are KV v2 mounts, environments are namespaces,
 * folders are secret paths and a secret's fields are its keys. Writes change fields of KV v2 secrets with check-and-set.
 */
class VaultProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "HashiCorp Vault / OpenBao"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    override val referenceScheme: String = "vault"
    override val referenceGrammar: ReferenceGrammar get() = VaultGrammar
    override val supportsWrites: Boolean get() = true

    // Every Vault is self-hosted or a dedicated HCP cluster: no shared instance to offer
    override val instancePresets: List<Pair<String, String>> = emptyList()

    override val authMethods: List<AuthMethod> = VaultAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String =
        VaultGrammar.normalizeNamespace(connection.option(VaultAuthMethods.OPTION_NAMESPACE))

    override fun createClient(connection: SecretConnection): SecretsClient = VaultClient(connection)

    override fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String?, key: String?): String {
        // The Vault UI: /ui/vault/secrets/<mount>/kv/<path>/details (?namespace=); OpenBao's UI uses the same routes
        if (scope == null) return "${connection.baseUrl}/ui/"
        val namespace = scope.environment.takeIf { it != VaultApi.ROOT_NAMESPACE }?.let { "?namespace=" + VaultHttp.segment(it) }.orEmpty()
        val path = VaultHttp.path(scope.path)
        val target = if (path.isEmpty()) "list" else "${VaultHttp.segment(path)}/details"
        return "${connection.baseUrl}/ui/vault/secrets/${VaultHttp.segment(scope.projectId)}/kv/$target$namespace"
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
 * [SecretsClient] for one Vault / OpenBao connection. Writes change one field of a KV v2 secret: the secret is read, the field
 * changed and the whole secret written as a new version with check-and-set, so a change made meanwhile is refused instead of
 * lost, and every other field keeps its value and type.
 */
class VaultClient(private val connection: SecretConnection) : SecretsClient {
    private val api = VaultApi(VaultHttp(connection.baseUrl))
    private val auth = VaultAuth(connection, api)

    override fun signIn(interaction: SignInInteraction): String = auth.signIn(interaction)

    override fun signOut() = auth.signOut()

    override fun hasCredentials(): Boolean = auth.hasCredentials()

    /** The KV v2 mounts (configured, else listed from the server), each with the namespaces as environments. */
    override fun listProjects(): List<ProviderProject> {
        val configured = csv(connection.option(VaultAuthMethods.OPTION_MOUNTS)).map { it.trim('/') }.filter { it.isNotEmpty() }
        val mounts = configured.ifEmpty {
            auth.withToken { api.kvMounts(it, auth.loginNamespace) }?.map { it.path }.orEmpty()
        }.ifEmpty {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "No KV v2 secret engine found for '${connection.name}'. If the token can't list them, enter the mounts " +
                    "(for example: secret) in the connection's settings.",
            )
        }
        val environments = namespaces().map { ProviderEnvironment(slug = it, name = it) }
        return mounts.distinct().map { ProviderProject(id = it, name = it, slug = it, environments = environments) }
    }

    /** Child paths of the scope's path: folders and secrets alike (a secret is shown as a folder of its fields). */
    override fun listFolders(scope: SecretScope): List<SecretFolder> {
        val listing = auth.withToken { api.list(it, scope.environment, scope.projectId, scope.path) }
        return (listing.folders + listing.secrets).distinct().sorted().map { name ->
            SecretFolder(name, SecretScope.normalizePath("${scope.path}/$name"))
        }
    }

    /** Fields of the secret at the scope's path; with `recursive`, of every secret below it too. */
    override fun listSecrets(scope: SecretScope): List<SecretEntry> {
        val paths = if (scope.recursive) secretPaths(scope) else listOf(scope.path)
        return paths.flatMap { path ->
            auth.withToken { api.read(it, scope.environment, scope.projectId, path) }.orEmpty().map { (field, value) ->
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
            val listing = auth.withToken { api.list(it, scope.environment, scope.projectId, folder) }
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

    // ---- Writes ------------------------------------------------------------------------------------------------------

    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        val path = secretPath(scope)
        auth.withToken { token ->
            val current = api.readVersioned(token, scope.environment, scope.projectId, path)
            if (current.fields.has(key)) {
                throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(scope)} already has a field $key")
            }
            current.fields.addProperty(key, value.reveal())
            api.write(token, scope.environment, scope.projectId, path, current.fields, current.version)
        }
        return WriteOutcome.APPLIED
    }

    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?): WriteOutcome {
        val path = secretPath(scope)
        auth.withToken { token ->
            val current = api.readVersioned(token, scope.environment, scope.projectId, path)
            if (!current.fields.has(key)) throw missing(scope, key)
            current.fields.addProperty(key, value.reveal())
            api.write(token, scope.environment, scope.projectId, path, current.fields, current.version)
        }
        return WriteOutcome.APPLIED
    }

    override fun deleteSecret(scope: SecretScope, key: String): WriteOutcome {
        val path = secretPath(scope)
        auth.withToken { token ->
            val current = api.readVersioned(token, scope.environment, scope.projectId, path)
            if (!current.fields.has(key)) throw missing(scope, key)
            current.fields.remove(key)
            removeFields(token, scope, path, current)
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Copies the fields to the destination secret (one new version, values and types as stored), then removes them from the
     * source secret (another new version). A field already at the destination stops the move before anything is written
     * unless [overwrite]; a failure to remove the source copy is reported, never a lost field.
     */
    override fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        val sourcePath = secretPath(source)
        val destinationPath = secretPath(destination)
        if (source.projectId == destination.projectId && source.environment == destination.environment && sourcePath == destinationPath) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(source)} is already where the fields are; nothing was moved")
        }
        val keys = secrets.map { it.key }
        val notRemoved = linkedMapOf<String, String>()
        val copied = auth.withToken { token ->
            val from = api.readVersioned(token, source.environment, source.projectId, sourcePath)
            val to = api.readVersioned(token, destination.environment, destination.projectId, destinationPath)
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
            if (copied.isNotEmpty()) api.write(token, destination.environment, destination.projectId, destinationPath, to.fields, to.version)
            copied
        }
        if (copied.isEmpty()) return MoveResult(moved = emptyList(), notRemoved = notRemoved)
        try {
            auth.withToken { token ->
                // Read again: the copy is safe at the destination, the source may have changed since
                val from = api.readVersioned(token, source.environment, source.projectId, sourcePath)
                copied.forEach { from.fields.remove(it) }
                removeFields(token, source, sourcePath, from)
            }
        } catch (e: SecretProviderException) {
            copied.forEach { notRemoved[it] = "copied, but not removed from ${location(source)}: ${e.message}" }
            return MoveResult(moved = emptyList(), notRemoved = notRemoved)
        }
        return MoveResult(moved = copied, notRemoved = notRemoved)
    }

    override fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment =
        throw SecretProviderException(
            SecretProviderException.Kind.CONFIGURATION,
            "Namespaces are created by a Vault administrator. To show an existing one, add it to the connection's More namespaces.",
        )

    /** Writes [current] without the removed fields; a secret left with none has its latest version deleted (restorable). */
    private fun removeFields(token: String, scope: SecretScope, path: String, current: VaultApi.Versioned) {
        // Written with check-and-set either way, so a version someone wrote meanwhile is refused, not deleted; the empty version
        // is then deleted too (only it can be the latest now, apart from a write in the instant between the two calls)
        api.write(token, scope.environment, scope.projectId, path, current.fields, current.version)
        if (current.fields.size() == 0) api.deleteLatest(token, scope.environment, scope.projectId, path)
    }

    /** The secret a write goes to: the scope's path, which must name a secret (KV v2 has none at the mount's root). */
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

        private fun csv(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
