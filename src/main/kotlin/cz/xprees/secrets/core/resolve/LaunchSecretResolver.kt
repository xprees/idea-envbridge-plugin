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
        // "Run with environment…" wins over INFISICAL_ENVIRONMENT on the configuration
        val environmentOverride = environment ?: plan.explicit[SecretBindingService.ENVIRONMENT_VARIABLE]
        val service = SecretsService.getInstance()
        fun providerOf(reference: SecretReference) = SecretProvider.byScheme(reference.scheme)
            ?: throw SecretProviderException(Kind.CONFIGURATION, "Unknown reference scheme ${reference.scheme}://")
        fun bindingOf(reference: SecretReference) = bindings.binding(providerOf(reference), environmentOverride, reference.connectionId)
        checkBoundProject(plan, bindings, ::providerOf)
        val result = EnvResolution.resolve(
            plan,
            defaults = { reference ->
                val binding = bindingOf(reference)
                if (reference.projectId != null) {
                    SecretReference.Defaults(reference.projectId, binding.environment, binding.path)
                } else {
                    binding.defaults()
                }
            },
            fetch = { reference, scope -> service.secrets(bindingOf(reference).connection, scope, fresh) },
            locate = { reference, scope ->
                service.secrets(bindingOf(reference).connection, SecretScope(scope.projectId, scope.environment, "/", recursive = true))
                    .filter { it.key == reference.key && it.origin != cz.xprees.secrets.core.model.SecretOrigin.IMPORTED }
                    .map { it.path }.distinct().sorted()
            },
        )
        return if (plan.hasSecrets) result.withOverrides(LocalOverrides.getInstance(project).all()) else result
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
            if (reference.projectId != null && reference.projectId != boundProject) {
                problems += SecretsBundle.message("restrict.other.project", reference.format(), boundProject)
            }
            val connection = reference.connectionId
            if (connection != null && !connection.equals(bound.connection.id) && !connection.equals(bound.connection.name, ignoreCase = true)) {
                problems += SecretsBundle.message("restrict.other.connection", reference.format(), bound.connection.name)
            }
        }
        if (problems.isNotEmpty()) throw SecretProviderException(Kind.CONFIGURATION, problems.joinToString("\n"))
    }

    companion object {
        fun getInstance(project: Project): LaunchSecretResolver = project.service()
    }
}
