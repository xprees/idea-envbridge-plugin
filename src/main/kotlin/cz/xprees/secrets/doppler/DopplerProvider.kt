package cz.xprees.secrets.doppler

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
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.ProjectFileDefaults
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SecretsClient
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
            displayName = "Browser sign-in",
            description = "Sign in with your Doppler account in the system browser, as `doppler login` does: check that the browser " +
                "shows the code the IDE shows, and approve. The token is kept in the IDE's password storage and revoked on sign-out.",
        ),
        AuthMethod(
            id = TOKEN,
            displayName = "Token",
            description = "A personal token (dp.pt.…), a service account token (dp.sa.…) or a service token (dp.st.…, which reads " +
                "one config only). Kept in the IDE's password storage; sign-out forgets it but doesn't revoke it.",
            secretOptions = listOf(AuthOption(OPTION_TOKEN, "Token:", hint = "dp.pt.…, dp.sa.… or dp.st.…")),
        ),
    )
}

/** [SecretsClient] for one Doppler connection. */
class DopplerClient(private val connection: SecretConnection) : SecretsClient {
    private val api = DopplerApi(DopplerHttp(connection.baseUrl))
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

    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?): WriteOutcome {
        checkNoFolder(scope)
        checkName(key)
        val current = existing(scope)[key]
            ?: throw SecretProviderException(Kind.NOT_FOUND, "$key doesn't exist in ${where(scope)}; create it instead")
        auth.withToken { api.setSecrets(it, scope.projectId, scope.environment, mapOf(key to value.reveal())) }
        if (comment != null && comment != current.note) saveNote(scope.projectId, key, comment)
        return WriteOutcome.APPLIED
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
        val conflicts = keys.filter { it in existing(destination) }
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
