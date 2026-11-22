package cz.xprees.secrets.core.varlock

import cz.xprees.secrets.core.varlock.VarlockProblem.Code

/**
 * Evaluates compiled varlock values ([VarlockExpr]) the way varlock 1.25.0 does: `concat` joins (undefined as ""),
 * `fallback` returns its first argument that is neither undefined nor "", `forEnv(a, b)` is whether the current environment is
 * one of them, `eq` compares, `if(cond, then?, else?)`, `ifs(cond, value, …, default?)`, `not`, `and`, `or` (short-circuit) and
 * `isEmpty` (undefined or ""). `ref(NAME)` reads [definitions] first (evaluated once, lazily, cycles reported), then
 * [external]. Secret functions go to [secret] with their arguments evaluated (arguments made from secrets are refused: they
 * would end up in request URLs and messages). Every value carries the secrets it was made from.
 *
 * Pure: no I/O of its own. Problems are thrown as [VarlockException]s, without values.
 */
class VarlockEvaluator(
    /** The items whose values are varlock expressions. */
    private val definitions: Map<String, VarlockExpr>,
    /** Values from elsewhere (the run configuration, plain file values, resolved references), or null. */
    private val external: (String) -> VarlockValue?,
    /** The current environment for `forEnv(…)`, or null. */
    private val currentEnvironment: String?,
    /** Resolves a secret function: the call and its evaluated arguments. */
    private val secret: (SecretCall, List<String?>, Map<String, String?>) -> VarlockValue,
) {
    private val values = HashMap<String, VarlockValue>()
    private val evaluating = LinkedHashSet<String>()

    /** The value of item [key] (a definition, else [external], else undefined). */
    fun value(key: String): VarlockValue {
        values[key]?.let { return it }
        val expr = definitions[key] ?: return external(key) ?: VarlockValue.UNDEFINED
        if (!evaluating.add(key)) throw VarlockException(VarlockProblem(Code.CYCLE, key, evaluating.dropWhile { it != key } + key))
        try {
            return evaluate(expr, key).also { values[key] = it }
        } finally {
            evaluating.remove(key)
        }
    }

    /** Evaluates [expr] as the value of item [key] (for an expression that isn't an item, such as a decorator argument). */
    fun evaluate(expr: VarlockExpr, key: String?): VarlockValue = when (expr) {
        is VarlockExpr.Text -> VarlockValue(expr.bool ?: expr.value)
        VarlockExpr.Undefined -> VarlockValue.UNDEFINED
        is VarlockExpr.Ref -> value(expr.name)
        is VarlockExpr.Pure -> pure(expr, key)
        is VarlockExpr.Secret -> {
            val positional = expr.call.positional.map { evaluate(it, key) }
            val named = expr.call.named.mapValues { (_, v) -> evaluate(v, key) }
            // Arguments become part of requests (paths in URLs) and of messages: they may not hold secret values
            if (positional.any { it.secrets.isNotEmpty() } || named.values.any { it.secrets.isNotEmpty() }) {
                throw VarlockException(VarlockProblem(Code.INVALID_CALL, key, listOf("${expr.call.function}()", "can't take arguments made from secrets")))
            }
            secret(expr.call, positional.map { it.text }, named.mapValues { it.value.text })
        }
    }

    private fun pure(expr: VarlockExpr.Pure, key: String?): VarlockValue {
        val args = expr.args
        val used = LinkedHashSet<SecretUse>()
        fun arg(i: Int): VarlockValue = evaluate(args[i], key).also { used += it.secrets }
        fun result(value: Any?) = VarlockValue(value, used)
        return when (expr.function) {
            "concat" -> result(args.indices.joinToString("") { arg(it).text.orEmpty() })
            "fallback" -> {
                for (i in args.indices) {
                    val v = arg(i)
                    if (v.value != null && v.value != "") return result(v.value)
                }
                result(null)
            }

            "forEnv" -> {
                val environment = currentEnvironment ?: throw VarlockException(VarlockProblem(Code.NO_ENVIRONMENT, key))
                result(args.indices.any { arg(it).text == environment })
            }

            "eq" -> result(arg(0).value == arg(1).value)
            "if" -> when {
                arg(0).truthy -> result(if (args.size > 1) arg(1).value else true)
                args.size > 2 -> result(arg(2).value)
                args.size == 2 -> result(null)
                else -> result(false)
            }

            "ifs" -> {
                var i = 0
                while (i + 1 < args.size) {
                    if (arg(i).truthy) return result(arg(i + 1).value)
                    i += 2
                }
                result(if (args.size % 2 == 1) arg(args.size - 1).value else null)
            }

            "not" -> result(!arg(0).truthy)
            "and" -> result(args.indices.all { arg(it).truthy })
            "or" -> result(args.indices.any { arg(it).truthy })
            "isEmpty" -> arg(0).let { result(it.value == null || it.value == "") }
            else -> throw VarlockException(VarlockProblem(Code.UNSUPPORTED, key, listOf("${expr.function}()")))
        }
    }
}
