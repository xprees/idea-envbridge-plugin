package cz.xprees.secrets.vault

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
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.reference.ReferenceGrammar
import javax.swing.Icon

/**
 * HashiCorp Vault and OpenBao (same API): KV v2 secrets, namespaces. Projects are KV v2 mounts, environments are namespaces,
 * folders are secret paths and a secret's fields are its keys. Reading only in this version.
 */
class VaultProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "HashiCorp Vault / OpenBao"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    override val referenceScheme: String = "vault"
    override val referenceGrammar: ReferenceGrammar get() = VaultGrammar
    override val supportsWrites: Boolean get() = false

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
        AuthOption(OPTION_NAMESPACE, "Namespace:", required = false, hint = "root (Vault Enterprise, HCP Vault, OpenBao)"),
        AuthOption(OPTION_NAMESPACES, "More namespaces:", required = false, hint = "optional, comma-separated: team-a, team-a/dev"),
        AuthOption(OPTION_MOUNTS, "KV v2 mounts:", required = false, hint = "listed from the server; or comma-separated: secret, kv"),
    )

    private fun mount(default: String) = AuthOption(OPTION_MOUNT, "Auth mount path:", required = false, hint = default)

    val ALL = listOf(
        AuthMethod(
            id = OIDC,
            displayName = "OIDC (browser)",
            description = "Sign in with your identity provider in the system browser, as `vault login -method=oidc` does. The role must " +
                "allow the redirect URI http://localhost:<port>/oidc/callback (the Vault CLI's).",
            options = listOf(
                AuthOption(OPTION_ROLE, "Role:", required = false, hint = "the mount's default role"),
                mount(OIDC),
                AuthOption(OPTION_CALLBACK_PORT, "Redirect port:", required = false, hint = DEFAULT_CALLBACK_PORT.toString()),
            ) + COMMON,
        ),
        AuthMethod(
            id = TOKEN,
            displayName = "Token",
            description = "A Vault token. Left empty, the Vault CLI's token (~/.vault-token, from `vault login`) is used.",
            options = COMMON,
            secretOptions = listOf(AuthOption(OPTION_TOKEN, "Token:", required = false, hint = "~/.vault-token")),
        ),
        AuthMethod(
            id = APPROLE,
            displayName = "AppRole",
            description = "A role ID and secret ID, for machines and dedicated identities.",
            options = listOf(AuthOption(OPTION_ROLE_ID, "Role ID:"), mount(APPROLE)) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_SECRET_ID, "Secret ID:")),
        ),
        AuthMethod(
            id = USERPASS,
            displayName = "Username and password",
            description = "The userpass auth method.",
            options = listOf(AuthOption(OPTION_USERNAME, "Username:"), mount(USERPASS)) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_PASSWORD, "Password:")),
        ),
        AuthMethod(
            id = LDAP,
            displayName = "LDAP",
            description = "Your directory username and password (the LDAP auth method).",
            options = listOf(AuthOption(OPTION_USERNAME, "Username:"), mount(LDAP)) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_PASSWORD, "Password:")),
        ),
    )
}

/** [SecretsClient] for one Vault / OpenBao connection (reading only). */
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

    private fun namespaces(): List<String> {
        val login = auth.loginNamespace
        return (listOf(login) + csv(connection.option(VaultAuthMethods.OPTION_NAMESPACES)).map(VaultGrammar::normalizeNamespace)).distinct()
    }

    companion object {
        const val MAX_RECURSIVE_PATHS = 500

        private fun csv(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
