package cz.xprees.secrets.infisical

import cz.xprees.secrets.core.provider.CopyResult
import cz.xprees.secrets.core.provider.MoveResult

import cz.xprees.secrets.core.model.ProviderEnvironment

import cz.xprees.secrets.core.provider.SecretProviderException

import cz.xprees.secrets.core.model.SecretValue

import cz.xprees.secrets.core.provider.WriteOutcome

import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.ProjectFileDefaults
import cz.xprees.secrets.core.resolve.GitHead
import cz.xprees.secrets.infisical.config.InfisicalProjectConfig
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.renameTarget
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
            displayName = SecretsBundle.message("auth.infisical.browser.name"),
            description = SecretsBundle.message("auth.infisical.browser.description"),
        ),
        AuthMethod(
            id = OAUTH,
            displayName = SecretsBundle.message("auth.infisical.oauth.name"),
            description = SecretsBundle.message("auth.infisical.oauth.description"),
            options = listOf(
                AuthOption(OPTION_CLIENT_ID, SecretsBundle.message("auth.infisical.option.clientId.label")),
                AuthOption(
                    OPTION_CALLBACK_PORT,
                    SecretsBundle.message("auth.infisical.option.callbackPort.label"),
                    required = false,
                    hint = DEFAULT_CALLBACK_PORT.toString(),
                ),
            ),
            secretOptions = listOf(AuthOption(OPTION_CLIENT_SECRET, SecretsBundle.message("auth.infisical.option.clientSecret.label"))),
        ),
        AuthMethod(
            id = MACHINE_IDENTITY,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink(SecretsBundle.message("auth.link.dashboard", "Infisical")) { it.trimEnd('/') },
                cz.xprees.secrets.core.provider.SetupLink.fixed(SecretsBundle.message("auth.link.identity"), "https://infisical.com/docs/documentation/platform/identities/universal-auth"),
            ),
            displayName = SecretsBundle.message("auth.infisical.machine.identity.name"),
            description = SecretsBundle.message("auth.infisical.machine.identity.description"),
            options = listOf(
                AuthOption(OPTION_CLIENT_ID, SecretsBundle.message("auth.infisical.option.clientId.label")),
                AuthOption(
                    OPTION_ORGANIZATION_SLUG,
                    SecretsBundle.message("auth.infisical.option.organizationSlug.label"),
                    required = false,
                    hint = SecretsBundle.message("auth.infisical.option.organizationSlug.hint"),
                ),
            ),
            secretOptions = listOf(AuthOption(OPTION_CLIENT_SECRET, SecretsBundle.message("auth.infisical.option.clientSecret.label"))),
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

    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?, newKey: String?): WriteOutcome {
        checkWritable("secrets")
        val rename = renameTarget(key, newKey)
        // Secrets imported from elsewhere don't count: they live there
        val folder = auth.withToken { api.listSecrets(it, scope.copy(recursive = false)) }.filter { it.origin != SecretOrigin.IMPORTED && it.path == scope.path }
        // Checked first, so a taken name changes nothing
        if (rename != null && folder.any { it.key == rename }) {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "${scope.environment}:${scope.path} already has a secret $rename; choose another name or delete that one first",
            )
        }
        // Listings show values with references expanded: a value left as it was isn't sent, so `${…}` references are kept
        val current = folder.firstOrNull { it.key == key && it.origin == SecretOrigin.LOCAL }
        val changed = value.takeIf { current == null || current.value != it }
        return auth.withToken { api.updateSecret(it, scope, key, changed, comment, rename) }
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

    /** Infisical duplicates the stored secrets (value with its references, comment, tags), never overwriting. */
    override fun copySecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope): CopyResult {
        checkWritable("secrets")
        require(secrets.all { it.id.isNotEmpty() }) { "Secrets without an ID can't be copied; refresh and try again" }
        val existing = auth.withToken { api.listSecrets(it, destination.copy(recursive = false)) }
            .filter { it.origin != SecretOrigin.IMPORTED && it.path == destination.path }.map { it.key }.toSet()
        val failed = linkedMapOf<String, String>()
        secrets.filter { it.key in existing }.forEach { failed[it.key] = "already in ${destination.environment}:${destination.path}" }
        val copied = mutableListOf<String>()
        val awaiting = mutableListOf<String>()
        for (chunk in secrets.filter { it.key !in existing }.chunked(50)) {
            val result = auth.withToken { api.duplicateSecrets(it, source, chunk.map(SecretEntry::id), destination, overwrite = false) }
            chunk.forEach { if (result[it.key] == true || result[it.id] == true) awaiting += it.key else copied += it.key }
        }
        return CopyResult(copied, awaiting, failed)
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
