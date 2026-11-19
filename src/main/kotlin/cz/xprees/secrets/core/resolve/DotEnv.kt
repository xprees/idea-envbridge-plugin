package cz.xprees.secrets.core.resolve

/**
 * Parser for `.env` files as run configurations load them: `KEY=value` lines, optional `export `, single-quoted values
 * (literal), double-quoted values (may span lines; understand `\n`, `\r`, `\t`, `\"` and `\\`), full-line comments and
 * ` #` comments after unquoted values. Lines that are not assignments are ignored.
 *
 * **Bindings:** a comment at the end of an assignment that is a reference of one of the given schemes, such as
 * `URL= # infisical://prod/URL` or `URL=http://localhost # infisical://URL`, replaces the value. Tools outside the IDE read
 * the plain value (empty or a local fallback); launches from the IDE get the secret.
 */
object DotEnv {
    private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")

    /** The reference of a binding comment (the text after `#`), or null when [comment] isn't one. */
    fun bindingOf(comment: String, schemes: Collection<String>): String? {
        val candidate = comment.trim()
        if (candidate.any(Char::isWhitespace)) return null
        return candidate.takeIf { schemes.any { scheme -> candidate.startsWith("$scheme://", ignoreCase = true) } }
    }

    fun parse(text: String, bindingSchemes: Collection<String> = emptyList()): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val s = text.replace("\r\n", "\n")
        var i = 0
        while (i < s.length) {
            val lineStart = i
            val lineEnd = s.indexOf('\n', i).let { if (it < 0) s.length else it }
            val raw = s.substring(lineStart, lineEnd)
            var line = raw.trim()
            i = lineEnd + 1
            if (line.isEmpty() || line.startsWith('#')) continue
            if (line.startsWith("export ")) line = line.removePrefix("export ").trimStart()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (!KEY.matches(key)) continue
            val afterEq = line.substring(eq + 1)
            val rest = afterEq.trimStart()
            // `KEY= # comment` has no value (like dotenv); `KEY=#fff` keeps `#fff` unless it is a binding
            val commentOnly = rest.startsWith('#') && rest.length < afterEq.length
            // What follows the value on its line, for binding comments (multi-line values have none)
            var trailing: String? = null
            result[key] = when {
                rest.startsWith('\'') -> rest.substring(1).substringBefore('\'').also {
                    trailing = rest.substring(1).substringAfter('\'', "")
                }

                rest.startsWith('"') -> {
                    // Scan from the opening quote in the whole text, so a value can continue on the next lines
                    val start = lineStart + raw.indexOf('"', raw.indexOf('=')) + 1
                    val (value, end) = readDoubleQuoted(s, start)
                    if (end > lineEnd) i = (s.indexOf('\n', end).let { if (it < 0) s.length else it }) + 1 else trailing = s.substring(end, lineEnd)
                    value
                }

                commentOnly -> "".also { trailing = rest }
                else -> rest.substringBefore(" #").trimEnd().also {
                    // `KEY=#…` is only a comment when it is a binding (otherwise the value is kept, e.g. `COLOR=#fff`)
                    trailing = if (rest.startsWith('#')) rest else rest.substringAfter(" #", "").let { c -> if (c.isEmpty()) null else "#$c" }
                }
            }
            if (bindingSchemes.isNotEmpty()) {
                val comment = trailing?.trimStart()?.takeIf { it.startsWith('#') }?.substring(1)
                comment?.let { bindingOf(it, bindingSchemes) }?.let { result[key] = it }
            }
        }
        return result
    }

    /** Reads a double-quoted value starting after the opening quote; returns it and the index after the closing quote. */
    private fun readDoubleQuoted(s: String, start: Int): Pair<String, Int> {
        val value = StringBuilder()
        var j = start
        while (j < s.length) {
            val c = s[j]
            when {
                c == '"' -> return value.toString() to j + 1
                c == '\\' && j + 1 < s.length -> {
                    value.append(
                        when (val next = s[j + 1]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> next
                        },
                    )
                    j += 2
                    continue
                }

                else -> value.append(c)
            }
            j++
        }
        return value.toString() to s.length
    }
}
