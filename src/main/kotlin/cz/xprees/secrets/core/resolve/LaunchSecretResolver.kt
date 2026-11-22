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
        checkBoundProject(plan.allReferences, bindings, ::providerOf)
        fun defaultsOf(reference: SecretReference): SecretReference.Defaults {
            val binding = bindingOf(reference)
            val defaults = if (reference.projectId != null) {
                SecretReference.Defaults(reference.projectId, binding.environment, binding.path)
            } else {
                binding.defaults()
            }
            // Not every reference takes the selected environment (Vault's full paths under an environment layout)
            return providerOf(reference).referenceDefaults(binding.connection, reference, defaults)
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
     * Reads single references one at a time for one launch (varlock compatibility mode), with the same bindings, the same
     * "only use the bound project" check and the same fresh fetching as [resolve]; each scope is fetched once.
     *
     * @param environment the launch's environment override ("Run with environment…", else `ENVBRIDGE_ENVIRONMENT`), or null
     */
    fun fetcher(environment: String?, fresh: Boolean = true): Fetcher = Fetcher(environment, fresh)

    /** See [fetcher]. Blocking network I/O: never on the EDT. Values are returned as [SecretEntry]s (masked `toString`). */
    inner class Fetcher internal constructor(private val environment: String?, private val fresh: Boolean) {
        private val bindings = SecretBindingService.getInstance(project)
        private val fetched = HashMap<Pair<String, SecretScope>, List<cz.xprees.secrets.core.model.SecretEntry>>()

        private fun providerOf(reference: SecretReference) = SecretProvider.byScheme(reference.scheme)
            ?: throw SecretProviderException(Kind.CONFIGURATION, "Unknown reference scheme ${reference.scheme}://")

        private fun bindingOf(reference: SecretReference) = bindings.binding(providerOf(reference), environment, reference.connectionId)

        /** The scope [reference] reads (the binding fills in what it leaves open). */
        fun scope(reference: SecretReference): SecretScope {
            val binding = bindingOf(reference)
            val defaults = if (reference.projectId != null) {
                SecretReference.Defaults(reference.projectId, binding.environment, binding.path)
            } else {
                binding.defaults()
            }
            return reference.scope(providerOf(reference).referenceDefaults(binding.connection, reference, defaults))
        }

        /** The secrets of the scope of [reference] (empty when the folder doesn't exist), after the bound-project check. */
        fun entries(reference: SecretReference): List<cz.xprees.secrets.core.model.SecretEntry> {
            checkBoundProject(listOf(reference), bindings, ::providerOf)
            val connection = bindingOf(reference).connection
            val scope = scope(reference)
            return fetched.getOrPut(connection.id to scope) {
                try {
                    SecretsService.getInstance().secrets(connection, scope, fresh)
                } catch (e: SecretProviderException) {
                    if (e.kind != Kind.NOT_FOUND) throw e
                    emptyList()
                }
            }
        }

        /** The secret [reference] names, or null when it doesn't exist. Throws when its value may not be read. */
        fun secret(reference: SecretReference): cz.xprees.secrets.core.model.SecretEntry? {
            val secret = entries(reference).firstOrNull { it.key == reference.key } ?: return null
            if (secret.valueHidden) {
                throw SecretProviderException(Kind.FORBIDDEN, "no permission to read the value of ${reference.key} in ${EnvResolution.describe(scope(reference))}")
            }
            return secret
        }
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
    private fun checkBoundProject(references: List<SecretReference>, bindings: SecretBindingService, providerOf: (SecretReference) -> SecretProvider) {
        if (!ProjectSecretsSettings.getInstance(project).state.restrictToBoundProject) return
        val problems = mutableListOf<String>()
        for (reference in references) {
            val bound = bindings.bindingOrNull(providerOf(reference)) ?: continue
            val boundProject = bound.projectId ?: continue
            // The project as listings name it (a Vault full path may lie in the bound layout project)
            val written = providerOf(reference).referencedProject(bound.connection, reference)
            if (written != null && !namesProject(written, boundProject, bound.connection)) {
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
