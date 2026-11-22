package cz.xprees.secrets.run

import com.intellij.openapi.project.Project
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.EnvResolution
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.LocalOverrides
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.core.varlock.DEFAULT_INSTANCE
import cz.xprees.secrets.core.varlock.SecretCall
import cz.xprees.secrets.core.varlock.SecretUse
import cz.xprees.secrets.core.varlock.VarlockEvaluator
import cz.xprees.secrets.core.varlock.VarlockException
import cz.xprees.secrets.core.varlock.VarlockExpr
import cz.xprees.secrets.core.varlock.VarlockProblem
import cz.xprees.secrets.core.varlock.VarlockProblem.Code
import cz.xprees.secrets.core.varlock.VarlockProvider
import cz.xprees.secrets.core.varlock.VarlockSecrets
import cz.xprees.secrets.core.varlock.VarlockValue
import java.nio.file.Path

/** Where an item (or a root decorator) is: the file and its zero-based line. */
data class VarlockLocation(val file: Path, val line: Int)

/** A problem of the varlock mode and where it is, if known. */
data class VarlockLocatedProblem(val problem: VarlockProblem, val location: VarlockLocation?)

/**
 * What the varlock compatibility mode found in a launch's env files ([EnvFileReferences.prepare]): the items whose values are
 * varlock expressions, the plugin instances (`@init…`), problems found before anything is fetched, and the environment.
 * Holds no values (expressions are the files' text, compiled).
 */
class VarlockPlan(
    /** Items with a varlock expression (not set on the configuration): resolved by [VarlockLaunch.resolve]. */
    val definitions: Map<String, VarlockExpr>,
    /** The arguments EnvBridge reads of each `@init…(id=…)` ([VarlockProvider.connectionArgs]), compiled. */
    val instances: Map<Pair<VarlockProvider, String>, Map<String, VarlockExpr>>,
    /** Problems found in the files (refused and unsupported functions, imports, bulk loading), with where they are. */
    val problems: List<VarlockLocatedProblem>,
    /** Where each item is defined (the file that wins). */
    val locations: Map<String, VarlockLocation>,
    /** Items only `@init…` credentials refer to (`clientSecret=$INFISICAL_CLIENT_SECRET`): EnvBridge doesn't need them. */
    val credentialKeys: Set<String>,
    /** Warnings to show at launch (credentials written inline in a header). No values. */
    val warnings: List<String>,
    /** The current environment (for `forEnv(…)` and the layered files), or null. */
    val environment: String?,
) {
    override fun toString(): String = "VarlockPlan(keys=${definitions.keys}, problems=${problems.size}, environment=$environment)"
}

/** The launch can't go on: varlock values that are refused, unsupported or can't be resolved. Messages name keys, never values. */
class VarlockLaunchException(val problems: List<VarlockLocatedProblem>) :
    SecretProviderException(SecretProviderException.Kind.CONFIGURATION, problems.joinToString("\n") { VarlockLaunch.message(it.problem) })

/**
 * Resolves the varlock values of a launch (varlock compatibility mode, `SecretsAppSettings.state.varlockCompatibility`) after
 * EnvBridge's own references are resolved: evaluates every item of [VarlockPlan.definitions] ([VarlockEvaluator]), reading
 * secret functions through EnvBridge connections ([LaunchSecretResolver.fetcher]).
 *
 * **Instances to connections** (`@initInfisical(id=prod, …)`, `infisical(prod, "KEY")`): an instance whose `@init…` names a server
 * (`siteUrl=` of Infisical, `url=` of Vault, `region=` of AWS) uses the EnvBridge connection of that provider with that URL /
 * region, and fails when there is none (EnvBridge never connects to a server the user didn't add); otherwise a named instance uses
 * the connection of that provider **named like the instance** (case-insensitive), if any; otherwise the project's binding chooses
 * the connection, as for `infisical://` references. Credentials in `@init…` are never read.
 */
object VarlockLaunch {

    /** Fails before anything is fetched when the files use refused or unsupported functions. */
    fun checkProblems(plan: VarlockPlan?) {
        if (plan != null && plan.problems.isNotEmpty()) throw VarlockLaunchException(plan.problems)
    }

    /**
     * [result] with the varlock values of [prepared] added. Blocking network I/O: never on the EDT.
     *
     * @param environment the launch's environment override for references ("Run with environment…" or `ENVBRIDGE_ENVIRONMENT`)
     * @throws VarlockLaunchException listing every value that can't be resolved
     */
    fun resolve(
        project: Project,
        prepared: EnvFileReferences.Prepared,
        result: EnvResolution.Result,
        resolver: LaunchSecretResolver,
        environment: String?,
    ): EnvResolution.Result {
        val plan = prepared.varlock ?: return result
        checkProblems(plan)
        if (plan.definitions.isEmpty()) return result.copy(warnings = result.warnings + plan.warnings)
        val fetcher = resolver.fetcher(environment)
        // The function each secret was read with, for the launch summary
        val functions = HashMap<SecretReference, String>()

        fun external(name: String): VarlockValue? {
            val value = result.environment[name] ?: return System.getenv(name)?.let { VarlockValue(it) }
            val uses = when (val source = result.sources[name]?.let { (it as? EnvResolution.Source.LocalOverride)?.replaced ?: it }) {
                is EnvResolution.Source.Reference -> setOf(SecretUse(source.reference, source.scope, value))
                is EnvResolution.Source.Scope -> setOf(SecretUse(source.reference, source.scope, value))
                else -> emptySet()
            }
            return VarlockValue(value, uses)
        }

        // @init… arguments: evaluated once per instance, with references to items but without secret functions
        val decoratorEvaluator = VarlockEvaluator(plan.definitions, ::external, plan.environment) { call, _, _ ->
            throw VarlockException(VarlockProblem(Code.SECRET_IN_DECORATOR, null, listOf(call.provider.initDecorator)))
        }
        val inits = HashMap<Pair<VarlockProvider, String>, Map<String, String>>()
        fun init(provider: VarlockProvider, instance: String): Map<String, String> = inits.getOrPut(provider to instance) {
            plan.instances[provider to instance].orEmpty().mapNotNull { (arg, expr) ->
                val value = decoratorEvaluator.evaluate(expr, null)
                // Project, environment, paths and URLs go into requests: never from a secret
                if (value.secrets.isNotEmpty()) throw VarlockException(VarlockProblem(Code.SECRET_IN_DECORATOR, null, listOf(provider.initDecorator)))
                value.text?.let { arg to it }
            }.toMap()
        }

        fun secret(call: SecretCall, args: List<String?>, named: Map<String, String?>): VarlockValue {
            val init = init(call.provider, call.instance)
            val connection = connectionFor(call.provider, call.instance, init, call.itemKey)
            val mapped = try {
                VarlockSecrets.map(call.provider, call.itemKey, args, named, init, connection?.id)
            } catch (e: IllegalArgumentException) {
                throw VarlockException(VarlockProblem(Code.INVALID_CALL, call.itemKey, listOf("${call.function}()", e.message.orEmpty())))
            }
            val reference = mapped.reference
            if (mapped.whole) {
                val entries = fetcher.entries(reference).filter { !it.valueHidden }
                if (entries.isEmpty()) return missing(call, mapped.allowMissing, fetcher.scope(reference))
                val scope = fetcher.scope(reference)
                val single = entries.singleOrNull()
                val value = if (single != null && single.key == mapped.plainKey) single.value.reveal() else json(entries.associate { it.key to it.value.reveal() })
                val uses = entries.map { SecretUse(reference.copy(key = it.key), scope, it.value.reveal()) }
                uses.forEach { functions[it.reference] = call.function }
                return VarlockValue(value, uses.toSet())
            }
            val entry = fetcher.secret(reference) ?: return missing(call, mapped.allowMissing, fetcher.scope(reference))
            functions[reference] = call.function
            return VarlockValue(entry.value.reveal(), setOf(SecretUse(reference, fetcher.scope(reference), entry.value.reveal())))
        }

        val evaluator = VarlockEvaluator(plan.definitions, ::external, plan.environment, ::secret)
        val environmentValues = LinkedHashMap(result.environment)
        val sources = LinkedHashMap(result.sources)
        val partial = LinkedHashSet(result.partialSecretValues)
        val problems = mutableListOf<VarlockLocatedProblem>()
        for (key in plan.definitions.keys) {
            val value = try {
                evaluator.value(key)
            } catch (e: VarlockException) {
                problems += VarlockLocatedProblem(e.problem.copy(key = e.problem.key ?: key), plan.locations[e.problem.key ?: key])
                continue
            }
            val text = value.text ?: continue
            environmentValues[key] = text
            if (value.secrets.isEmpty()) {
                sources[key] = EnvResolution.Source.Explicit
            } else {
                val used = value.secrets.map { functions[it.reference] ?: "ref" }.distinct()
                sources[key] = EnvResolution.Source.Varlock(used, value.secrets.map { EnvResolution.Source.Reference(it.reference, it.scope) }.distinct())
                partial += value.secrets.map { it.value }
            }
        }
        if (problems.isNotEmpty()) throw VarlockLaunchException(problems.distinct())
        val merged = result.copy(environment = environmentValues, sources = sources, warnings = result.warnings + plan.warnings, partialSecretValues = partial)
        val overrides = LocalOverrides.getInstance(project).all().filterKeys { sources[it] is EnvResolution.Source.Varlock }
        return merged.withOverrides(overrides)
    }

    private fun missing(call: SecretCall, allowMissing: Boolean, scope: cz.xprees.secrets.core.model.SecretScope): VarlockValue {
        if (allowMissing) return VarlockValue.UNDEFINED
        throw VarlockException(VarlockProblem(Code.MISSING_SECRET, call.itemKey, listOf("${call.function}()", EnvResolution.describe(scope))))
    }

    /** A JSON object of string values (Vault `raw=true`, an AWS secret read whole). */
    private fun json(values: Map<String, String>): String =
        com.google.gson.JsonObject().apply { values.forEach { (k, v) -> addProperty(k, v) } }.toString()

    /**
     * The EnvBridge connection an instance maps to (see the class comment), or null for the binding's choice.
     *
     * @throws VarlockException when the instance names a server no connection has
     */
    fun connectionFor(provider: VarlockProvider, instance: String, init: Map<String, String>, key: String?): SecretConnection? {
        val providerId = SecretProvider.byScheme(provider.scheme)?.id ?: return null
        val candidates = SecretsAppSettings.getInstance().connectionsOf(providerId)
        val server = when (provider) {
            VarlockProvider.INFISICAL -> init["siteUrl"]
            VarlockProvider.VAULT -> init["url"]
            else -> null
        }?.takeIf { it.isNotBlank() }
        if (server != null) {
            val wanted = SecretConnection.normalizeBaseUrl(server)
            return candidates.firstOrNull { wanted != null && it.baseUrl.equals(wanted, ignoreCase = true) }
                ?: throw VarlockException(VarlockProblem(Code.NO_CONNECTION, key, listOf(provider.initDecorator, server)))
        }
        if (provider == VarlockProvider.AWS) {
            init["region"]?.takeIf { it.isNotBlank() }?.let { region ->
                return candidates.firstOrNull { it.option("region")?.trim().equals(region.trim(), ignoreCase = true) }
                    ?: throw VarlockException(VarlockProblem(Code.NO_CONNECTION, key, listOf(provider.initDecorator, region)))
            }
        }
        if (instance == DEFAULT_INSTANCE) return null
        return candidates.firstOrNull { it.name.equals(instance, ignoreCase = true) }
    }

    /** The user-visible message for [problem]: names keys, functions and paths, never values. */
    fun message(problem: VarlockProblem): String {
        val key = problem.key ?: "@"
        val a = problem.args
        return when (problem.code) {
            Code.REFUSED -> SecretsBundle.message("varlock.problem.refused." + VarlockProblem.RefusalReason.valueOf(a[1]).name.lowercase(), key, a[0])
            Code.UNSUPPORTED -> SecretsBundle.message("varlock.problem.unsupported", key, a[0])
            Code.INVALID_CALL -> SecretsBundle.message("varlock.problem.invalid", key, a[0], a.getOrElse(1) { "" })
            Code.UNKNOWN_INSTANCE -> SecretsBundle.message("varlock.problem.instance", key, a[0], a[1])
            Code.CYCLE -> SecretsBundle.message("varlock.problem.cycle", a.joinToString(" → "))
            Code.NO_ENVIRONMENT -> SecretsBundle.message("varlock.problem.environment", key)
            Code.MISSING_SECRET -> SecretsBundle.message("varlock.problem.missing", key, a[0], a[1])
            Code.BULK -> SecretsBundle.message("varlock.problem.bulk", a[0])
            Code.IMPORT_OUTSIDE -> SecretsBundle.message("varlock.problem.import.outside", a[0])
            Code.IMPORT_MISSING -> SecretsBundle.message("varlock.problem.import.missing", a[0])
            Code.SECRET_IN_DECORATOR -> SecretsBundle.message("varlock.problem.decorator", a[0])
            Code.NO_CONNECTION -> SecretsBundle.message("varlock.problem.connection", key, a[0], a[1])
        }
    }
}
