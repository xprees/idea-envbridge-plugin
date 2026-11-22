package cz.xprees.secrets.editor.varlock

import cz.xprees.secrets.core.envspec.EnvSpecDecorators
import cz.xprees.secrets.core.envspec.EnvSpecType

/**
 * What the varlock editor support knows about varlock 1.25.0: its functions (core and those of the 1Password, Infisical and
 * HashiCorp Vault plugins), the options of `@type` values, the values decorators take, deprecated decorators with their
 * replacements and where varlock documents each of them. Pure data; nothing here is ever evaluated.
 */
object VarlockCatalog {

    /** Where a function comes from. */
    enum class Source { CORE, PLUGIN }

    /**
     * A varlock plugin: the [initDecorator] that configures it in the header (`@initOp(…)`), the words a `@plugin(…)` package
     * name contains when it loads it, and the bundle key of its display name.
     */
    enum class Plugin(val initDecorator: String, val packageWords: List<String>, val nameKey: String) {
        ONE_PASSWORD("initOp", listOf("1password", "onepassword"), "varlock.plugin.1password"),
        INFISICAL("initInfisical", listOf("infisical"), "varlock.plugin.infisical"),
        VAULT("initHcpVault", listOf("vault", "openbao"), "varlock.plugin.vault"),
    }

    /** A function usable in values: its [name], the arguments shown in completion ([signature]) and where it comes from. */
    data class Function(val name: String, val signature: String, val source: Source, val plugin: Plugin? = null)

    /** The functions of varlock 1.25.0 and of the plugins EnvBridge knows; argument lists of `random*` aren't documented here. */
    val FUNCTIONS: List<Function> = listOf(
        Function("ref", "(KEY)", Source.CORE),
        Function("concat", "(a, b, …)", Source.CORE),
        Function("fallback", "(a, b, …, default)", Source.CORE),
        Function("remap", "(value, match, result, …, default)", Source.CORE),
        Function("ifs", "(condition, value, …, default)", Source.CORE),
        Function("if", "(condition, then, else)", Source.CORE),
        Function("eq", "(a, b)", Source.CORE),
        Function("not", "(value)", Source.CORE),
        Function("and", "(a, b, …)", Source.CORE),
        Function("or", "(a, b, …)", Source.CORE),
        Function("isEmpty", "(value)", Source.CORE),
        Function("forEnv", "(env, …)", Source.CORE),
        Function("regex", "(pattern, flags)", Source.CORE),
        Function("domainFromUrl", "(url)", Source.CORE),
        Function("randomNum", "(…)", Source.CORE),
        Function("randomUuid", "()", Source.CORE),
        Function("randomHex", "(…)", Source.CORE),
        Function("randomString", "(…)", Source.CORE),
        Function("generateOtp", "(…)", Source.CORE),
        Function("exec", "(command)", Source.CORE),
        Function("cache", "(resolver, ttl=, key=)", Source.CORE),
        Function("varlock", "(…)", Source.CORE),
        Function("keychain", "(…)", Source.CORE),
        Function("inferFromPrefix", "(PREFIX)", Source.CORE),
        Function("op", "(op://vault/item/field)", Source.PLUGIN, Plugin.ONE_PASSWORD),
        Function("opLoadEnvironment", "(id)", Source.PLUGIN, Plugin.ONE_PASSWORD),
        Function("infisical", "(key, path)", Source.PLUGIN, Plugin.INFISICAL),
        Function("infisicalBulk", "(path=, tag=)", Source.PLUGIN, Plugin.INFISICAL),
        Function("vaultSecret", "(\"path#key\")", Source.PLUGIN, Plugin.VAULT),
        Function("vaultToken", "()", Source.PLUGIN, Plugin.VAULT),
    )

    private val BY_NAME = FUNCTIONS.associateBy { it.name }

    /** The function named [name], or null. */
    fun function(name: String): Function? = BY_NAME[name]

    /** The known function closest to an unknown [name] (edit distance up to 2, or a case-only difference), or null. */
    fun functionSuggestion(name: String): String? {
        FUNCTIONS.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.name }
        return FUNCTIONS.map { it.name to distance(name.lowercase(), it.name.lowercase()) }
            .filter { (_, d) -> d <= 2 && d < name.length }
            .minByOrNull { it.second }?.first
    }

    /** The decorators plugins add to the header (`@initOp(…)`, …), for completion. */
    val PLUGIN_ROOT_DECORATORS: List<String> = Plugin.entries.map { it.initDecorator }

    /** Deprecated decorators and what replaces them. */
    val DEPRECATED_REPLACEMENTS: Map<String, String> = mapOf(
        "envFlag" to "currentEnv",
        "redactLogs" to "redact",
        "generateTypes" to "generateTsTypes",
        "docsUrl" to "docs",
    )

    /** Deprecated decorators whose replacement takes the same value, so renaming is a correct fix. */
    val RENAMEABLE_DEPRECATIONS: Set<String> = setOf("redactLogs")

    /** The type names `@type=` completes. */
    val TYPES: List<String> = EnvSpecType.CHECKED + EnvSpecType.UNCHECKED.sorted()

    /** The options of a type's call form (`string(startsWith=…)`), by type name. */
    val TYPE_OPTIONS: Map<String, List<String>> = mapOf(
        "string" to listOf("startsWith", "endsWith", "matches", "minLength", "maxLength", "isLength", "allowEmpty", "toUpperCase", "toLowerCase"),
        "number" to listOf("min", "max", "isInt", "precision", "isDivisibleBy"),
        "integer" to listOf("min", "max", "isDivisibleBy"),
        "ip" to listOf("version"),
        "uuid" to listOf("version"),
    )

    /** Decorators that are switches (`@required`, `@required=false`, `@required=forEnv(prod)`). */
    val SWITCHES: Set<String> = setOf(
        "required", "optional", "sensitive", "public", "internal", "dynamic", "static", "deprecated", "auditIgnore",
        "defaultDynamic", "disable", "redact", "preventLeaks", "encryptInjectedEnv", "disableProcessEnvInjection", "injectUndefinedAsEmpty",
    )

    /** The values completed after `@name=` (switches also get the functions, `forEnv()` among them). */
    fun decoratorValues(name: String): List<String> = when (name) {
        "defaultRequired" -> listOf("true", "false", "infer")
        "defaultSensitive" -> listOf("true", "false", "inferFromPrefix()")
        in SWITCHES -> listOf("true", "false")
        else -> emptyList()
    }

    /** Decorators written in the function style (`@import(…)`), so completion inserts `()`. */
    val FUNCTION_STYLE: Set<String> = setOf("import", "plugin", "docs", "tag", "setValuesBulk", "generateTsTypes", "auditIgnorePaths", "auditIgnoreKeys", "auditIgnoreExtraPatterns", "proxyConfig") +
        PLUGIN_ROOT_DECORATORS.toSet()

    /** Decorators that take a value (`@type=…`), so completion inserts `=`. */
    val VALUE_STYLE: Set<String> = setOf("type", "currentEnv", "defaultRequired", "defaultSensitive", "example", "icon", "placeholder", "envFlag")

    /** Whether [name] is a decorator varlock documents as a root decorator (plugin decorators included). */
    fun isRootDecorator(name: String): Boolean = EnvSpecDecorators.isRoot(name)

    /** varlock's documentation of a decorator. Anchors follow varlock's reference pages; not checked against the live site. */
    fun decoratorDocsUrl(name: String): String =
        if (isRootDecorator(name)) "$DOCS/reference/root-decorators/#${name.lowercase()}" else "$DOCS/reference/item-decorators/#${name.lowercase()}"

    /** varlock's documentation of a function. */
    fun functionDocsUrl(function: Function): String = when (function.plugin) {
        Plugin.ONE_PASSWORD -> "$DOCS/plugins/1password/"
        Plugin.INFISICAL -> "$DOCS/plugins/infisical/"
        Plugin.VAULT -> "$DOCS/plugins/hashicorp-vault/"
        null -> "$DOCS/reference/functions/#${function.name.lowercase()}"
    }

    private const val DOCS = "https://varlock.dev"

    private fun distance(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val current = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
                previous = current
            }
        }
        return row[b.length]
    }
}
