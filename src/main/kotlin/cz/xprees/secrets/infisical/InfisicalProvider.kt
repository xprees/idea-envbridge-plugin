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
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.model.RotationInfo
import cz.xprees.secrets.core.provider.MetadataEditor
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

    /** A secret a secret rotation writes: the hover shows the rotation's name and schedule (read on demand). */
    override fun hasSecretDetails(connection: SecretConnection, entry: SecretEntry?): Boolean = entry?.metadata?.rotation?.managed == true

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

    /** Created with its tags in the same request (`tagIds`), so a change request covers both. */
    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String, metadata: SecretMetadata?): WriteOutcome {
        checkWritable("secrets")
        return auth.withToken { token ->
            val tagIds = metadata?.tags?.keys?.takeIf { it.isNotEmpty() }?.let { tagIds(token, scope.projectId, it) }
            api.createSecret(token, scope, key, value, comment, tagIds)
        }
    }

    /** Tags are picked from the project's tags (their slugs); the current ones are read again. */
    override fun metadataEditor(scope: SecretScope, key: String?): MetadataEditor = auth.withToken { token ->
        val choices = api.listTags(token, scope.projectId).map { it.slug }
        val current = key?.let { api.describeSecret(token, scope, it) }?.let(InfisicalApi::parseMetadata) ?: SecretMetadata()
        MetadataEditor(current = SecretMetadata(tags = current.tags), tagChoices = choices)
    }

    /** Replaces the secret's tags with [metadata]'s (`PATCH` with `tagIds` only; Infisical allows that on rotated secrets too). */
    override fun updateMetadata(scope: SecretScope, key: String, metadata: SecretMetadata): WriteOutcome {
        checkWritable("secrets")
        return auth.withToken { token ->
            val ids = tagIds(token, scope.projectId, metadata.tags.keys)
            api.updateSecret(token, scope, key, value = null, comment = null, tagIds = ids)
        }
    }

    /** The IDs of the project's tags with these slugs; CONFIGURATION, before anything is written, for a slug it doesn't have. */
    private fun tagIds(token: String, projectId: String, slugs: Collection<String>): List<String> {
        if (slugs.isEmpty()) return emptyList()
        val tags = api.listTags(token, projectId).associate { it.slug to it.id }
        val unknown = slugs.filter { it !in tags }
        if (unknown.isNotEmpty()) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "The project has no tag ${unknown.joinToString()}; create it in Infisical first")
        }
        return slugs.map { tags.getValue(it) }
    }

    /** The secret rotation writing [key] in the folder of [scope]; CONFIGURATION when no rotation writes it. */
    private fun rotationOf(token: String, scope: SecretScope, key: String): InfisicalApi.Rotation {
        val secret = api.describeSecret(token, scope, key)
            ?: throw SecretProviderException(SecretProviderException.Kind.NOT_FOUND, "${scope.environment}:${scope.path} has no secret $key any more; refresh")
        val id = InfisicalApi.rotationId(secret)
        if (InfisicalApi.parseMetadata(secret)?.rotation?.managed != true || id == null) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "$key isn't managed by a secret rotation")
        }
        return api.listRotations(token, scope.projectId).firstOrNull { it.id == id }
            ?: throw SecretProviderException(SecretProviderException.Kind.NOT_FOUND, "The secret rotation managing $key wasn't found (or you may not see it)")
    }

    /** The rotation writing the secret: its name, whether it runs automatically, when it last ran and runs next. */
    override fun secretDetails(scope: SecretScope, key: String?): SecretMetadata? {
        if (key == null) return null
        return auth.withToken { token ->
            val secret = api.describeSecret(token, scope, key) ?: return@withToken null
            val metadata = InfisicalApi.parseMetadata(secret) ?: return@withToken null
            if (metadata.rotation?.managed != true) return@withToken metadata
            val id = InfisicalApi.rotationId(secret) ?: return@withToken metadata
            val rotation = api.listRotations(token, scope.projectId).firstOrNull { it.id == id } ?: return@withToken metadata
            metadata.copy(rotation = RotationInfo(rotation.autoRotation, rotation.lastRotated, rotation.nextRotation, managed = true, name = rotation.name))
        }
    }

    /** Rotates the secret rotation writing [key] now (Secret Rotations v2 `rotate-secrets`): every secret it writes changes. */
    override fun rotateSecret(scope: SecretScope, key: String) {
        checkWritable("secrets")
        auth.withToken { token -> api.rotate(token, rotationOf(token, scope, key)) }
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
        // A secret rotation writes the value and name of its secrets; Infisical refuses changing them (the comment is fine)
        if (current?.metadata?.rotation?.managed == true && (changed != null || rename != null)) {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "$key is managed by a secret rotation: its value and name change only when it rotates (use Rotate Now)",
            )
        }
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
        // A folder that doesn't exist yet has nothing in it (duplicating creates it)
        val existing = try {
            auth.withToken { api.listSecrets(it, destination.copy(recursive = false)) }
                .filter { it.origin != SecretOrigin.IMPORTED && it.path == destination.path }.map { it.key }.toSet()
        } catch (e: SecretProviderException) {
            if (e.kind != SecretProviderException.Kind.NOT_FOUND) throw e
            emptySet()
        }
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
