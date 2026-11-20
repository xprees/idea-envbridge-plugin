package cz.xprees.secrets.infisical

import cz.xprees.secrets.core.provider.MoveResult

import cz.xprees.secrets.core.model.ProviderEnvironment

import cz.xprees.secrets.core.provider.SecretProviderException

import cz.xprees.secrets.core.model.SecretValue

import cz.xprees.secrets.core.provider.WriteOutcome

import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.ProjectFileDefaults
import cz.xprees.secrets.core.resolve.GitHead
import cz.xprees.secrets.infisical.config.InfisicalProjectConfig
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.infisical.api.InfisicalApi
import cz.xprees.secrets.infisical.api.InfisicalHttp
import cz.xprees.secrets.infisical.auth.InfisicalAuth
import javax.swing.Icon

/** Infisical (US Cloud, EU Cloud, dedicated and self-hosted instances). */
class InfisicalProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "Infisical"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    override val referenceScheme: String = "infisical"
    override val projectFileName: String = ".infisical.yaml"

    override val instancePresets: List<Pair<String, String>> = listOf(
        "Infisical Cloud (US)" to US_CLOUD,
        "Infisical Cloud (EU)" to EU_CLOUD,
    )

    override val authMethods: List<AuthMethod> = InfisicalAuthMethods.ALL

    override fun createClient(connection: SecretConnection): SecretsClient = InfisicalClient(connection)

    override fun readProjectFile(projectDir: java.nio.file.Path): ProjectFileDefaults? =
        InfisicalProjectConfig.find(projectDir)?.toDefaults(GitHead.currentBranch(projectDir))

    override fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String?, key: String?): String {
        // The web app's route (frontend/src/routes): /organizations/<org>/projects/secret-management/<project>/secrets/<env>,
        // with ?secretPath=<folder>&search=<key>. Without the organization only the instance's start page is reliable.
        if (scope == null || organizationId.isNullOrBlank()) return connection.baseUrl
        fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        val query = buildList {
            if (scope.path != "/") add("secretPath=${enc(scope.path)}")
            key?.let { add("search=${enc(it)}") }
        }
        return "${connection.baseUrl}/organizations/${enc(organizationId)}/projects/secret-management/${enc(scope.projectId)}/secrets/${enc(scope.environment)}" +
            if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }

    companion object {
        const val ID = "infisical"
        const val US_CLOUD = "https://app.infisical.com"
        const val EU_CLOUD = "https://eu.infisical.com"
    }
}

object InfisicalAuthMethods {
    const val BROWSER = "browser"
    const val OAUTH = "oauth"
    const val MACHINE_IDENTITY = "machine-identity"

    const val OPTION_CLIENT_ID = "clientId"
    const val OPTION_CLIENT_SECRET = "clientSecret"
    const val OPTION_CALLBACK_PORT = "callbackPort"
    const val OPTION_ORGANIZATION_SLUG = "organizationSlug"

    const val DEFAULT_CALLBACK_PORT = 51327
    const val OAUTH_SCOPE = "secrets:read"

    val ALL = listOf(
        AuthMethod(
            id = BROWSER,
            displayName = "Browser sign-in",
            description = "Sign in with your Infisical account in the system browser, as the Infisical CLI does. No setup needed; " +
                "the session has your permissions.",
        ),
        AuthMethod(
            id = OAUTH,
            displayName = "OAuth application",
            description = "Least privilege: a read-only (secrets:read) token from an OAuth application your organization registered, " +
                "approved in the browser. Register the redirect URI http://127.0.0.1:<port>/callback.",
            options = listOf(
                AuthOption(OPTION_CLIENT_ID, "Client ID:"),
                AuthOption(OPTION_CALLBACK_PORT, "Redirect port:", required = false, hint = DEFAULT_CALLBACK_PORT.toString()),
            ),
            secretOptions = listOf(AuthOption(OPTION_CLIENT_SECRET, "Client secret:")),
        ),
        AuthMethod(
            id = MACHINE_IDENTITY,
            displayName = "Machine identity (Universal Auth)",
            description = "A machine identity's client ID and secret, for setups without a browser or with dedicated identities.",
            options = listOf(
                AuthOption(OPTION_CLIENT_ID, "Client ID:"),
                AuthOption(OPTION_ORGANIZATION_SLUG, "Organization slug:", required = false, hint = "optional"),
            ),
            secretOptions = listOf(AuthOption(OPTION_CLIENT_SECRET, "Client secret:")),
        ),
    )
}

/** [SecretsClient] for one Infisical connection. */
class InfisicalClient(private val connection: SecretConnection) : SecretsClient {
    private val api = InfisicalApi(InfisicalHttp(connection.baseUrl))
    private val auth = InfisicalAuth(connection, api)

    override fun signIn(interaction: SignInInteraction): String = auth.signIn(interaction)

    override fun signOut() = auth.signOut()

    override fun hasCredentials(): Boolean = auth.hasCredentials()

    override fun listProjects(): List<ProviderProject> = auth.withToken(api::listProjects).sortedBy { it.name.lowercase() }

    override fun listFolders(scope: SecretScope): List<SecretFolder> = auth.withToken { api.listFolders(it, scope) }

    override fun listSecrets(scope: SecretScope): List<SecretEntry> = auth.withToken { api.listSecrets(it, scope) }

    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        checkWritable("secrets")
        return auth.withToken { api.createSecret(it, scope, key, value, comment) }
    }

    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?): WriteOutcome {
        checkWritable("secrets")
        return auth.withToken { api.updateSecret(it, scope, key, value, comment) }
    }

    override fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        checkWritable("secrets")
        require(secrets.all { it.id.isNotEmpty() }) { "Secrets without an ID can't be moved; refresh and try again" }
        val moved = mutableListOf<String>()
        val awaiting = mutableListOf<String>()
        val notRemoved = linkedMapOf<String, String>()
        // Duplicate takes at most 50 secrets per request
        for (chunk in secrets.chunked(50)) {
            val copied = auth.withToken { api.duplicateSecrets(it, source, chunk.map(SecretEntry::id), destination, overwrite) }
            for (secret in chunk) {
                // The answer lists approvals; without a result entry the copy went through directly
                if (copied[secret.key] == true || copied[secret.id] == true) {
                    awaiting += secret.key
                    continue
                }
                try {
                    // With a change policy on the source folder the removal itself waits for approval: both copies exist until then
                    if (auth.withToken { api.deleteSecret(it, source, secret.key) } == WriteOutcome.APPROVAL_REQUESTED) {
                        notRemoved[secret.key] = "removal from ${source.environment}:${source.path} waits for approval"
                        continue
                    }
                    moved += secret.key
                } catch (e: SecretProviderException) {
                    notRemoved[secret.key] = e.message ?: e.kind.name
                }
            }
        }
        return MoveResult(moved, awaiting, notRemoved)
    }

    override fun deleteSecret(scope: SecretScope, key: String): WriteOutcome {
        checkWritable("secrets")
        return auth.withToken { api.deleteSecret(it, scope, key) }
    }

    override fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment {
        checkWritable("environments")
        return auth.withToken { api.createEnvironment(it, projectId, name, slug) }
    }

    // OAuth applications only offer the secrets:read scope
    private fun checkWritable(what: String) {
        if (connection.authMethodId != InfisicalAuthMethods.OAUTH) return
        throw SecretProviderException(
            SecretProviderException.Kind.CONFIGURATION,
            "OAuth connections are read-only (scope ${InfisicalAuthMethods.OAUTH_SCOPE}). Use browser sign-in or a machine identity " +
                "with write permission to create, change, move or delete $what.",
        )
    }
}
