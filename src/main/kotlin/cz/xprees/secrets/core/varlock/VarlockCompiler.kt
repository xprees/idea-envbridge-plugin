package cz.xprees.secrets.core.varlock

import cz.xprees.secrets.core.envspec.EnvSpecValue
import cz.xprees.secrets.core.varlock.VarlockProblem.Code
import cz.xprees.secrets.core.varlock.VarlockProblem.RefusalReason

/**
 * Turns env-spec values ([EnvSpecValue], from `EnvSpecParser`) into [VarlockExpr]s, as varlock 1.25.0 reads them:
 *
 * - quoted (except single-quoted) and unquoted strings are **expanded**: `$NAME` and `${NAME}` become `ref(NAME)`,
 *   `${NAME:-default}` / `${NAME-default}` become `fallback(ref(NAME), "default")`, text around them a `concat(…)`; `\$` isn't
 *   expanded. `$(command)` would be `exec(command)` and is refused;
 * - unquoted `true` / `false` are booleans, unquoted `undefined` is no value;
 * - functions: see [PURE], [REFUSED] and the secret functions of [VarlockProvider]; `cache(x, …)` is just `x` (EnvBridge doesn't
 *   cache values across launches); every other function is unsupported.
 *
 * Problems are collected, never thrown; they hold names, never values.
 */
class VarlockCompiler(
    /** The instance IDs each provider's `@init…(id=…)` decorators declare (the default instance without `id=` not included). */
    private val instances: Map<VarlockProvider, Set<String>> = emptyMap(),
) {
    /** The compiled value and its problems (the value is null when there are any). */
    data class Result(val expr: VarlockExpr?, val problems: List<VarlockProblem>)

    private class Failed(val problem: VarlockProblem) : RuntimeException()

    /** Compiles the value of item [key] (null: `KEY=`, undefined). */
    fun compile(value: EnvSpecValue?, key: String?): Result {
        val problems = mutableListOf<VarlockProblem>()
        val expr = try {
            value?.let { node(it, key ?: "") } ?: VarlockExpr.Undefined
        } catch (e: Failed) {
            problems += e.problem
            null
        }
        return Result(expr.takeIf { problems.isEmpty() }, problems)
    }

    private fun node(value: EnvSpecValue, key: String): VarlockExpr = when (value) {
        is EnvSpecValue.Literal -> literal(value, key)
        is EnvSpecValue.Ref -> VarlockExpr.Ref(value.name)
        is EnvSpecValue.Array -> throw Failed(VarlockProblem(Code.UNSUPPORTED, key, listOf("[…]")))
        is EnvSpecValue.Call -> call(value, key)
    }

    private fun literal(value: EnvSpecValue.Literal, key: String): VarlockExpr {
        if (value.quote == null) {
            when (value.value) {
                "true" -> return VarlockExpr.Text("true", true)
                "false" -> return VarlockExpr.Text("false", false)
                "undefined" -> return VarlockExpr.Undefined
            }
        }
        if (value.quote == "'") return VarlockExpr.Text(value.value)
        return expand(value.value, key)
    }

    /** `"a-${B}-$C"` -> `concat("a-", ref(B), "-", ref(C))`; `$(…)` is refused. */
    private fun expand(text: String, key: String): VarlockExpr {
        if ('$' !in text) return VarlockExpr.Text(text)
        val parts = mutableListOf<VarlockExpr>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) parts += VarlockExpr.Text(buffer.toString())
            buffer.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val escaped = i > 0 && text[i - 1] == '\\'
            if (c == '$' && !escaped && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '(') throw Failed(VarlockProblem(Code.REFUSED, key, listOf("$(…)", RefusalReason.COMMAND.name)))
                if (next == '{') {
                    val close = text.indexOf('}', i + 2)
                    val match = if (close > 0) BRACKETED.matchEntire(text.substring(i + 2, close)) else null
                    if (match != null) {
                        flush()
                        val ref = VarlockExpr.Ref(match.groupValues[1])
                        val default = match.groups[3]?.value
                        parts += if (default != null) VarlockExpr.Pure("fallback", listOf(ref, VarlockExpr.Text(default))) else ref
                        i = close + 1
                        continue
                    }
                } else if (next == '_' || (next.code < 128 && next.isLetter())) {
                    var end = i + 1
                    while (end < text.length && (text[end] == '_' || (text[end].code < 128 && text[end].isLetterOrDigit()))) end++
                    flush()
                    parts += VarlockExpr.Ref(text.substring(i + 1, end))
                    i = end
                    continue
                }
            }
            buffer.append(c)
            i++
        }
        flush()
        return when (parts.size) {
            0 -> VarlockExpr.Text("")
            1 -> parts.single()
            else -> VarlockExpr.Pure("concat", parts)
        }
    }

    private fun call(call: EnvSpecValue.Call, key: String): VarlockExpr {
        val name = call.name
        REFUSED[name]?.let { throw Failed(VarlockProblem(Code.REFUSED, key, listOf("$name()", it.name))) }
        VarlockProvider.ofFunction(name)?.let { return secret(it, call, key) }
        val positional = call.positional
        return when (name) {
            "ref" -> {
                val target = (positional.singleOrNull() as? EnvSpecValue.Literal)?.value
                    ?: (positional.singleOrNull() as? EnvSpecValue.Ref)?.name
                    ?: throw invalid(key, name, "expects one variable name")
                VarlockExpr.Ref(target)
            }
            // EnvBridge fetches fresh values for every launch: the cached expression is evaluated as it is
            "cache" -> node(positional.firstOrNull() ?: throw invalid(key, name, "expects a value to cache"), key)
            in PURE -> {
                val (min, max) = PURE.getValue(name)
                if (positional.size < min || positional.size > max) {
                    throw invalid(key, name, if (max == Int.MAX_VALUE) "expects at least $min arguments" else if (min == max) "expects $min arguments" else "expects $min to $max arguments")
                }
                if (call.args.any { it.key != null }) throw invalid(key, name, "takes no named arguments")
                VarlockExpr.Pure(name, positional.map { node(it, key) })
            }

            else -> throw Failed(VarlockProblem(Code.UNSUPPORTED, key, listOf("$name()")))
        }
    }

    private fun secret(provider: VarlockProvider, call: EnvSpecValue.Call, key: String): VarlockExpr {
        val name = call.name
        val positional = call.positional
        val max = if (provider == VarlockProvider.INFISICAL) 3 else 2
        if (positional.size > max) throw invalid(key, name, "expects at most $max arguments")
        if (provider == VarlockProvider.ONEPASSWORD && positional.isEmpty()) throw invalid(key, name, "expects an op:// reference")
        val first = (positional.firstOrNull() as? EnvSpecValue.Literal)?.takeIf { '$' !in it.value }?.value
        val declared = instances[provider].orEmpty()
        // The instance ID comes first when the function is given its most arguments. infisical("KEY", "/path") and
        // infisical(dev, "KEY") both have two: the first is an instance only when an @initInfisical declares it
        val instance = when {
            positional.size == max && provider != VarlockProvider.INFISICAL -> first ?: throw invalid(key, name, "expects the instance ID first")
            positional.size == 3 -> first ?: throw invalid(key, name, "expects the instance ID first")
            positional.size == 2 && provider == VarlockProvider.INFISICAL && first != null && first in declared -> first
            else -> null
        }
        if (instance != null && instance != DEFAULT_INSTANCE && instance !in declared) {
            throw Failed(VarlockProblem(Code.UNKNOWN_INSTANCE, key, listOf("$name()", instance)))
        }
        val rest = if (instance != null) positional.drop(1) else positional
        val named = call.args.filter { it.key != null }.associate { it.key!! to node(it.value, key) }
        return VarlockExpr.Secret(SecretCall(name, provider, instance ?: DEFAULT_INSTANCE, rest.map { node(it, key) }, named, key))
    }

    private fun invalid(key: String, function: String, detail: String) = Failed(VarlockProblem(Code.INVALID_CALL, key, listOf("$function()", detail)))

    companion object {
        private val BRACKETED = Regex("""([A-Za-z_][A-Za-z0-9_.]*)(:?-(.+))?""")

        /** Functions EnvBridge evaluates itself, with their positional argument counts (min, max). */
        val PURE: Map<String, Pair<Int, Int>> = mapOf(
            "concat" to (1 to Int.MAX_VALUE),
            "fallback" to (1 to Int.MAX_VALUE),
            "forEnv" to (1 to Int.MAX_VALUE),
            "eq" to (2 to 2),
            "if" to (1 to 3),
            "ifs" to (1 to Int.MAX_VALUE),
            "not" to (1 to 1),
            "and" to (1 to Int.MAX_VALUE),
            "or" to (1 to Int.MAX_VALUE),
            "isEmpty" to (1 to 1),
        )

        /** Functions EnvBridge never runs from the IDE, and why. */
        val REFUSED: Map<String, RefusalReason> = mapOf(
            "exec" to RefusalReason.COMMAND,
            "keychain" to RefusalReason.KEYCHAIN,
            "varlock" to RefusalReason.LOCAL_ENCRYPTION,
            "generateOtp" to RefusalReason.OTP,
        )
    }
}
