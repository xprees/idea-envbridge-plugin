package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.reference.SecretReference

/**
 * Turns the environment variables of a run configuration into the environment of the process:
 *
 * - plain values stay as they are and win over everything else;
 * - `KEY=infisical://…` references are replaced by the secret's value;
 * - scope imports (a value whose last segment is `*`, or `**` with subfolders) add every secret of their scope and are not passed on themselves.
 *
 * Pure logic: fetching is a parameter, so this is shared by every provider and unit tested without a network.
 */
object EnvResolution {

    /** How the variables of one launch split up. */
    data class Plan(
        /** Plain values, in their original order. */
        val explicit: Map<String, String>,
        /** Variable name -> the secret it references. */
        val references: Map<String, SecretReference>,
        /** Scope imports, in their original order (later ones win over earlier ones). */
        val scopes: List<ScopeImport>,
        /** Variable name -> why its reference could not be parsed. */
        val errors: Map<String, String>,
    ) {
        val hasSecrets: Boolean get() = references.isNotEmpty() || scopes.isNotEmpty() || errors.isNotEmpty()

        /** Every reference of the plan (scope imports and single secrets). */
        val allReferences: List<SecretReference> get() = scopes.map { it.reference } + references.values
    }

    /** A scope import and the name of the variable that declared it (a label, not passed on). */
    data class ScopeImport(val variable: String, val reference: SecretReference)

    /** Where a variable of the final environment came from (for the preview and console masking). */
    sealed interface Source {
        data object Explicit : Source

        data class Reference(val reference: SecretReference, val scope: SecretScope) : Source

        data class Scope(val reference: SecretReference, val scope: SecretScope, val secretPath: String) : Source

        /** A secret replaced by the user's local override (kept in the credential store). */
        data class LocalOverride(val replaced: Source) : Source
    }

    data class Result(
        val environment: Map<String, String>,
        val sources: Map<String, Source>,
        /** Non-fatal problems (secrets of a scope import whose value may not be read, so they were left out). */
        val warnings: List<String> = emptyList(),
    ) {
        /** Environments the secrets came from (for the prod guard). */
        val environments: Set<String>
            get() = sources.values.mapNotNull { source ->
                when (val s = (source as? Source.LocalOverride)?.replaced ?: source) {
                    is Source.Reference -> s.scope.environment
                    is Source.Scope -> s.scope.environment
                    else -> null
                }
            }.toSet()

        /**
         * This result with [overrides] applied: a variable that came from a secret manager and has an override gets the
         * override's value. Values set on the run configuration are not touched.
         */
        fun withOverrides(overrides: Map<String, String>): Result {
            if (overrides.isEmpty()) return this
            val environment = LinkedHashMap(this.environment)
            val sources = LinkedHashMap(this.sources)
            for ((name, value) in overrides) {
                val source = sources[name] ?: continue
                if (source is Source.Explicit || source is Source.LocalOverride) continue
                environment[name] = value
                sources[name] = Source.LocalOverride(source)
            }
            return copy(environment = environment, sources = sources)
        }

        /**
         * One line for the console of the launched process: which variables came from a secret manager and from where.
         * Names and scopes only, never values. Null when nothing came from a secret manager.
         */
        fun summary(): String? {
            val injected = sources.filterValues { it !is Source.Explicit }
            if (injected.isEmpty()) return null
            val byScope = injected.entries.groupBy({ (_, source) -> label(source) }, { (name, _) -> name })
            val empty = injected.keys.filter { environment[it].isNullOrEmpty() }
            return buildString {
                append("EnvBridge: ").append(injected.size).append(if (injected.size == 1) " variable" else " variables").append(" from secrets: ")
                append(byScope.entries.joinToString("; ") { (scope, names) -> "$scope ${names.joinToString(", ")}" })
                if (empty.isNotEmpty()) append(" | empty: ").append(empty.joinToString(", "))
            }
        }

        private fun label(source: Source): String = when (source) {
            is Source.Reference -> "[${EnvResolution.describe(source.scope)}]"
            is Source.Scope -> "[${EnvResolution.describe(source.scope)}]"
            is Source.LocalOverride -> "[local override]"
            Source.Explicit -> ""
        }

        /** Values that came from a secret manager (to mask them in consoles). */
        val secretValues: Set<String>
            get() = sources.filterValues { it !is Source.Explicit }.keys.mapNotNull { environment[it] }.filter { it.isNotEmpty() }.toSet()
    }

    /** Splits [envs] into plain values, references and scope imports for the given reference [schemes]. */
    fun plan(envs: Map<String, String>, schemes: Collection<String>): Plan {
        val explicit = LinkedHashMap<String, String>()
        val references = LinkedHashMap<String, SecretReference>()
        val scopes = mutableListOf<ScopeImport>()
        val errors = LinkedHashMap<String, String>()
        for ((name, value) in envs) {
            val reference = try {
                SecretReference.parse(value, schemes)
            } catch (e: IllegalArgumentException) {
                errors[name] = e.message ?: "Malformed reference"
                continue
            }
            when {
                reference == null -> explicit[name] = value
                reference.isScope -> scopes += ScopeImport(name, reference)
                else -> references[name] = reference
            }
        }
        return Plan(explicit, references, scopes, errors)
    }

    /**
     * Resolves [plan].
     *
     * @param defaults the project binding a reference falls back to (per reference, as references can name other connections)
     * @param fetch the secrets of a scope, with imports applied. Called at most once per distinct (reference target, scope).
     * @param suggest for a key missing from its scope: a hint for the message (where the key is, or what was probably meant)
     * @throws SecretProviderException with every problem listed when anything cannot be resolved (fail closed)
     */
    fun resolve(
        plan: Plan,
        defaults: (SecretReference) -> SecretReference.Defaults,
        suggest: (SecretReference, SecretScope) -> String? = { _, _ -> null },
        fetch: (SecretReference, SecretScope) -> List<SecretEntry>,
    ): Result {
        val problems = plan.errors.map { (name, message) -> "$name: $message" }.toMutableList()
        if (problems.isNotEmpty()) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, problems.joinToString("\n"))
        }

        val fetched = HashMap<Pair<Target, SecretScope>, List<SecretEntry>>()
        // A folder that doesn't exist holds no secrets: reported per variable (with a hint) instead of failing the whole launch
        fun secretsOf(reference: SecretReference, scope: SecretScope): List<SecretEntry> =
            fetched.getOrPut(Target.of(reference) to scope) {
                try {
                    fetch(reference, scope)
                } catch (e: SecretProviderException) {
                    if (e.kind != SecretProviderException.Kind.NOT_FOUND) throw e
                    emptyList()
                }
            }

        val environment = LinkedHashMap<String, String>()
        val sources = LinkedHashMap<String, Source>()
        val warnings = mutableListOf<String>()

        for (import in plan.scopes) {
            val scope = import.reference.scope(defaults(import.reference))
            for (secret in secretsOf(import.reference, scope)) {
                if (secret.valueHidden) {
                    warnings += "${secret.key} (${describe(scope)}): no permission to read its value, left out"
                    continue
                }
                environment[secret.key] = secret.value.reveal()
                sources[secret.key] = Source.Scope(import.reference, scope, secret.path)
            }
        }
        for ((name, reference) in plan.references) {
            val scope = reference.scope(defaults(reference))
            val secret = secretsOf(reference, scope).firstOrNull { it.key == reference.key }
            if (secret == null) {
                val hint = runCatching { suggest(reference, scope) }.getOrNull()
                problems += "$name: no secret ${reference.key} in ${describe(scope)}" + (hint?.let { "; $it" } ?: "")
                continue
            }
            if (secret.valueHidden) {
                problems += "$name: no permission to read the value of ${reference.key} in ${describe(scope)}"
                continue
            }
            val value = secret.value.reveal()
            // An empty value is passed on (it may be intended), but said out loud: the app may fall back to a default
            if (value.isEmpty()) warnings += "$name: ${reference.key} in ${describe(scope)} has an empty value"
            environment[name] = value
            sources[name] = Source.Reference(reference, scope)
        }
        if (problems.isNotEmpty()) {
            throw SecretProviderException(SecretProviderException.Kind.NOT_FOUND, problems.joinToString("\n"))
        }
        for ((name, value) in plan.explicit) {
            environment[name] = value
            sources[name] = Source.Explicit
        }
        return Result(environment, sources, warnings)
    }

    fun describe(scope: SecretScope): String = "${scope.environment}:${scope.path}" + if (scope.recursive) " (with subfolders)" else ""

    /** Which account a reference reads through: its scheme plus an explicit connection, if any. */
    private data class Target(val scheme: String, val connectionId: String?) {
        companion object {
            fun of(reference: SecretReference) = Target(reference.scheme, reference.connectionId)
        }
    }
}
