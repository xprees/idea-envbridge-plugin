package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.model.SecretScope

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.settings.ProjectSecretsSettings

/** Resolves the environment variables of a launch (see [EnvResolution]) against the project's bindings. */
@Service(Service.Level.PROJECT)
class LaunchSecretResolver(private val project: Project) {

    fun schemes(): List<String> = SecretProvider.all().map { it.referenceScheme }

    /** Cheap check for the EDT: does any value look like a secret reference? */
    fun hasReferences(envs: Map<String, String>): Boolean {
        if (envs.isEmpty()) return false
        val schemes = schemes()
        return envs.values.any { value -> schemes.any { SecretReference.looksLikeReference(value, it) } }
    }

    fun plan(envs: Map<String, String>): EnvResolution.Plan = EnvResolution.plan(envs, schemes())

    /**
     * Fetches fresh values for every reference of [plan] and applies the user's local overrides. Blocking network I/O and
     * credential store access: never on the EDT.
     *
     * @param environment an environment for this launch only ("Run with environment…"), winning over the binding
     *
     * @throws SecretProviderException listing every problem (fail closed)
     */
    fun resolve(plan: EnvResolution.Plan, fresh: Boolean = true, environment: String? = null): EnvResolution.Result {
        val bindings = SecretBindingService.getInstance(project)
        // "Run with environment…" wins over ENVBRIDGE_ENVIRONMENT (or INFISICAL_ENVIRONMENT) on the configuration
        val environmentOverride = environment ?: SecretBindingService.environmentOverride(plan.explicit)
        val service = SecretsService.getInstance()
        fun providerOf(reference: SecretReference) = SecretProvider.byScheme(reference.scheme)
            ?: throw SecretProviderException(Kind.CONFIGURATION, "Unknown reference scheme ${reference.scheme}://")
        fun bindingOf(reference: SecretReference) = bindings.binding(providerOf(reference), environmentOverride, reference.connectionId)
        checkBoundProject(plan, bindings, ::providerOf)
        fun defaultsOf(reference: SecretReference): SecretReference.Defaults {
            val binding = bindingOf(reference)
            return if (reference.projectId != null) {
                SecretReference.Defaults(reference.projectId, binding.environment, binding.path)
            } else {
                binding.defaults()
            }
        }
        // Providers that read several secrets per request (AWS BatchGetSecretValue) get every scope of the launch up front
        plan.allReferences
            .mapNotNull { reference -> runCatching { bindingOf(reference).connection to reference.scope(defaultsOf(reference)) }.getOrNull() }
            .groupBy({ it.first }, { it.second })
            .forEach { (connection, scopes) -> service.prefetch(connection, scopes, fresh) }
        val result = EnvResolution.resolve(
            plan,
            defaults = ::defaultsOf,
            fetch = { reference, scope -> service.secrets(bindingOf(reference).connection, scope, fresh) },
            suggest = { reference, scope -> suggestion(reference, scope, bindingOf(reference).connection) },
        )
        return if (plan.hasSecrets) result.withOverrides(LocalOverrides.getInstance(project).all()) else result
    }

    /**
     * A hint for a key missing from [scope]: references written the old way (`infisical://prod/KEY` with the environment as the first segment) now
     * name folder /prod, so say what was probably meant; otherwise name the folders of the environment that define the key.
     */
    private fun suggestion(reference: SecretReference, scope: SecretScope, connection: cz.xprees.secrets.core.model.SecretConnection): String? {
        val service = SecretsService.getInstance()
        val firstFolder = scope.path.split('/').firstOrNull { it.isNotEmpty() }
        if (reference.environment == null && firstFolder != null) {
            val environments = runCatching { service.projects(connection).firstOrNull { it.id == scope.projectId }?.environments?.map { it.slug } }
                .getOrNull().orEmpty()
            if (firstFolder in environments) {
                val rest = scope.path.split('/').filter { it.isNotEmpty() }.drop(1).joinToString("/", prefix = "/")
                val meant = reference.copy(environment = firstFolder, path = rest.takeIf { it != "/" })
                return "$firstFolder is an environment: write ${meant.format()} (the environment goes before a colon)"
            }
        }
        val folders = runCatching {
            service.secrets(connection, SecretScope(scope.projectId, scope.environment, "/", recursive = true))
                .filter { it.key == reference.key && it.origin != cz.xprees.secrets.core.model.SecretOrigin.IMPORTED }
                .map { it.path }.distinct().sorted().filter { it != scope.path }
        }.getOrDefault(emptyList())
        if (folders.isEmpty()) return null
        return "it is in " + folders.joinToString { folder -> "$folder (${reference.copy(path = folder.takeIf { it != "/" }).format()})" }
    }

    /**
     * With "only use the bound project" on and a project bound, references may not leave it: naming another project or another
     * connection is refused, so secrets of an unrelated project can't reach this one by accident.
     */
    private fun checkBoundProject(plan: EnvResolution.Plan, bindings: SecretBindingService, providerOf: (SecretReference) -> SecretProvider) {
        if (!ProjectSecretsSettings.getInstance(project).state.restrictToBoundProject) return
        val problems = mutableListOf<String>()
        for (reference in plan.allReferences) {
            val bound = bindings.bindingOrNull(providerOf(reference)) ?: continue
            val boundProject = bound.projectId ?: continue
            if (reference.projectId != null && !namesProject(reference.projectId, boundProject, bound.connection)) {
                problems += SecretsBundle.message("restrict.other.project", reference.format(), boundProject)
            }
            val connection = reference.connectionId
            if (connection != null && !connection.equals(bound.connection.id) && !connection.equals(bound.connection.name, ignoreCase = true)) {
                problems += SecretsBundle.message("restrict.other.connection", reference.format(), bound.connection.name)
            }
        }
        if (problems.isNotEmpty()) throw SecretProviderException(Kind.CONFIGURATION, problems.joinToString("\n"))
    }

    /**
     * Whether [written] (a project as a reference writes it) is the project [boundProjectId]: its ID, or its slug or name where the
     * provider's references name projects that way (a 1Password vault by its name).
     */
    private fun namesProject(written: String, boundProjectId: String, connection: cz.xprees.secrets.core.model.SecretConnection): Boolean {
        if (written == boundProjectId) return true
        val bound = runCatching { SecretsService.getInstance().projects(connection).firstOrNull { it.id == boundProjectId } }.getOrNull()
            ?: return false
        return written == bound.slug || written == bound.name
    }

    companion object {
        fun getInstance(project: Project): LaunchSecretResolver = project.service()
    }
}
