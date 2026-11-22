package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.reference.ReferenceCompletion
import cz.xprees.secrets.core.reference.ReferenceGrammar
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
            val escaped = Regex.escape(scheme)
            // References with spaces in their names (1Password labels: op://Private/Stripe/api key), where the extent is clear: the
            // whole of a quoted string, or the whole value of a KEY= line up to a ` # comment`. Elsewhere a space ends a reference.
            val spaced = mutableListOf<IntRange>()
            val extents = Regex("""(?i)"($escaped://[^"\r\n]*)"|'($escaped://[^'\r\n]*)'|`($escaped://[^`\r\n]*)`""").findAll(text)
                .mapNotNull { m -> (1..3).firstNotNullOfOrNull { m.groups[it] } } +
                Regex("""(?im)^[ \t]*(?:export[ \t]+)?[A-Za-z_][A-Za-z0-9_.\-]*[ \t]*=[ \t]*($escaped://[^"'`\r\n]*?)(?:[ \t]+#[^\r\n]*)?[ \t]*$""")
                    .findAll(text).mapNotNull { it.groups[1] }
            for (group in extents) {
                // Blanks at the edge are a typo, not part of a name (as the grammars say): the plain match below takes it to the blank
                if (group.value.none(Char::isWhitespace) || group.value != group.value.trim() || spaced.any { group.range.first in it }) continue
                val reference = runCatching { SecretReference.parse(group.value, listOf(scheme)) }.getOrNull() ?: continue
                spaced += group.range
                if (reference.key != null) result += Found(reference.key, group.range.first, group.range.last + 1, Kind.REFERENCE, reference)
            }
            Regex("""(?i)\b$escaped://[^\s"'`<>,;)\]}]+""").findAll(text).forEach { m ->
                if (spaced.any { m.range.first in it }) return@forEach
                val reference = runCatching { SecretReference.parse(m.value, listOf(scheme)) }.getOrNull()
                if (reference?.key != null) result += Found(reference.key, m.range.first, m.range.last + 1, Kind.REFERENCE, reference)
            }
        }
        return result.distinctBy { it.start to it.end }.sortedBy { it.start }
    }

    /**
     * When [linePrefix] (the line up to the caret) ends inside a reference whose grammar completes names itself
     * ([ReferenceGrammar.completionContext]: `op://vault/item/field`, `vault://mount/path#key`), its scheme and what is typed.
     */
    fun referenceCompletion(linePrefix: String, schemes: Collection<String>): Pair<String, ReferenceCompletion>? {
        for (scheme in schemes) {
            val start = Regex("""(?i)\b${Regex.escape(scheme)}://""").findAll(linePrefix).lastOrNull() ?: continue
            val body = linePrefix.substring(start.range.last + 1)
            val grammar = SecretReference.grammarFor(scheme)
            if (body.any { it in "\"'`<>" } || (!grammar.allowsSpaces && body.any(Char::isWhitespace))) continue
            grammar.completionContext(body)?.let { return scheme to it }
        }
        return null
    }

    /** The partially typed variable name when [linePrefix] (the line up to the caret) is a place to complete one. */
    fun completionPrefix(linePrefix: String, schemes: Collection<String>): String? {
        for (pattern in COMPLETION_PATTERNS) pattern.find(linePrefix)?.let { return it.groupValues[1] }
        for (scheme in schemes) {
            // infisical://partial, infisical://folder/partial, infisical://env:folder/partial: complete the key
            Regex("""(?i)\b${Regex.escape(scheme)}://(?:[A-Za-z0-9_.\-]+:)?(?:[^\s"'?/:]+/)*([A-Za-z0-9_.\-]*)$""").find(linePrefix)?.let { return it.groupValues[1] }
        }
        return null
    }
}
