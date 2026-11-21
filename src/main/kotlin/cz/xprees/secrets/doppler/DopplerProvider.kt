package cz.xprees.secrets.doppler

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
import cz.xprees.secrets.core.provider.CopyResult
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.ProjectFileDefaults
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.renameTarget
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.reference.ReferenceGrammar
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Path
import javax.swing.Icon

/**
 * Doppler (unofficial integration). Projects are Doppler projects; the EnvBridge "environment" is a Doppler **config**
 * (`dev`, `dev_personal`, `stg`, `prd`, branch configs such as `dev_feature`), because configs hold the secrets. Doppler has no
 * folders. Reading and writing.
 */
class DopplerProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "Doppler"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    override val referenceScheme: String = "doppler"
    override val projectFileName: String = "doppler.yaml"
    override val referenceGrammar: ReferenceGrammar get() = DopplerGrammar

    // Doppler is a hosted service; the address stays editable (tests, a proxy in front of the API)
    override val instancePresets: List<Pair<String, String>> = listOf("Doppler" to API_URL)

    override val authMethods: List<AuthMethod> = DopplerAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String = "dev"

    override fun createClient(connection: SecretConnection): SecretsClient = DopplerClient(connection)

    override fun readProjectFile(projectDir: Path): ProjectFileDefaults? = DopplerProjectFile.find(projectDir)?.toDefaults()

    /** `https://dashboard.doppler.com/workplace/<workplace>/projects/<project>/configs/<config>`, or the dashboard's start page. */
    override fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String?, key: String?): String {
        val dashboard = dashboardBase(connection.baseUrl)
        if (scope == null || organizationId.isNullOrBlank()) return dashboard
        fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        return "$dashboard/workplace/${enc(organizationId)}/projects/${enc(scope.projectId)}/configs/${enc(scope.environment)}"
    }

    companion object {
        const val ID = "doppler"
        const val API_URL = "https://api.doppler.com"
        const val DASHBOARD_URL = "https://dashboard.doppler.com"

        /**
         * The API address requests are built on: [baseUrl] without an API path, so an endpoint pasted as the address
         * (`https://api.doppler.com/v3/workplace`, `…/v3`) works like `https://api.doppler.com`.
         */
        fun apiOrigin(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
            val path = uri.rawPath.orEmpty()
            val api = Regex("""/v\d+(/.*)?$""").find(path) ?: return trimmed
            return trimmed.removeSuffix(path) + path.substring(0, api.range.first)
        }

        /** The dashboard of an API address: `https://api.doppler.com` -> `https://dashboard.doppler.com`; others as they are. */
        fun dashboardBase(baseUrl: String): String {
            val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return DASHBOARD_URL
            val host = uri.host ?: return DASHBOARD_URL
            return if (host.startsWith("api.")) "${uri.scheme}://dashboard.${host.removePrefix("api.")}" else baseUrl.trimEnd('/')
        }
    }
}

object DopplerAuthMethods {
    const val BROWSER = "browser"
    const val TOKEN = "token"

    const val OPTION_TOKEN = "token"

    val ALL = listOf(
        AuthMethod(
            id = BROWSER,
            displayName = SecretsBundle.message("auth.doppler.browser.name"),
            description = SecretsBundle.message("auth.doppler.browser.description"),
        ),
        AuthMethod(
            id = TOKEN,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink(SecretsBundle.message("auth.link.dashboard", "Doppler")) { DopplerProvider.dashboardBase(DopplerProvider.apiOrigin(it)) },
                cz.xprees.secrets.core.provider.SetupLink.fixed(SecretsBundle.message("auth.link.tokens"), "https://docs.doppler.com/reference/api#authentication"),
            ),
            displayName = SecretsBundle.message("auth.doppler.token.name"),
            description = SecretsBundle.message("auth.doppler.token.description"),
            secretOptions = listOf(
                AuthOption(
                    OPTION_TOKEN,
                    SecretsBundle.message("auth.doppler.option.token.label"),
                    hint = SecretsBundle.message("auth.doppler.option.token.hint"),
                ),
            ),
        ),
    )
}

/** [SecretsClient] for one Doppler connection. */
class DopplerClient(private val connection: SecretConnection) : SecretsClient {
    private val api = DopplerApi(DopplerHttp(DopplerProvider.apiOrigin(connection.baseUrl)))
    private val auth = DopplerAuth(connection, api)

    override fun signIn(interaction: SignInInteraction): String = auth.signIn(interaction)

    override fun signOut() = auth.signOut()

    override fun hasCredentials(): Boolean = auth.hasCredentials()

    /**
     * Every project with its configs as environments. A service token sees one config only (listing projects is refused):
     * that project and config are offered alone.
     */
    override fun listProjects(): List<ProviderProject> {
        val me = runCatching { auth.me() }.getOrNull()
        if (isServiceToken(me)) return listOf(serviceTokenProject(me))
        val projects = try {
            auth.withToken(api::projects)
        } catch (e: SecretProviderException) {
            if (e.kind != Kind.FORBIDDEN) throw e
            return listOf(runCatching { serviceTokenProject(me) }.getOrElse { throw e })
        }
        return projects.sortedBy { it.name.lowercase() }.map { project ->
            val configs = try {
                auth.withToken { api.configs(it, project.slug) }
            } catch (e: SecretProviderException) {
                if (e.kind != Kind.FORBIDDEN) throw e
                emptyList()
            }
            ProviderProject(
                id = project.slug,
                name = project.name,
                slug = project.slug,
                environments = configs.map { ProviderEnvironment(slug = it.name, name = displayName(it)) },
                organizationId = me?.workplaceSlug,
            )
        }
    }

    /** Doppler has no folders. */
    override fun listFolders(scope: SecretScope): List<SecretFolder> = emptyList()

    /**
     * The config's secrets with Doppler's computed values (references `${…}` resolved by Doppler). Doppler's own
     * `DOPPLER_PROJECT` / `DOPPLER_CONFIG` / `DOPPLER_ENVIRONMENT` are left out: they describe the binding, not secrets.
     */
    override fun listSecrets(scope: SecretScope): List<SecretEntry> {
        checkNoFolder(scope)
        return auth.withToken { api.secrets(it, scope.projectId, scope.environment) }
            .filter { it.name !in METADATA }
            .sortedBy { it.name }
            .map { secret ->
                SecretEntry(
                    key = secret.name,
                    value = SecretValue(if (secret.hidden) "" else secret.computed.orEmpty()),
                    comment = secret.note,
                    path = "/",
                    environment = scope.environment,
                    valueHidden = secret.hidden,
                    id = secret.name,
                )
            }
    }

    // ---- Writes ------------------------------------------------------------------------------------------------------

    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        checkNoFolder(scope)
        checkName(key)
        if (existing(scope).containsKey(key)) {
            throw SecretProviderException(Kind.CONFIGURATION, "$key already exists in ${where(scope)}; edit it instead")
        }
        auth.withToken { api.setSecrets(it, scope.projectId, scope.environment, mapOf(key to value.reveal())) }
        if (comment.isNotEmpty()) saveNote(scope.projectId, key, comment)
        return WriteOutcome.APPLIED
    }

    /**
     * Sets the secret's value (and note). With a [newKey], renames it in the same request (a change request naming the
     * original); the note, which Doppler keeps per project and name, is saved under the new name too.
     */
    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?, newKey: String?): WriteOutcome {
        checkNoFolder(scope)
        checkName(key)
        val rename = renameTarget(key, newKey)?.also(::checkName)
        val secrets = existing(scope)
        val current = secrets[key]
            ?: throw SecretProviderException(Kind.NOT_FOUND, "$key doesn't exist in ${where(scope)}; create it instead")
        // Listings (and so the dialog) show the computed value: left as it was, the raw one is kept so `${…}` references keep working
        val raw = current.raw?.takeIf { value.reveal() == current.computed } ?: value.reveal()
        if (rename == null) {
            if (raw != current.raw) auth.withToken { api.setSecrets(it, scope.projectId, scope.environment, mapOf(key to raw)) }
            if (comment != null && comment != current.note) saveNote(scope.projectId, key, comment)
            return WriteOutcome.APPLIED
        }
        if (rename in secrets) {
            throw SecretProviderException(Kind.CONFIGURATION, "$rename already exists in ${where(scope)}; choose another name or delete that one first")
        }
        auth.withToken { api.renameSecret(it, scope.projectId, scope.environment, key, rename, raw) }
        if (comment != null && comment != current.note) {
            saveNote(scope.projectId, rename, comment)
        } else if (current.note.isNotEmpty()) {
            // Carried over to the new name, best effort: the rename is what matters
            runCatching { auth.withToken { api.setNote(it, scope.projectId, rename, current.note) } }
        }
        return WriteOutcome.APPLIED
    }

    /** Copies the raw values (so Doppler references keep working), never overwriting; notes follow to another project. */
    override fun copySecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope): CopyResult {
        checkNoFolder(source)
        checkNoFolder(destination)
        val keys = secrets.map { it.key }.distinct()
        val sourceSecrets = existing(source)
        val atDestination = existing(destination).keys
        val failed = linkedMapOf<String, String>()
        val copyable = keys.filter { key ->
            val reason = when {
                key !in sourceSecrets -> "not in ${where(source)} any more"
                sourceSecrets.getValue(key).raw == null -> "its value can't be read (restricted in Doppler)"
                key in atDestination -> "already in ${where(destination)}"
                else -> null
            }
            reason?.let { failed[key] = it }
            reason == null
        }
        if (copyable.isNotEmpty()) {
            auth.withToken { api.setSecrets(it, destination.projectId, destination.environment, copyable.associateWith { k -> sourceSecrets.getValue(k).raw!! }) }
            if (source.projectId != destination.projectId) {
                for (key in copyable) {
                    val note = sourceSecrets.getValue(key).note
                    if (note.isNotEmpty()) runCatching { auth.withToken { api.setNote(it, destination.projectId, key, note) } }
                }
            }
        }
        return CopyResult(copyable, emptyList(), failed)
    }

    override fun deleteSecret(scope: SecretScope, key: String): WriteOutcome {
        checkNoFolder(scope)
        auth.withToken { api.deleteSecret(it, scope.projectId, scope.environment, key) }
        return WriteOutcome.APPLIED
    }

    /**
     * Copies [secrets] to the destination config (their raw values, so Doppler references keep working) in one request, then
     * deletes each from the source. A source copy that can't be deleted stays (reported in [MoveResult.notRemoved]): a
     * failure never loses a secret.
     */
    override fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        checkNoFolder(source)
        checkNoFolder(destination)
        if (source.projectId == destination.projectId && source.environment == destination.environment) {
            throw SecretProviderException(Kind.CONFIGURATION, "The secrets are already in ${where(source)}")
        }
        val keys = secrets.map { it.key }.distinct()
        if (keys.isEmpty()) return MoveResult(emptyList())
        keys.forEach(::checkName)
        val sourceSecrets = existing(source)
        val missing = keys.filter { it !in sourceSecrets }
        if (missing.isNotEmpty()) throw SecretProviderException(Kind.NOT_FOUND, "Not in ${where(source)} any more: ${missing.joinToString()}")
        val unreadable = keys.filter { sourceSecrets.getValue(it).raw == null }
        if (unreadable.isNotEmpty()) {
            throw SecretProviderException(Kind.FORBIDDEN, "Can't read the values of ${unreadable.joinToString()} (restricted in Doppler), so they can't be moved")
        }
        val atDestination = existing(destination).keys
        val conflicts = keys.filter { it in atDestination }
        if (conflicts.isNotEmpty() && !overwrite) {
            throw SecretProviderException(Kind.CONFIGURATION, "Already in ${where(destination)}: ${conflicts.joinToString()}; choose to overwrite, or move the others")
        }

        auth.withToken { api.setSecrets(it, destination.projectId, destination.environment, keys.associateWith { k -> sourceSecrets.getValue(k).raw!! }) }
        if (source.projectId != destination.projectId) {
            // Notes belong to the project: carry them over (best effort, the values are what matters)
            for (key in keys) {
                val note = sourceSecrets.getValue(key).note
                if (note.isNotEmpty()) runCatching { auth.withToken { api.setNote(it, destination.projectId, key, note) } }
            }
        }

        val moved = mutableListOf<String>()
        val notRemoved = linkedMapOf<String, String>()
        for (key in keys) {
            try {
                auth.withToken { api.deleteSecret(it, source.projectId, source.environment, key) }
                moved += key
            } catch (e: SecretProviderException) {
                notRemoved[key] = e.message ?: e.kind.name
            }
        }
        return MoveResult(moved, notRemoved = notRemoved)
    }

    /**
     * Creates a branch config. Doppler names a branch config after its environment (`dev_feature` in `dev`); the slug may be
     * written with hyphens (`dev-feature`, as the dialog suggests), which become underscores. [name] is not used: a config
     * has only its name.
     */
    override fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment {
        val configName = slug.trim().lowercase().replace('-', '_')
        val environments = auth.withToken { api.configs(it, projectId) }.map { it.environment }.distinct()
        val environment = environments.filter { configName.startsWith(it + "_") && configName.length > it.length + 1 }.maxByOrNull { it.length }
            ?: throw SecretProviderException(
                Kind.CONFIGURATION,
                "Doppler branch configs start with their environment: " +
                    environments.ifEmpty { listOf("dev") }.joinToString(", ") { "${it}_<name> (or $it-<name>)" },
            )
        if (!CONFIG_NAME.matches(configName)) {
            throw SecretProviderException(Kind.CONFIGURATION, "'$configName' isn't a valid Doppler config name (lowercase letters, digits, _)")
        }
        val created = auth.withToken { api.createConfig(it, projectId, environment, configName) }
        return ProviderEnvironment(slug = created.name, name = displayName(created))
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    private fun existing(scope: SecretScope): Map<String, DopplerApi.Secret> =
        auth.withToken { api.secrets(it, scope.projectId, scope.environment) }.associateBy { it.name }

    private fun saveNote(project: String, key: String, note: String) {
        try {
            auth.withToken { api.setNote(it, project, key, note) }
        } catch (e: SecretProviderException) {
            throw SecretProviderException(e.kind, "$key was saved, but its note wasn't: ${e.message}", e)
        }
    }

    private fun isServiceToken(me: DopplerApi.Me?): Boolean {
        val type = me?.type?.lowercase().orEmpty()
        if (type.contains("service") && !type.contains("account")) return true
        if (type.isNotEmpty()) return false
        return connection.authMethodId == DopplerAuthMethods.TOKEN && runCatching { auth.token() }.getOrNull()?.startsWith(SERVICE_TOKEN_PREFIX) == true
    }

    /** The one project and config a service token can read: from `/v3/me` if it says, else from the config's own metadata. */
    private fun serviceTokenProject(me: DopplerApi.Me?): ProviderProject {
        var project = me?.project
        var config = me?.config
        if (project == null || config == null) {
            val metadata = auth.withToken { api.secrets(it, null, null) }.filter { it.name in METADATA }.associate { it.name to it.computed }
            project = project ?: metadata["DOPPLER_PROJECT"]
            config = config ?: metadata["DOPPLER_CONFIG"]
        }
        if (project.isNullOrBlank() || config.isNullOrBlank()) {
            throw SecretProviderException(Kind.CONFIGURATION, "Couldn't tell which Doppler project and config the token of '${connection.name}' reads")
        }
        return ProviderProject(
            id = project,
            name = project,
            slug = project,
            environments = listOf(ProviderEnvironment(slug = config, name = config)),
            organizationId = me?.workplaceSlug,
        )
    }

    companion object {
        private const val SERVICE_TOKEN_PREFIX = "dp.st."

        /** Secrets Doppler adds to every config by itself. */
        val METADATA = setOf("DOPPLER_PROJECT", "DOPPLER_CONFIG", "DOPPLER_ENVIRONMENT")

        /** Doppler secret names: uppercase letters, digits and underscores, not starting with a digit. */
        val SECRET_NAME = Regex("[A-Z_][A-Z0-9_]*")

        private val CONFIG_NAME = Regex("[a-z0-9]+(?:_[a-z0-9]+)+")

        fun checkName(key: String) {
            if (!SECRET_NAME.matches(key)) {
                val hint = key.uppercase().replace(Regex("[^A-Z0-9_]"), "_").let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }
                throw SecretProviderException(
                    Kind.CONFIGURATION,
                    "'$key' isn't a valid Doppler secret name: use uppercase letters, digits and underscores (for example $hint)",
                )
            }
        }

        private fun checkNoFolder(scope: SecretScope) {
            if (SecretScope.normalizePath(scope.path) != "/") {
                throw SecretProviderException(Kind.NOT_FOUND, "Doppler has no folders (${scope.path}); secrets live directly in a config")
            }
        }

        private fun where(scope: SecretScope) = "${scope.projectId}/${scope.environment}"

        /** `dev`, or `dev_feature (branch of dev)`. */
        fun displayName(config: DopplerApi.Config): String =
            if (config.root || config.environment.isBlank() || config.environment == config.name) config.name
            else "${config.name} (branch of ${config.environment})"
    }
}
