package cz.xprees.secrets.core.envspec

import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Code
import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Severity

/**
 * The decorators of env-spec as of varlock 1.25.0, and which of them EnvBridge acts on by itself ([NATIVE]). The others are
 * recognised (no "unknown decorator" warning) and left alone.
 */
object EnvSpecDecorators {
    /** Root decorators (header only). */
    val ROOT: Set<String> = setOf(
        "currentEnv", "envFlag", "defaultRequired", "defaultSensitive", "defaultDynamic", "disable", "import", "setValuesBulk",
        "plugin", "cache", "redact", "redactLogs", "preventLeaks", "encryptInjectedEnv", "disableProcessEnvInjection",
        "injectUndefinedAsEmpty", "auditIgnorePaths", "auditIgnoreKeys", "auditIgnoreExtraPatterns", "proxyConfig",
        "generateTsTypes", "generateTypes",
    )

    /** Item decorators. */
    val ITEM: Set<String> = setOf(
        "required", "optional", "sensitive", "public", "internal", "dynamic", "static", "type", "example", "docs", "tag", "icon",
        "deprecated", "docsUrl", "auditIgnore", "placeholder", "proxy",
    )

    /** Decorators varlock deprecated (still recognised). */
    val DEPRECATED: Set<String> = setOf("envFlag", "redactLogs", "generateTypes", "docsUrl")

    /**
     * What EnvBridge does by itself, without the varlock compatibility mode; a file using any of them changes what launches do
     * (see `EnvSpecLayers`).
     */
    val NATIVE: Set<String> = setOf("required", "optional", "sensitive", "public", "internal", "type", "defaultRequired", "defaultSensitive")

    /** Every known name, for completion: item decorators first. */
    val ALL: List<String> = ITEM.sorted() + ROOT.sorted()

    /** Root decorators that plugins add: `@initOp(…)`, `@initInfisical(…)`, `@generateDotEnvExample(…)`, … */
    fun isPluginRoot(name: String): Boolean =
        (name.startsWith("init") && name.length > 4 && name[4].isUpperCase()) ||
            (name.startsWith("generate") && name.length > 8 && name[8].isUpperCase())

    /** Whether [name] is a known root decorator (plugin decorators included). */
    fun isRoot(name: String): Boolean = name in ROOT || isPluginRoot(name)

    /** Whether [name] is known at all. */
    fun isKnown(name: String): Boolean = name in ITEM || isRoot(name)

    /** The known name closest to an unknown [name] (edit distance up to 2, or a case-only difference), or null. */
    fun suggestion(name: String): String? {
        val candidates = ITEM + ROOT
        candidates.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        return candidates.map { it to distance(name.lowercase(), it.lowercase()) }
            .filter { (_, d) -> d <= 2 && d < name.length }
            .minByOrNull { it.second }?.first
    }

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

/**
 * Checks the meaning of a parsed file (the syntax is checked by [EnvSpecParser]): unknown or misplaced decorators, repeated
 * ones, `@required` with `@optional`, `@sensitive` with `@public`, root defaults EnvBridge can't read, `@type` values and
 * literal values that don't fit their `@type`. Only files that use decorators get these checks, so ordinary `.env` files with
 * `# @todo`-like comments stay quiet. Values never appear in the diagnostics.
 */
object EnvSpecValidation {

    fun check(file: EnvSpecFile): List<EnvSpecDiagnostic> {
        if (!file.usesDecorators) return emptyList()
        val result = mutableListOf<EnvSpecDiagnostic>()
        fun known(decorator: EnvSpecDecorator): Boolean {
            if (EnvSpecDecorators.isKnown(decorator.name)) return true
            result += EnvSpecDiagnostic(
                Code.UNKNOWN_DECORATOR, Severity.WARNING, decorator.range, listOf(decorator.name, EnvSpecDecorators.suggestion(decorator.name).orEmpty()),
            )
            return false
        }
        for (decorator in file.rootDecorators) {
            if (!known(decorator)) continue
            if (decorator.name in EnvSpecDecorators.ITEM) result += misplaced(decorator)
        }
        duplicates(file.rootDecorators, result)
        file.rootDecorator("defaultRequired")?.let { decorator ->
            val ok = decorator.booleanValue != null || (decorator.value as? EnvSpecValue.Literal)?.let { it.quote == null && it.value == "infer" } == true
            if (!ok) result += invalidValue(decorator)
        }
        file.rootDecorator("defaultSensitive")?.let { decorator ->
            val call = decorator.value as? EnvSpecValue.Call
            val ok = decorator.booleanValue != null ||
                (call != null && call.name == "inferFromPrefix" && call.positional.singleOrNull() is EnvSpecValue.Literal)
            if (!ok) result += invalidValue(decorator)
        }
        for (item in file.items) {
            for (decorator in item.decorators) {
                if (!known(decorator)) continue
                if (EnvSpecDecorators.isRoot(decorator.name)) result += misplaced(decorator)
            }
            duplicates(item.decorators, result)
            conflict(item, "required", "optional", result)
            conflict(item, "sensitive", "public", result)
            item.decorator("type")?.let { result += typeDiagnostics(item, it) }
        }
        return result
    }

    private fun misplaced(decorator: EnvSpecDecorator) = EnvSpecDiagnostic(Code.MISPLACED_DECORATOR, Severity.WARNING, decorator.range, listOf(decorator.name))

    private fun invalidValue(decorator: EnvSpecDecorator) =
        EnvSpecDiagnostic(Code.INVALID_DECORATOR_VALUE, Severity.WARNING, decorator.range, listOf(decorator.name, decorator.valueText.orEmpty()))

    private fun duplicates(decorators: List<EnvSpecDecorator>, result: MutableList<EnvSpecDiagnostic>) {
        // Function-style decorators may repeat (`@import(…)` several times, `@plugin(…)`)
        decorators.filter { it.args == null }.groupBy { it.name }.values.filter { it.size > 1 }.forEach { repeated ->
            repeated.drop(1).forEach { result += EnvSpecDiagnostic(Code.DUPLICATE_DECORATOR, Severity.WARNING, it.range, listOf(it.name)) }
        }
    }

    /** Both [a] and [b] switched on (a bare decorator or `=true`): reported on the second. */
    private fun conflict(item: EnvSpecItem, a: String, b: String, result: MutableList<EnvSpecDiagnostic>) {
        val first = item.decorator(a)?.takeIf { it.booleanValue == true } ?: return
        val second = item.decorator(b)?.takeIf { it.booleanValue == true } ?: return
        val later = if (second.range.first > first.range.first) second else first
        result += EnvSpecDiagnostic(Code.CONFLICTING_DECORATORS, Severity.WARNING, later.range, listOf(a, b))
    }

    private fun typeDiagnostics(item: EnvSpecItem, decorator: EnvSpecDecorator): List<EnvSpecDiagnostic> {
        val value = decorator.value ?: return listOf(invalidValue(decorator))
        return when (val parsed = EnvSpecType.parse(value)) {
            is EnvSpecType.Parsed.Unknown -> listOf(EnvSpecDiagnostic(Code.UNKNOWN_TYPE, Severity.WARNING, value.range, listOf(parsed.name)))
            is EnvSpecType.Parsed.Invalid -> listOf(EnvSpecDiagnostic(Code.INVALID_TYPE_ARGUMENT, Severity.WARNING, value.range, listOf(parsed.name, parsed.detail)))
            is EnvSpecType.Parsed.Ok -> {
                val literal = item.value as? EnvSpecValue.Literal
                // Only static values; an empty string isn't checked (that's for @required)
                if (literal == null || literal.value.isEmpty() || parsed.type.accepts(literal.value)) {
                    emptyList()
                } else {
                    listOf(EnvSpecDiagnostic(Code.TYPE_MISMATCH, Severity.WARNING, literal.range, listOf(item.key, parsed.type.expected)))
                }
            }
        }
    }
}
