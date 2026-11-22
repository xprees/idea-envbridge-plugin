package cz.xprees.secrets.core.envspec

import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Code
import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Severity

/**
 * Parses `.env`-style files with env-spec decorator comments (the format of varlock's `.env.schema`, RFC "env-spec", as of
 * varlock 1.25.0) into an [EnvSpecFile]:
 *
 * - `#` starts a comment; a comment whose text starts with `@` is a **decorator comment** holding one or more decorators
 *   (`# @required @sensitive @type=string(startsWith=sk-)`), optionally followed by `# a plain comment`;
 * - a **comment block** is consecutive comment lines; it ends at a blank line, a divider (`# ---`, `# ===`), an item or the end;
 * - the **header** is the comment text at the top of the file ended by a divider; its decorators are the root decorators
 *   (`@defaultRequired=infer`, `@defaultSensitive=false`, `@currentEnv=$APP_ENV`, …);
 * - an **item** (`KEY=value`, optionally `export KEY=value`) gets the decorators of the comment block directly above it and those
 *   after its value (`ITEM=foo # @required`);
 * - decorators: `@name` is `@name=true`; values are unquoted text, quoted strings, `$REF` / `${REF}`, arrays `[a, b]` and
 *   function calls `name(arg, key=value)`, nested as needed (`@type=string(matches=regex("^sk-"))`); the function style
 *   `@docs(https://…)` takes arguments directly;
 * - item values: unquoted (up to ` #`), quoted (`'…'`, `` `…` ``, and `"…"` with escapes, which may span lines), `"""…"""` and
 *   triple backticks for multi-line text, function calls (`op(op://vault/item/field)`) and references. `KEY=` is undefined,
 *   `KEY=""` an empty string. CRLF and LF line ends.
 *
 * Never throws: problems are returned as [EnvSpecFile.diagnostics] with their offsets, and parsing goes on with the next line.
 * Nothing is evaluated.
 */
object EnvSpecParser {
    private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")
    private val DIVIDER = Regex("""^(?:-{3,}|={3,})""")

    /** Parses [text]. */
    fun parse(text: String): EnvSpecFile = Parser(text).run()

    /** Whether [commentText] (a comment's text after `#`) is a divider: `---` or `===` (three or more). */
    fun isDivider(commentText: String): Boolean = DIVIDER.containsMatchIn(commentText.trim())

    private class CommentLine(val text: String, val decorators: List<EnvSpecDecorator>, val start: Int) {
        val isDecoratorLine: Boolean get() = text.trimStart().startsWith('@')
    }

    private class Parser(private val s: String) {
        private val diagnostics = mutableListOf<EnvSpecDiagnostic>()
        private val items = mutableListOf<EnvSpecItem>()
        private val detached = mutableListOf<EnvSpecDecorator>()
        private var header: EnvSpecHeader? = null

        /** The comment block being collected (since the last blank line, divider or item). */
        private val block = mutableListOf<CommentLine>()

        /** Comment lines before the first item: they become the header when a divider follows. */
        private val top = mutableListOf<CommentLine>()

        /** Blocks before the first item that a blank line ended: detached, unless a divider makes them the header. */
        private val topDetached = mutableListOf<EnvSpecDecorator>()
        private var seenItem = false

        fun run(): EnvSpecFile {
            var pos = 0
            var lineNumber = 0
            while (pos < s.length) {
                val next = handleLine(pos, lineEnd(pos), lineNumber)
                if (next <= pos) break
                lineNumber += s.substring(pos, next).count { it == '\n' }
                pos = next
            }
            endBlock()
            if (!seenItem) {
                detached += topDetached
                topDetached.clear()
            }
            return EnvSpecFile(header, items.toList(), detached.toList(), diagnostics.sortedBy { it.range.first })
        }

        /** The end of the line starting at [start] (before `\r\n` / `\n`). */
        private fun lineEnd(start: Int): Int {
            val nl = s.indexOf('\n', start).let { if (it < 0) s.length else it }
            return if (nl > start && s[nl - 1] == '\r') nl - 1 else nl
        }

        /** The start of the line after the one that ends at [end]. */
        private fun nextLine(end: Int): Int {
            val nl = s.indexOf('\n', end)
            return if (nl < 0) s.length else nl + 1
        }

        /** Handles the line [start]..[end]; returns where the next line starts (later for multi-line values). */
        private fun handleLine(start: Int, end: Int, lineNumber: Int): Int {
            var p = start
            while (p < end && (s[p] == ' ' || s[p] == '\t')) p++
            if (p == end) {
                endBlock()
                return nextLine(end)
            }
            if (s[p] == '#') {
                val textStart = p + 1
                val text = s.substring(textStart, end)
                if (isDivider(text)) {
                    divider(start, end)
                    return nextLine(end)
                }
                val decorators = if (text.trimStart().startsWith('@')) parseDecorators(textStart, end) else emptyList()
                val line = CommentLine(text.trim(), decorators, start)
                block += line
                if (!seenItem) top += line
                return nextLine(end)
            }
            return item(p, end, lineNumber)
        }

        private fun divider(start: Int, end: Int) {
            if (!seenItem && header == null && (top.isNotEmpty() || topDetached.isNotEmpty())) {
                val lines = top.toList()
                val range = (lines.firstOrNull()?.start ?: start) until end
                header = EnvSpecHeader(lines.flatMap { it.decorators }, lines.map { it.text }, range)
                topDetached.clear()
                top.clear()
                block.clear()
                return
            }
            endBlock()
        }

        /** Ends the current comment block: its decorators are detached (they precede no item directly). */
        private fun endBlock() {
            if (block.isEmpty()) return
            val decorators = block.flatMap { it.decorators }
            if (decorators.isNotEmpty()) {
                if (seenItem) {
                    detached += decorators
                    diagnostics += EnvSpecDiagnostic(Code.DETACHED_DECORATORS, Severity.WARNING, decorators.first().range.first until decorators.last().range.last + 1)
                } else {
                    topDetached += decorators
                }
            }
            block.clear()
        }

        private fun item(contentStart: Int, end: Int, lineNumber: Int): Int {
            var p = contentStart
            if (s.startsWith("export", p) && p + 6 < end && (s[p + 6] == ' ' || s[p + 6] == '\t')) {
                p += 6
                while (p < end && (s[p] == ' ' || s[p] == '\t')) p++
            }
            val keyStart = p
            val match = KEY.matchAt(s, p)
            val keyEnd = match?.range?.last?.plus(1)?.coerceAtMost(end) ?: p
            var q = keyEnd
            while (q < end && (s[q] == ' ' || s[q] == '\t')) q++
            if (match == null || keyEnd == keyStart || q >= end || s[q] != '=') {
                diagnostics += EnvSpecDiagnostic(Code.INVALID_LINE, Severity.WARNING, contentStart until end)
                block.clear()
                return nextLine(end)
            }
            val key = s.substring(keyStart, keyEnd)
            q++ // past '='
            val afterEquals = q
            while (q < end && (s[q] == ' ' || s[q] == '\t')) q++
            var value: EnvSpecValue? = null
            var valueEnd: Int // where the value ends (possibly on a later line)
            when {
                q >= end -> valueEnd = q
                s.startsWith("\"\"\"", q) || s.startsWith("```", q) -> {
                    val fence = s.substring(q, q + 3)
                    val close = s.indexOf(fence, q + 3)
                    if (close < 0) {
                        diagnostics += EnvSpecDiagnostic(Code.UNTERMINATED_QUOTE, Severity.ERROR, q until end)
                        value = EnvSpecValue.Literal(s.substring(q + 3, end), fence, s.substring(q, end), q until end)
                        valueEnd = end
                    } else {
                        var content = s.substring(q + 3, close)
                        content = content.removePrefix("\r\n").removePrefix("\n")
                        value = EnvSpecValue.Literal(content, fence, s.substring(q, close + 3), q until close + 3)
                        valueEnd = close + 3
                    }
                }

                s[q] == '"' || s[q] == '\'' || s[q] == '`' -> {
                    // Double quotes may span lines (dotenv); single quotes and backticks end with their line
                    val (literal, closeEnd) = quoted(q, s.length, multiLine = s[q] == '"')
                    if (closeEnd < 0) {
                        // Unterminated: take the rest of the line and go on with the next one
                        diagnostics += EnvSpecDiagnostic(Code.UNTERMINATED_QUOTE, Severity.ERROR, q until end)
                        value = EnvSpecValue.Literal(s.substring(q + 1, end), s[q].toString(), s.substring(q, end), q until end)
                        valueEnd = end
                    } else {
                        value = literal
                        valueEnd = closeEnd
                    }
                }

                // `KEY= # comment` and `KEY=# @decorator`: no value. `KEY=#fff` keeps `#fff` (dotenv)
                s[q] == '#' && (q > afterEquals || s.substring(q + 1, end).trimStart().startsWith('@')) -> valueEnd = q
                else -> {
                    val call = if (isIdentStart(s[q])) callAt(q, end) else null
                    if (call != null) {
                        value = call
                        valueEnd = call.range.last + 1
                    } else {
                        val hash = Regex("""[ \t]#""").find(s.substring(q, end))?.range?.first?.plus(q) ?: end
                        val raw = s.substring(q, hash).trimEnd()
                        valueEnd = q + raw.length
                        value = if (raw.isEmpty()) null else unquotedItemValue(raw, q)
                    }
                }
            }
            // The rest of the value's (last) line: a trailing comment, maybe decorators
            val lastLineEnd = if (valueEnd > end) lineEnd(valueEnd) else end
            var r = valueEnd
            while (r < lastLineEnd && (s[r] == ' ' || s[r] == '\t')) r++
            var trailingDecorators = emptyList<EnvSpecDecorator>()
            var trailingComment: String? = null
            if (r < lastLineEnd) {
                if (s[r] == '#') {
                    val text = s.substring(r + 1, lastLineEnd)
                    if (text.trimStart().startsWith('@')) {
                        trailingDecorators = parseDecorators(r + 1, lastLineEnd)
                    } else {
                        trailingComment = text.trim().takeIf { it.isNotEmpty() }
                    }
                } else {
                    diagnostics += EnvSpecDiagnostic(Code.UNEXPECTED_TEXT, Severity.WARNING, r until lastLineEnd, listOf(s.substring(r, lastLineEnd).take(40)))
                }
            }
            seenItem = true
            if (top.isNotEmpty() || topDetached.isNotEmpty()) {
                // No header: blocks a blank line ended before the first item are detached
                detached += topDetached
                if (topDetached.isNotEmpty()) {
                    diagnostics += EnvSpecDiagnostic(Code.DETACHED_DECORATORS, Severity.WARNING, topDetached.first().range.first until topDetached.last().range.last + 1)
                }
                topDetached.clear()
                top.clear()
            }
            items += EnvSpecItem(
                key = key,
                keyRange = keyStart until keyEnd,
                value = value,
                decorators = block.flatMap { it.decorators } + trailingDecorators,
                comments = block.filterNot { it.isDecoratorLine }.map { it.text },
                trailingComment = trailingComment,
                line = lineNumber,
                range = keyStart until lastLineEnd,
            )
            block.clear()
            return nextLine(lastLineEnd)
        }

        /** An unquoted item value: a lone `$NAME` / `${NAME}` is a reference, anything else text. */
        private fun unquotedItemValue(raw: String, start: Int): EnvSpecValue {
            val range = start until start + raw.length
            Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""").find(raw)?.let { return EnvSpecValue.Ref(it.groupValues[1], raw, range) }
            Regex("""^\$([A-Za-z_][A-Za-z0-9_]*)$""").find(raw)?.let { return EnvSpecValue.Ref(it.groupValues[1], raw, range) }
            return EnvSpecValue.Literal(raw, null, raw, range)
        }

        /** A function call starting at [start] when the text there is `name(`…`)` followed by nothing but a comment; else null. */
        private fun callAt(start: Int, end: Int): EnvSpecValue.Call? {
            var p = start
            while (p < end && isIdentPart(s[p])) p++
            if (p >= end || s[p] != '(') return null
            val mark = diagnostics.size
            val (value, after) = value(start, end, ValueContext.TOP)
            val call = value as? EnvSpecValue.Call
            var r = after
            while (r < end && (s[r] == ' ' || s[r] == '\t')) r++
            if (call == null || (r < end && s[r] != '#')) {
                // Not a call after all (e.g. `a(b)c`): text, without the call's diagnostics
                while (diagnostics.size > mark) diagnostics.removeAt(diagnostics.size - 1)
                return null
            }
            return call
        }

        // ---- Decorators ---------------------------------------------------------------------------------------------

        /** The decorators of a decorator comment between [start] (after `#`) and [end]. */
        private fun parseDecorators(start: Int, end: Int): List<EnvSpecDecorator> {
            val result = mutableListOf<EnvSpecDecorator>()
            var p = start
            while (true) {
                while (p < end && (s[p] == ' ' || s[p] == '\t')) p++
                if (p >= end || s[p] == '#') break
                if (s[p] != '@') {
                    diagnostics += EnvSpecDiagnostic(Code.UNEXPECTED_TEXT, Severity.ERROR, p until end, listOf(s.substring(p, end).trim().take(40)))
                    break
                }
                val at = p
                p++
                val nameStart = p
                if (p < end && s[p].isLetter() && s[p].code < 128) {
                    while (p < end && isIdentPart(s[p])) p++
                }
                if (p == nameStart) {
                    val stop = skipToken(p, end)
                    diagnostics += EnvSpecDiagnostic(Code.INVALID_DECORATOR, Severity.ERROR, at until maxOf(stop, at + 1))
                    p = stop
                    continue
                }
                val name = s.substring(nameStart, p)
                var value: EnvSpecValue? = null
                var args: List<EnvSpecArg>? = null
                if (p < end && s[p] == '=') {
                    val (v, after) = value(p + 1, end, ValueContext.TOP)
                    value = v
                    p = after
                } else if (p < end && s[p] == '(') {
                    val (list, after) = arguments(p + 1, end, p)
                    args = list
                    p = after
                }
                result += EnvSpecDecorator(name, value, args, at until p, nameStart until nameStart + name.length)
                if (p < end && s[p] != ' ' && s[p] != '\t' && s[p] != '#') {
                    val stop = skipToken(p, end)
                    diagnostics += EnvSpecDiagnostic(Code.UNEXPECTED_TEXT, Severity.ERROR, p until stop, listOf(s.substring(p, stop).take(40)))
                    p = stop
                }
            }
            return result
        }

        private fun skipToken(from: Int, end: Int): Int {
            var p = from
            while (p < end && s[p] != ' ' && s[p] != '\t') p++
            return p
        }

        // ---- Values -------------------------------------------------------------------------------------------------

        private enum class ValueContext { TOP, ARGUMENT, ARRAY }

        /** A value starting at [start] (no leading blanks expected for TOP); returns it and where it ends. */
        private fun value(start: Int, end: Int, context: ValueContext): Pair<EnvSpecValue, Int> {
            var p = start
            if (context != ValueContext.TOP) while (p < end && (s[p] == ' ' || s[p] == '\t')) p++
            if (p >= end) return EnvSpecValue.Literal("", null, "", p until p) to p
            val c = s[p]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val (literal, closeEnd) = quoted(p, end, multiLine = false)
                    if (closeEnd < 0) {
                        diagnostics += EnvSpecDiagnostic(Code.UNTERMINATED_QUOTE, Severity.ERROR, p until end)
                        return EnvSpecValue.Literal(s.substring(p + 1, end), c.toString(), s.substring(p, end), p until end) to end
                    }
                    return literal to closeEnd
                }

                c == '[' -> {
                    val elements = mutableListOf<EnvSpecValue>()
                    var q = p + 1
                    while (true) {
                        while (q < end && (s[q] == ' ' || s[q] == '\t')) q++
                        if (q >= end) {
                            diagnostics += EnvSpecDiagnostic(Code.UNCLOSED_BRACKET, Severity.ERROR, p until end)
                            return EnvSpecValue.Array(elements, s.substring(p, end), p until end) to end
                        }
                        if (s[q] == ']') return EnvSpecValue.Array(elements, s.substring(p, q + 1), p..q) to q + 1
                        val (element, after) = value(q, end, ValueContext.ARRAY)
                        elements += element
                        q = after
                        while (q < end && (s[q] == ' ' || s[q] == '\t')) q++
                        if (q < end && s[q] == ',') q++
                        else if (q < end && s[q] != ']') {
                            diagnostics += EnvSpecDiagnostic(Code.UNEXPECTED_TEXT, Severity.ERROR, q..q, listOf(s[q].toString()))
                            q++
                        }
                    }
                }

                c == '$' -> {
                    Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""").matchAt(s, p)?.takeIf { it.range.last < end }?.let {
                        return EnvSpecValue.Ref(it.groupValues[1], it.value, it.range) to it.range.last + 1
                    }
                    Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""").matchAt(s, p)?.takeIf { it.range.last < end }?.let {
                        return EnvSpecValue.Ref(it.groupValues[1], it.value, it.range) to it.range.last + 1
                    }
                }

                isIdentStart(c) -> {
                    var q = p
                    while (q < end && isIdentPart(s[q])) q++
                    if (q < end && s[q] == '(') {
                        val (args, after) = arguments(q + 1, end, q)
                        return EnvSpecValue.Call(s.substring(p, q), args, s.substring(p, after), p until after) to after
                    }
                }
            }
            // Unquoted text: up to a blank (top level) or a separator (inside a call or an array)
            var q = p
            while (q < end) {
                val ch = s[q]
                val stop = when (context) {
                    ValueContext.TOP -> ch == ' ' || ch == '\t'
                    ValueContext.ARGUMENT -> ch == ',' || ch == ')'
                    ValueContext.ARRAY -> ch == ',' || ch == ']'
                }
                if (stop) break
                q++
            }
            val raw = s.substring(p, q).trimEnd()
            return EnvSpecValue.Literal(raw, null, raw, p until p + raw.length) to q
        }

        /** Arguments after `(` at [open]; returns them and the index after `)`. */
        private fun arguments(start: Int, end: Int, open: Int): Pair<List<EnvSpecArg>, Int> {
            val args = mutableListOf<EnvSpecArg>()
            var p = start
            while (true) {
                while (p < end && (s[p] == ' ' || s[p] == '\t')) p++
                if (p >= end) {
                    diagnostics += EnvSpecDiagnostic(Code.UNCLOSED_PARENTHESIS, Severity.ERROR, open until end)
                    return args to end
                }
                if (s[p] == ')') return args to p + 1
                val argStart = p
                // key=value (but not `==`)
                var key: String? = null
                var q = p
                if (isIdentStart(s[q])) {
                    while (q < end && isIdentPart(s[q])) q++
                    var e = q
                    while (e < end && (s[e] == ' ' || s[e] == '\t')) e++
                    if (e < end && s[e] == '=' && (e + 1 >= end || s[e + 1] != '=')) {
                        key = s.substring(p, q)
                        p = e + 1
                    }
                }
                val (value, after) = value(p, end, ValueContext.ARGUMENT)
                val argEnd = after.coerceAtLeast(argStart)
                args += EnvSpecArg(key, value, argStart until value.range.last.plus(1).coerceAtLeast(argStart), s.substring(argStart, argEnd).trim())
                p = after
                while (p < end && (s[p] == ' ' || s[p] == '\t')) p++
                if (p < end && s[p] == ',') {
                    p++
                } else if (p < end && s[p] != ')') {
                    diagnostics += EnvSpecDiagnostic(Code.UNEXPECTED_TEXT, Severity.ERROR, p..p, listOf(s[p].toString()))
                    p++
                }
            }
        }

        /**
         * A quoted string starting at [start] (the quote). Returns it and the index after the closing quote, or -1 as the index
         * when it isn't closed before [end] (or the line's end unless [multiLine]).
         */
        private fun quoted(start: Int, end: Int, multiLine: Boolean): Pair<EnvSpecValue.Literal, Int> {
            val quote = s[start]
            val value = StringBuilder()
            var j = start + 1
            while (j < end) {
                val c = s[j]
                if (!multiLine && c == '\n') break
                if (c == quote) {
                    return EnvSpecValue.Literal(value.toString(), quote.toString(), s.substring(start, j + 1), start..j) to j + 1
                }
                if (c == '\\' && j + 1 < end && quote != '\'') {
                    val next = s[j + 1]
                    value.append(
                        when {
                            quote == '"' && next == 'n' -> '\n'
                            quote == '"' && next == 'r' -> '\r'
                            quote == '"' && next == 't' -> '\t'
                            next == quote || next == '\\' -> next
                            else -> {
                                value.append('\\')
                                next
                            }
                        },
                    )
                    j += 2
                    continue
                }
                value.append(c)
                j++
            }
            return EnvSpecValue.Literal(value.toString(), quote.toString(), s.substring(start, j), start until j) to -1
        }

        private fun isIdentStart(c: Char) = c.code < 128 && c.isLetter()
        private fun isIdentPart(c: Char) = c.code < 128 && (c.isLetterOrDigit() || c == '_')
    }
}
