package cz.xprees.secrets.core.resolve

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.reference.SecretReference

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
        )
        return if (plan.hasSecrets) result.withOverrides(LocalOverrides.getInstance(project).all()) else result
    }

    companion object {
        fun getInstance(project: Project): LaunchSecretResolver = project.service()
    }
}
