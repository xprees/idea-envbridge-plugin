package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.reference.SecretReference

/**
 * Finds environment variable names in source text, language-agnostically: the common ways code reads environment variables,
 * `${VAR}` placeholders (Compose, YAML, Spring properties, shell) and secret references (`infisical://KEY`). Pure text
 * matching, so it works in every IDE and file type without depending on language plugins.
 */
object EnvReferences {

    enum class Kind {
        /** Code reading a variable: `System.getenv("X")`, `process.env.X`, `os.environ["X"]`, … */
        CODE,

        /** A `${X}` placeholder. */
        PLACEHOLDER,

        /** A secret reference: `infisical://X`. [Found.reference] is set. */
        REFERENCE,
    }

    /** A variable name at [start] until [end] (exclusive) in the text. */
    data class Found(val name: String, val start: Int, val end: Int, val kind: Kind, val reference: SecretReference? = null)

    private const val NAME = """([A-Za-z_][A-Za-z0-9_.\-]*)"""

    /** Patterns whose group 1 is a complete variable name. */
    private val CODE_PATTERNS: List<Regex> = listOf(
        // Java, Kotlin, Scala, Groovy: System.getenv("X"); Python os.getenv; PHP/C getenv; Deno.env.get
        Regex("""\b(?:getenv|Getenv|LookupEnv|GetEnvironmentVariable|getEnv|get_env)\s*\(\s*["']$NAME["']"""),
        Regex("""\bDeno\.env\.get\s*\(\s*["']$NAME["']"""),
        // JavaScript / TypeScript: process.env.X, process.env["X"], Bun.env.X, import.meta.env.X
        Regex("""\b(?:process\.env|Bun\.env|import\.meta\.env)\.$NAME"""),
        Regex("""\b(?:process\.env|Bun\.env|import\.meta\.env)\[\s*["']$NAME["']\s*\]"""),
        // Python: os.environ["X"], os.environ.get("X"); Ruby: ENV["X"], ENV.fetch("X")
        Regex("""\b(?:environ|ENV)\[\s*["']$NAME["']\s*\]"""),
        Regex("""\b(?:environ|ENV)\.(?:get|fetch)\s*\(\s*["']$NAME["']"""),
        // Rust: std::env::var("X"); Laravel/Django helpers: env("X")
        Regex("""\benv::var(?:_os)?\s*\(\s*"$NAME""""),
        Regex("""(?<![\w.])env\s*\(\s*["']$NAME["']"""),
    )

    private val PLACEHOLDER = Regex("""\$\{$NAME(?:[:?\-=][^}]*)?}""")

    /** Completion contexts: the text before the caret ends inside a variable name; group 1 is the typed part. */
    private val COMPLETION_PATTERNS: List<Regex> = listOf(
        Regex("""\b(?:getenv|Getenv|LookupEnv|GetEnvironmentVariable|getEnv|get_env)\s*\(\s*["']([A-Za-z0-9_.\-]*)$"""),
        Regex("""\bDeno\.env\.get\s*\(\s*["']([A-Za-z0-9_.\-]*)$"""),
        Regex("""\b(?:process\.env|Bun\.env|import\.meta\.env)\.([A-Za-z0-9_]*)$"""),
        Regex("""\b(?:process\.env|Bun\.env|import\.meta\.env)\[\s*["']([A-Za-z0-9_.\-]*)$"""),
        Regex("""\b(?:environ|ENV)\[\s*["']([A-Za-z0-9_.\-]*)$"""),
        Regex("""\b(?:environ|ENV)\.(?:get|fetch)\s*\(\s*["']([A-Za-z0-9_.\-]*)$"""),
        Regex("""\benv::var(?:_os)?\s*\(\s*"([A-Za-z0-9_.\-]*)$"""),
        Regex("""(?<![\w.])env\s*\(\s*["']([A-Za-z0-9_.\-]*)$"""),
        Regex("""\$\{([A-Za-z0-9_.\-]*)$"""),
    )

    /** Files larger than this are not scanned (generated or minified files). */
    const val MAX_TEXT_LENGTH = 512 * 1024

    /** Every variable name and secret reference in [text]. */
    fun find(text: CharSequence, schemes: Collection<String>): List<Found> {
        if (text.length > MAX_TEXT_LENGTH) return emptyList()
        val result = mutableListOf<Found>()
        for (pattern in CODE_PATTERNS) {
            pattern.findAll(text).forEach { m -> m.groups[1]?.let { g -> result += Found(g.value, g.range.first, g.range.last + 1, Kind.CODE) } }
        }
        PLACEHOLDER.findAll(text).forEach { m -> m.groups[1]?.let { g -> result += Found(g.value, g.range.first, g.range.last + 1, Kind.PLACEHOLDER) } }
        for (scheme in schemes) {
            Regex("""(?i)\b${Regex.escape(scheme)}://[^\s"'`<>,;)\]}]+""").findAll(text).forEach { m ->
                val reference = runCatching { SecretReference.parse(m.value, listOf(scheme)) }.getOrNull()
                if (reference?.key != null) result += Found(reference.key, m.range.first, m.range.last + 1, Kind.REFERENCE, reference)
            }
        }
        return result.distinctBy { it.start to it.end }.sortedBy { it.start }
    }

    /** The partially typed variable name when [linePrefix] (the line up to the caret) is a place to complete one. */
    fun completionPrefix(linePrefix: String, schemes: Collection<String>): String? {
        for (pattern in COMPLETION_PATTERNS) pattern.find(linePrefix)?.let { return it.groupValues[1] }
        for (scheme in schemes) {
            // infisical://partial or infisical://env/folder/partial: complete the last segment
            Regex("""(?i)\b${Regex.escape(scheme)}://(?:[^\s"'?/]+/)*([A-Za-z0-9_.\-]*)$""").find(linePrefix)?.let { return it.groupValues[1] }
        }
        return null
    }
}
