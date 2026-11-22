package cz.xprees.secrets.core.envspec

/**
 * The parsed form of a `.env`-style file with env-spec decorator comments (the format of varlock's `.env.schema`, see
 * [EnvSpecParser]). Offsets in ranges are indexes into the text that was parsed (end exclusive). Pure data: no IntelliJ
 * dependencies, nothing evaluated (function calls stay calls, references stay references).
 */
data class EnvSpecFile(
    /** The header (the comment block at the top ended by a `# ---` / `# ===` divider) with the root decorators, or null. */
    val header: EnvSpecHeader?,
    /** The items (`KEY=value` lines) in file order; a key may occur more than once (the last one wins, as in dotenv). */
    val items: List<EnvSpecItem>,
    /** Decorator comments that belong to neither the header nor an item (a comment block followed by a blank line). */
    val detachedDecorators: List<EnvSpecDecorator>,
    /** Syntax problems, never thrown. */
    val diagnostics: List<EnvSpecDiagnostic>,
) {
    /** The root decorators (those of the header). */
    val rootDecorators: List<EnvSpecDecorator> get() = header?.decorators.orEmpty()

    /** The last item named [key], or null. */
    fun item(key: String): EnvSpecItem? = items.lastOrNull { it.key == key }

    /** Every decorator of the file: root, item and detached ones. */
    val allDecorators: List<EnvSpecDecorator> get() = rootDecorators + items.flatMap { it.decorators } + detachedDecorators

    /** Whether the file uses decorator comments at all. */
    val usesDecorators: Boolean get() = allDecorators.isNotEmpty()

    /** The root decorator named [name] (the last one when repeated), or null. */
    fun rootDecorator(name: String): EnvSpecDecorator? = rootDecorators.lastOrNull { it.name == name }

    companion object {
        val EMPTY = EnvSpecFile(null, emptyList(), emptyList(), emptyList())
    }
}

/** The document header: its comment lines (without `#`, decorator lines included) and the root decorators. */
data class EnvSpecHeader(val decorators: List<EnvSpecDecorator>, val comments: List<String>, val range: IntRange)

/** One `KEY=value` line (or several, for a multi-line value). */
data class EnvSpecItem(
    val key: String,
    /** Where the key is. */
    val keyRange: IntRange,
    /**
     * The value, or null when nothing follows `=` (`KEY=` is *undefined* in env-spec; `KEY=""` is an empty string, a quoted
     * [EnvSpecValue.Literal]). Unquoted `name(args)` is a [EnvSpecValue.Call], a lone `$NAME` / `${NAME}` a [EnvSpecValue.Ref].
     */
    val value: EnvSpecValue?,
    /** The decorators of the comment block above the item, then those after the value on its line. */
    val decorators: List<EnvSpecDecorator>,
    /** The plain comment lines of the block above the item (without `#`), its description. */
    val comments: List<String>,
    /** The plain comment after the value (without `#`, trimmed), e.g. an EnvBridge binding `infisical://KEY`; null when none. */
    val trailingComment: String?,
    /** Zero-based line of the key. */
    val line: Int,
    /** From the key to the end of the value (a multi-line value included) and its comment. */
    val range: IntRange,
) {
    /** The decorator named [name] (the last one when repeated), or null. */
    fun decorator(name: String): EnvSpecDecorator? = decorators.lastOrNull { it.name == name }

    /** Whether the item has a decorator named [name]. */
    fun has(name: String): Boolean = decorators.any { it.name == name }

    /**
     * The value as a launch would see it when it is a static literal: the unquoted or unescaped text; null for an undefined
     * value, a function call or a reference.
     */
    val literalValue: String? get() = (value as? EnvSpecValue.Literal)?.value
}

/**
 * One decorator: `@name` (meaning `@name=true`), `@name=value`, or the function style `@name(args)` (`@docs(https://…)`,
 * `@import(./.env.other, pick=[KEY])`).
 */
data class EnvSpecDecorator(
    val name: String,
    /** The value after `=`, or null for a bare `@name` and the function style. */
    val value: EnvSpecValue?,
    /** The arguments of the function style, or null for the other forms. */
    val args: List<EnvSpecArg>?,
    /** From `@` to the end of the decorator. */
    val range: IntRange,
    /** The name without `@`. */
    val nameRange: IntRange,
) {
    /** `@name`, `@name=true` -> true; `@name=false` -> false; null for any other value (a function call, a reference, text). */
    val booleanValue: Boolean?
        get() = when {
            value == null && args == null -> true
            value is EnvSpecValue.Literal && value.quote == null -> when (value.value) {
                "true" -> true
                "false" -> false
                else -> null
            }

            else -> null
        }

    /** The decorator's value as written (after `=`, or the parenthesised arguments of the function style), or null for a bare one. */
    val valueText: String? get() = value?.text ?: args?.let { list -> list.joinToString(", ") { it.text } }
}

/** An argument of a function call: positional (`key == null`) or `key=value`. */
data class EnvSpecArg(val key: String?, val value: EnvSpecValue, val range: IntRange, val text: String)

/** A value in a decorator or an item: text, a reference, an array or a function call. Never evaluated. */
sealed interface EnvSpecValue {
    /** The value as written. */
    val text: String

    /** Where [text] is. */
    val range: IntRange

    /**
     * A string: unquoted text or a quoted string (`"…"`, `'…'`, `` `…` ``, `"""…"""`, triple backticks). [value] is the
     * content without quotes, with the escapes of double-quoted strings (`\n`, `\r`, `\t`, `\"`, `\\`) applied.
     */
    data class Literal(val value: String, val quote: String?, override val text: String, override val range: IntRange) : EnvSpecValue

    /** `$NAME` or `${NAME}` (the same as `ref(NAME)`). */
    data class Ref(val name: String, override val text: String, override val range: IntRange) : EnvSpecValue

    /** `[a, b]`. */
    data class Array(val items: List<EnvSpecValue>, override val text: String, override val range: IntRange) : EnvSpecValue

    /** `name(args)`, e.g. `string(startsWith=sk-)`, `enum(a, b)`, `op(op://vault/item/field)`, `forEnv(prod)`. */
    data class Call(val name: String, val args: List<EnvSpecArg>, override val text: String, override val range: IntRange) : EnvSpecValue {
        /** The positional arguments. */
        val positional: List<EnvSpecValue> get() = args.filter { it.key == null }.map { it.value }

        /** The value of the named argument [key], or null. */
        fun named(key: String): EnvSpecValue? = args.lastOrNull { it.key == key }?.value
    }
}

/** A problem found while parsing or checking, with where it is. [args] never contain item values. */
data class EnvSpecDiagnostic(val code: Code, val severity: Severity, val range: IntRange, val args: List<String> = emptyList()) {
    enum class Severity { ERROR, WARNING }

    enum class Code {
        // Syntax (EnvSpecParser)
        /** A quoted string without its closing quote. */
        UNTERMINATED_QUOTE,

        /** A function call or the function style of a decorator without `)`. */
        UNCLOSED_PARENTHESIS,

        /** An array without `]`. */
        UNCLOSED_BRACKET,

        /** `@` not followed by a decorator name. */
        INVALID_DECORATOR,

        /** Text in a decorator comment that isn't a decorator (args: the text). */
        UNEXPECTED_TEXT,

        /** A line that is neither blank, a comment nor `KEY=value`. */
        INVALID_LINE,

        // Meaning (EnvSpecValidation)
        /** A decorator EnvBridge doesn't know (args: name, the closest known name or ""). */
        UNKNOWN_DECORATOR,

        /** A root decorator on an item, or an item decorator in the header (args: name). */
        MISPLACED_DECORATOR,

        /** The same decorator twice on one item or in the header (args: name). */
        DUPLICATE_DECORATOR,

        /** Two decorators that contradict each other (args: first, second), e.g. `@required @optional`. */
        CONFLICTING_DECORATORS,

        /** A decorator value EnvBridge can't use (args: name, the value as written). */
        INVALID_DECORATOR_VALUE,

        /** An unknown `@type` (args: the type name). */
        UNKNOWN_TYPE,

        /** An option of a known `@type` that is missing, unknown or malformed (args: type, detail). */
        INVALID_TYPE_ARGUMENT,

        /** A literal value that doesn't fit the item's `@type` (args: key, expected type). */
        TYPE_MISMATCH,

        /** Decorators that belong to no item: a blank line separates them from the next one. */
        DETACHED_DECORATORS,
    }
}
