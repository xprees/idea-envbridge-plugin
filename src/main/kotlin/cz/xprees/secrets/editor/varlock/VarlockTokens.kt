package cz.xprees.secrets.editor.varlock

import com.intellij.openapi.util.TextRange
import cz.xprees.secrets.core.envspec.EnvSpecArg
import cz.xprees.secrets.core.envspec.EnvSpecDecorator
import cz.xprees.secrets.core.envspec.EnvSpecFile
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecValue

/**
 * The pieces of a parsed env-spec file the varlock editor support highlights, documents, checks and navigates from: decorators,
 * function calls, `$KEY` / `${KEY}` / `ref(KEY)` references, `@type` names and options, argument names, keywords, quoted
 * strings in decorators, `@import` paths, keys and (for files no language highlights) comments. Read from the parser's tree;
 * nothing is evaluated.
 */
object VarlockTokens {

    /** What a token is. */
    enum class Kind { DECORATOR, FUNCTION, REFERENCE, TYPE, ARGUMENT, KEYWORD, STRING, IMPORT, KEY, COMMENT }

    /**
     * One token: [range] in the file's text, [name] (the decorator, function, type or key name; the path of an import), the
     * [call] for a function, and whether it is inside the header ([root]) for decorators.
     */
    data class Token(val kind: Kind, val range: TextRange, val name: String, val call: EnvSpecValue.Call? = null, val root: Boolean = false)

    private val KEYWORDS = setOf("true", "false", "undefined", "infer")
    private val BRACED_REFERENCE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")

    /** The tokens of [file] (parsed from [text]), in no particular order. */
    fun scan(file: EnvSpecFile, text: CharSequence): List<Token> {
        val tokens = mutableListOf<Token>()
        val scanner = Scanner(tokens)
        file.rootDecorators.forEach { scanner.decorator(it, root = true) }
        file.detachedDecorators.forEach { scanner.decorator(it, root = false) }
        for (item in file.items) {
            tokens += Token(Kind.KEY, TextRange(item.keyRange.first, item.keyRange.last + 1), item.key)
            item.value?.let { scanner.value(it, inDecorator = false) }
            item.decorators.forEach { scanner.decorator(it, root = false) }
        }
        tokens += comments(text)
        return tokens
    }

    /** The innermost token at [offset] of the given [kinds], or null. */
    fun at(tokens: List<Token>, offset: Int, kinds: Set<Kind>): Token? =
        tokens.filter { it.kind in kinds && (it.range.containsOffset(offset)) }.minByOrNull { it.range.length }

    /** Full comment lines without decorators, and the `#` of decorator lines (only shown where no language highlights them). */
    private fun comments(text: CharSequence): List<Token> {
        val result = mutableListOf<Token>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var p = start
            while (p < newline && (text[p] == ' ' || text[p] == '\t')) p++
            if (p < newline && text[p] == '#') {
                val end = if (newline > p && text[newline - 1] == '\r') newline - 1 else newline
                val body = text.subSequence(p + 1, end).toString()
                val range = if (body.trimStart().startsWith('@') && !EnvSpecParser.isDivider(body)) TextRange(p, p + 1) else TextRange(p, end)
                if (!range.isEmpty) result += Token(Kind.COMMENT, range, "#")
            }
            if (newline >= text.length) break
            start = newline + 1
        }
        return result
    }

    private class Scanner(val tokens: MutableList<Token>) {
        fun decorator(decorator: EnvSpecDecorator, root: Boolean) {
            tokens += Token(Kind.DECORATOR, TextRange(decorator.range.first, decorator.nameRange.last + 1), decorator.name, root = root)
            when (decorator.name) {
                "type" -> decorator.value?.let(::type)
                "import" -> decorator.args?.forEachIndexed { index, arg ->
                    val literal = arg.value as? EnvSpecValue.Literal
                    if (index == 0 && arg.key == null && literal != null) {
                        tokens += Token(Kind.IMPORT, range(literal), literal.value)
                    } else {
                        argument(arg, inDecorator = true)
                    }
                }

                else -> {
                    decorator.value?.let { value(it, inDecorator = true) }
                    decorator.args?.forEach { argument(it, inDecorator = true) }
                }
            }
        }

        /** `@type=port`, `@type=string(startsWith=sk-, matches=regex(…))`. */
        private fun type(value: EnvSpecValue) {
            when (value) {
                is EnvSpecValue.Literal -> if (value.quote == null && value.value.isNotEmpty()) tokens += Token(Kind.TYPE, range(value), value.value)
                is EnvSpecValue.Call -> {
                    tokens += Token(Kind.TYPE, TextRange(value.range.first, value.range.first + value.name.length), value.name)
                    value.args.forEach { argument(it, inDecorator = true) }
                }

                else -> value(value, inDecorator = true)
            }
        }

        fun argument(arg: EnvSpecArg, inDecorator: Boolean) {
            if (arg.key != null) tokens += Token(Kind.ARGUMENT, TextRange(arg.range.first, arg.range.first + arg.key.length), arg.key)
            value(arg.value, inDecorator)
        }

        fun value(value: EnvSpecValue, inDecorator: Boolean) {
            when (value) {
                is EnvSpecValue.Call -> {
                    tokens += Token(Kind.FUNCTION, TextRange(value.range.first, value.range.first + value.name.length), value.name, value)
                    val referenced = if (value.name == "ref") value.args.firstOrNull { it.key == null } else null
                    val literal = referenced?.value as? EnvSpecValue.Literal
                    if (literal != null && literal.value.isNotEmpty()) tokens += Token(Kind.REFERENCE, range(literal), literal.value)
                    value.args.filter { it !== referenced || literal == null }.forEach { argument(it, inDecorator) }
                }

                is EnvSpecValue.Ref -> tokens += Token(Kind.REFERENCE, range(value), value.name)
                is EnvSpecValue.Array -> value.items.forEach { value(it, inDecorator) }
                is EnvSpecValue.Literal -> {
                    if (inDecorator && value.quote == null && value.value in KEYWORDS) tokens += Token(Kind.KEYWORD, range(value), value.value)
                    if (inDecorator && value.quote != null) tokens += Token(Kind.STRING, range(value), value.value)
                    // `${KEY}` inside text expands like ref(KEY), except in single quotes
                    if (value.quote != "'") {
                        BRACED_REFERENCE.findAll(value.text).forEach { match ->
                            tokens += Token(
                                Kind.REFERENCE,
                                TextRange(value.range.first + match.range.first, value.range.first + match.range.last + 1),
                                match.groupValues[1],
                            )
                        }
                    }
                }
            }
        }

        private fun range(value: EnvSpecValue) = TextRange(value.range.first, value.range.last + 1)
    }
}
