package cz.xprees.secrets.core.resolve

/**
 * Parser for `.env` files as run configurations load them: `KEY=value` lines, optional `export `, single-quoted values
 * (literal), double-quoted values (may span lines; understand `\n`, `\r`, `\t`, `\"` and `\\`), full-line comments and
 * ` #` comments after unquoted values. Lines that are not assignments are ignored.
 */
object DotEnv {
    private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")

    fun parse(text: String): LinkedHashMap<String, String> {
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
            val rest = line.substring(eq + 1).trimStart()
            result[key] = when {
                rest.startsWith('\'') -> rest.substring(1).substringBefore('\'')
                rest.startsWith('"') -> {
                    // Scan from the opening quote in the whole text, so a value can continue on the next lines
                    val start = lineStart + raw.indexOf('"', raw.indexOf('=')) + 1
                    val (value, end) = readDoubleQuoted(s, start)
                    if (end > lineEnd) i = (s.indexOf('\n', end).let { if (it < 0) s.length else it }) + 1
                    value
                }

                else -> rest.substringBefore(" #").trimEnd()
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
