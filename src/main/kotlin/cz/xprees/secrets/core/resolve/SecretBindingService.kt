package cz.xprees.secrets.core.resolve

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.ProjectFileDefaults
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.nio.file.Path

/**
 * Works out what a project is bound to: connection, provider project, environment and folder, from the IDE settings and the
 * provider's project file. Environment precedence (highest first), as in the Infisical CLI:
 *
 * 1. an explicit environment in a reference (handled by the reference itself)
 * 2. `ENVBRIDGE_ENVIRONMENT` (or the older `INFISICAL_ENVIRONMENT`) set on the run configuration ([environmentOverride])
 * 3. the environment picked in the IDE
 * 4. the project file's mapping for the current Git branch
 * 5. the project file's default environment
 * 6. `dev`
 */
@Service(Service.Level.PROJECT)
class SecretBindingService(private val project: Project) {

    data class Binding(
        val provider: SecretProvider,
        val connection: SecretConnection,
        val projectId: String?,
        val environment: String,
        val path: String,
        /** Where [environment] came from, for display ("run configuration", "IDE", "branch main", ".infisical.yaml", "default"). */
        val environmentSource: String,
        val projectFile: ProjectFileDefaults?,
    ) {
        val scope: SecretScope? get() = projectId?.let { SecretScope(it, environment, path) }

        fun defaults(): SecretReference.Defaults = SecretReference.Defaults(
            projectId = projectId ?: throw SecretProviderException(
                Kind.CONFIGURATION,
                "No ${provider.displayName} project is bound to this IDE project. Pick one in Settings › Tools › EnvBridge " +
                    "or add a project file (infisical init), or name it in the reference (?project=<id>).",
            ),
            environment = environment,
            path = path,
        )
    }

    val projectDir: Path? get() = project.guessProjectDir()?.toNioPathOrNull() ?: project.basePath?.let(Path::of)

    /** The provider's project file, or null. Reads the disk: call off the EDT. */
    fun projectFile(provider: SecretProvider): ProjectFileDefaults? {
        if (!ProjectSecretsSettings.getInstance(project).state.useProjectFile) return null
        return projectDir?.let { runCatching { provider.readProjectFile(it) }.getOrNull() }
    }

    /**
     * The binding for [provider].
     *
     * @param connectionIdOrName a connection a reference names explicitly
     * @throws SecretProviderException (CONFIGURATION) when no connection can be chosen
     */
    fun binding(provider: SecretProvider, environmentOverride: String? = null, connectionIdOrName: String? = null): Binding {
        val settings = ProjectSecretsSettings.getInstance(project).state
        val file = projectFile(provider)
        val connection = chooseConnection(provider, settings.connectionId, file, connectionIdOrName)
        // The IDE binding belongs to one connection: its project and environment mean nothing to another provider's
        val ide = settings.takeIf { it.connectionId.isNullOrBlank() || it.connectionId == connection.id }
        val (environment, source) = when {
            !environmentOverride.isNullOrBlank() -> environmentOverride to "run configuration"
            !ide?.environment.isNullOrBlank() -> ide.environment!! to "IDE"
            !file?.branchEnvironment.isNullOrBlank() -> file.branchEnvironment!! to "Git branch"
            !file?.environment.isNullOrBlank() -> file.environment!! to file.file.fileName.toString()
            else -> provider.defaultEnvironment(connection) to "default"
        }
        return Binding(
            provider = provider,
            connection = connection,
            projectId = ide?.projectId?.takeIf { it.isNotBlank() } ?: file?.projectId,
            environment = environment,
            path = SecretScope.normalizePath(ide?.path?.takeIf { it.isNotBlank() } ?: file?.path),
            environmentSource = source,
            projectFile = file,
        )
    }

    /**
     * The provider the project uses: the one of the bound connection (This Project), else the first provider that has a
     * connection, else the first provider.
     */
    fun boundProvider(): SecretProvider? {
        val app = SecretsAppSettings.getInstance()
        app.connection(ProjectSecretsSettings.getInstance(project).state.connectionId)
            ?.let { SecretProvider.byId(it.providerId) }?.let { return it }
        val providers = SecretProvider.all()
        return providers.firstOrNull { app.connectionsOf(it.id).isNotEmpty() } ?: providers.firstOrNull()
    }

    /** Like [binding], but null instead of an exception when nothing is configured. */
    fun bindingOrNull(provider: SecretProvider): Binding? = runCatching { binding(provider) }.getOrNull()

    private fun chooseConnection(
        provider: SecretProvider,
        boundId: String?,
        file: ProjectFileDefaults?,
        explicit: String?,
    ): SecretConnection {
        val app = SecretsAppSettings.getInstance()
        val candidates = app.connectionsOf(provider.id)
        if (explicit != null) {
            return app.connectionByIdOrName(explicit)?.takeIf { it.providerId == provider.id }
                ?: throw SecretProviderException(Kind.CONFIGURATION, "No ${provider.displayName} connection named '$explicit'")
        }
        candidates.firstOrNull { it.id == boundId }?.let { return it }
        if (candidates.isEmpty()) {
            throw SecretProviderException(
                Kind.AUTH_REQUIRED,
                "No ${provider.displayName} connection yet. Add one in Settings › Tools › EnvBridge.",
            )
        }
        val domain = file?.domain?.let(SecretConnection::normalizeBaseUrl)
        if (domain != null) {
            // The project file only picks among connections the user created; it never makes us talk to a new server.
            return candidates.firstOrNull { it.baseUrl.equals(domain, ignoreCase = true) }
                ?: throw SecretProviderException(
                    Kind.CONFIGURATION,
                    "${file.file.fileName} uses the instance $domain, but no connection for it exists. If you trust this instance, " +
                        "add a connection for it in Settings › Tools › EnvBridge.",
                )
        }
        return candidates.first()
    }

    companion object {
        const val DEFAULT_ENVIRONMENT = "dev"
        /** Set on a run configuration, picks the environment its references read (any provider). */
        const val ENVIRONMENT_VARIABLE = "ENVBRIDGE_ENVIRONMENT"

        /** The first name of [ENVIRONMENT_VARIABLE], still read (it loses when both are set). */
        const val LEGACY_ENVIRONMENT_VARIABLE = "INFISICAL_ENVIRONMENT"

        /** The environment override a run configuration's variables ask for, if any. */
        fun environmentOverride(variables: Map<String, String>): String? =
            variables[ENVIRONMENT_VARIABLE]?.takeIf { it.isNotBlank() } ?: variables[LEGACY_ENVIRONMENT_VARIABLE]?.takeIf { it.isNotBlank() }

        fun getInstance(project: Project): SecretBindingService = project.service()

        private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): Path? = runCatching { toNioPath() }.getOrNull()
    }
}
