package cz.xprees.secrets.core.envspec

import java.net.URI

/**
 * A light reading of `@type=…`: the types EnvBridge checks (`string(startsWith=, endsWith=, matches=, minLength=, maxLength=,
 * isLength=)`, `enum(a, b)`, `number(min=, max=, isInt=)`, `integer`, `port`, `url`, `boolean`, `email`, `uuid`) and the other
 * types varlock knows, accepted without a check. Checks never put the value into what they return.
 */
sealed interface EnvSpecType {
    /** The type's name as written (`string`, `enum`, …). */
    val name: String

    /** Whether [value] fits; values are never part of the result. */
    fun accepts(value: String): Boolean

    /** A short description of what is expected, for messages: `port`, `enum(a, b)`, `string(startsWith=sk-)`. */
    val expected: String

    data class StringType(
        val startsWith: String? = null,
        val endsWith: String? = null,
        val matches: Regex? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val isLength: Int? = null,
        override val expected: String = "string",
    ) : EnvSpecType {
        override val name = "string"

        override fun accepts(value: String): Boolean =
            (startsWith == null || value.startsWith(startsWith)) &&
                (endsWith == null || value.endsWith(endsWith)) &&
                (matches == null || matches.containsMatchIn(value)) &&
                (minLength == null || value.length >= minLength) &&
                (maxLength == null || value.length <= maxLength) &&
                (isLength == null || value.length == isLength)
    }

    data class EnumType(val values: List<String>) : EnvSpecType {
        override val name = "enum"
        override val expected: String get() = "enum(${values.joinToString(", ")})"
        override fun accepts(value: String): Boolean = value in values
    }

    data class NumberType(val min: Double? = null, val max: Double? = null, val isInt: Boolean = false, override val name: String = "number") : EnvSpecType {
        override val expected: String get() = name
        override fun accepts(value: String): Boolean {
            val number = value.trim().toDoubleOrNull() ?: return false
            if (number.isNaN() || number.isInfinite()) return false
            if (isInt && number != Math.floor(number)) return false
            return (min == null || number >= min) && (max == null || number <= max)
        }
    }

    /** A simple check by name: `port`, `url`, `boolean`, `email`, `uuid`. */
    data class Simple(override val name: String, private val check: (String) -> Boolean) : EnvSpecType {
        override val expected: String get() = name
        override fun accepts(value: String): Boolean = check(value)
        override fun equals(other: Any?): Boolean = other is Simple && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }

    /** A type varlock knows that EnvBridge doesn't check (`ip`, `domain`, `timeZone`, …): every value is accepted. */
    data class Unchecked(override val name: String) : EnvSpecType {
        override val expected: String get() = name
        override fun accepts(value: String): Boolean = true
    }

    /** The result of reading a `@type` value: the type, or why it can't be used. */
    sealed interface Parsed {
        data class Ok(val type: EnvSpecType) : Parsed

        /** An unknown type name. */
        data class Unknown(val name: String) : Parsed

        /** A known type with a bad option ([detail] names the option, never a value of an item). */
        data class Invalid(val name: String, val detail: String) : Parsed
    }

    companion object {
        private val BOOLEANS = setOf("true", "false", "1", "0", "yes", "no", "on", "off")
        private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
        private val UUID = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

        /** Types varlock documents that EnvBridge accepts without checking. */
        val UNCHECKED = setOf("ip", "domain", "timeZone", "md5", "opServiceAccountToken", "simple-object", "object", "semver", "isoDate", "hex")

        /** The type names EnvBridge checks. */
        val CHECKED = listOf("string", "enum", "number", "integer", "port", "url", "boolean", "email", "uuid")

        private val SIMPLE: Map<String, (String) -> Boolean> = mapOf(
            "port" to { v -> v.trim().toIntOrNull()?.let { it in 0..65535 } == true },
            "url" to { v -> isUrl(v.trim()) },
            "boolean" to { v -> v.trim().lowercase() in BOOLEANS },
            "email" to { v -> EMAIL.matches(v.trim()) },
            "uuid" to { v -> UUID.matches(v.trim()) },
        )

        private fun isUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme != null && uri.scheme.matches(Regex("[A-Za-z][A-Za-z0-9+.\\-]*")) &&
                (uri.host != null || uri.scheme.equals("file", ignoreCase = true) || uri.authority != null)
        }

        /** Reads a `@type` decorator's value (`port`, `enum(a, b)`, `string(startsWith=sk-)`). */
        fun parse(value: EnvSpecValue): Parsed {
            val (name, args) = when (value) {
                is EnvSpecValue.Literal -> value.value to emptyList()
                is EnvSpecValue.Call -> value.name to value.args
                else -> return Parsed.Unknown(value.text)
            }
            return when (name) {
                "string" -> string(args, value.text)
                "enum" -> {
                    val values = args.map { arg -> literalText(arg.value) ?: return Parsed.Invalid(name, arg.text) }
                    if (values.isEmpty() || args.any { it.key != null }) Parsed.Invalid(name, "values") else Parsed.Ok(EnumType(values))
                }

                "number", "integer" -> {
                    var min: Double? = null
                    var max: Double? = null
                    var isInt = name == "integer"
                    for (arg in args) {
                        val text = literalText(arg.value) ?: return Parsed.Invalid(name, arg.text)
                        when (arg.key) {
                            "min" -> min = text.toDoubleOrNull() ?: return Parsed.Invalid(name, "min")
                            "max" -> max = text.toDoubleOrNull() ?: return Parsed.Invalid(name, "max")
                            "isInt" -> isInt = text.toBooleanStrictOrNull() ?: return Parsed.Invalid(name, "isInt")
                            "precision", "isDivisibleBy" -> Unit
                            else -> return Parsed.Invalid(name, arg.key ?: arg.text)
                        }
                    }
                    Parsed.Ok(NumberType(min, max, isInt, name))
                }

                in SIMPLE -> Parsed.Ok(Simple(name, SIMPLE.getValue(name)))
                in UNCHECKED -> Parsed.Ok(Unchecked(name))
                else -> Parsed.Unknown(name)
            }
        }

        private fun string(args: List<EnvSpecArg>, text: String): Parsed {
            var type = StringType(expected = text)
            for (arg in args) {
                val key = arg.key ?: return Parsed.Invalid("string", arg.text)
                fun intArg(): Int? = literalText(arg.value)?.toIntOrNull()?.takeIf { it >= 0 }
                type = when (key) {
                    "startsWith" -> type.copy(startsWith = literalText(arg.value) ?: return Parsed.Invalid("string", key))
                    "endsWith" -> type.copy(endsWith = literalText(arg.value) ?: return Parsed.Invalid("string", key))
                    "matches" -> type.copy(matches = regex(arg.value) ?: return Parsed.Invalid("string", key))
                    "minLength" -> type.copy(minLength = intArg() ?: return Parsed.Invalid("string", key))
                    "maxLength" -> type.copy(maxLength = intArg() ?: return Parsed.Invalid("string", key))
                    "isLength" -> type.copy(isLength = intArg() ?: return Parsed.Invalid("string", key))
                    "allowEmpty", "toUpperCase", "toLowerCase" -> type
                    else -> return Parsed.Invalid("string", key)
                }
            }
            return Parsed.Ok(type)
        }

        /** `regex("^sk-")`, `regex(^sk-, i)`, `"^sk-"`, or the older bare `/^sk-/i`. */
        private fun regex(value: EnvSpecValue): Regex? {
            val (pattern, flags) = when {
                value is EnvSpecValue.Call && value.name == "regex" -> {
                    val positional = value.positional.map { literalText(it) ?: return null }
                    (positional.firstOrNull() ?: return null) to positional.getOrNull(1).orEmpty()
                }

                value is EnvSpecValue.Literal && value.quote == null && value.value.length > 1 && value.value.startsWith('/') -> {
                    val last = value.value.lastIndexOf('/')
                    if (last <= 0) return null
                    value.value.substring(1, last) to value.value.substring(last + 1)
                }

                value is EnvSpecValue.Literal -> value.value to ""
                else -> return null
            }
            val options = buildSet {
                if ('i' in flags) add(RegexOption.IGNORE_CASE)
                if ('m' in flags) add(RegexOption.MULTILINE)
                if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            }
            return runCatching { Regex(pattern, options) }.getOrNull()
        }

        private fun literalText(value: EnvSpecValue): String? = (value as? EnvSpecValue.Literal)?.value
    }
}
