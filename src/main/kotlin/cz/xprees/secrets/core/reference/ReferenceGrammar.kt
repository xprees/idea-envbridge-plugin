package cz.xprees.secrets.core.reference

import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretScope
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * How one provider writes references: parsing the text after `scheme://`, formatting a [SecretReference], and building the
 * shortest reference to a secret (what the binding already says is left out where the grammar allows).
 */
interface ReferenceGrammar {
    /**
     * Parses [body] (the text after `scheme://`) of [original].
     *
     * @throws IllegalArgumentException when it is malformed
     */
    fun parse(scheme: String, body: String, original: String): SecretReference

    fun format(reference: SecretReference): String

    /** A reference to [key] (or every secret, when null) of [scope], leaving out what [defaults] (the binding) says. */
    fun build(scheme: String, scope: SecretScope, key: String?, defaults: SecretReference.Defaults?): SecretReference

    /**
     * What is being typed at the end of [body] (the text after `scheme://` up to the caret), for completion: a project, a folder
     * segment or a key. Null leaves completion to the default (keys, with `[env:][folder/…/]` before them, as [PathGrammar]
     * writes them), also when [body] is no place to complete.
     */
    fun completionContext(body: String): ReferenceCompletion? = null

    /** Whether a reference's names may contain raw spaces (1Password writes them as they are). */
    val allowsSpaces: Boolean get() = false

    /** How [project] is written in a reference, for completing it; null when references don't name projects this way. */
    fun projectName(project: ProviderProject): String? = null

    /** [name] (a project, folder or key) as written in a reference segment. */
    fun formatSegment(name: String): String = ReferenceSyntax.encodeSegment(name)
}

/** What completion is asked for inside a partially typed reference ([ReferenceGrammar.completionContext]); never values. */
sealed class ReferenceCompletion {
    /** The part of the name typed so far (as written, not decoded). */
    abstract val prefix: String

    /** A project as references write it (a 1Password vault, a Vault mount). */
    data class Project(override val prefix: String) : ReferenceCompletion()

    /** A folder segment directly below [parent] of [project] (null: the bound project). */
    data class Folder(override val prefix: String, val project: String?, val parent: String) : ReferenceCompletion()

    /** A key of [folder] of [project] (null: the bound project); a null [folder] is what a reference without a folder reads. */
    data class Key(override val prefix: String, val project: String?, val folder: String?) : ReferenceCompletion()
}

/** Shared pieces of reference syntax: query parameters and percent-encoded segments. */
object ReferenceSyntax {
    fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(k, StandardCharsets.UTF_8).lowercase() to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
    }

    fun formatQuery(query: List<Pair<String, String>>): String =
        if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8).replace("%2F", "/")}" }

    fun decodeSegment(segment: String): String = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)

    fun encodeSegment(segment: String): String =
        if (segment.all { it.isLetterOrDigit() || it in "_-.*" }) segment
        else URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
}

/**
 * The default grammar (Infisical): `scheme://[env:][folder/…/]KEY[?project=…&connection=…]`, folders absolute from the
 * environment's root, `*` / `**` for scope imports. See [SecretReference].
 */
object PathGrammar : ReferenceGrammar {
    override fun parse(scheme: String, body: String, original: String): SecretReference {
        val (pathPart, queryPart) = body.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val query = ReferenceSyntax.parseQuery(queryPart)
        val segments = pathPart.split('/').filter { it.isNotEmpty() }.map(ReferenceSyntax::decodeSegment)
        require(segments.isNotEmpty()) { "Reference '$original' names no secret: add a key, or * for every secret" }
        require(segments.none { it.isBlank() || it.any(Char::isWhitespace) }) { "Reference '$original' contains whitespace" }

        // `env:` before the first segment names the environment; every other segment is a folder from the root
        var environment: String? = null
        val parts = segments.toMutableList()
        val colon = parts.first().indexOf(':')
        if (colon >= 0) {
            environment = parts.first().substring(0, colon)
            require(environment.isNotEmpty()) { "In '$original', the environment before ':' is empty" }
            val rest = parts.first().substring(colon + 1)
            if (rest.isEmpty()) parts.removeAt(0) else parts[0] = rest
            require(parts.isNotEmpty()) { "Reference '$original' names no secret: add a key, or * for every secret" }
        }
        val last = parts.last()
        val isScope = last == "*" || last == "**"
        require(parts.dropLast(1).none { it == "*" || it == "**" }) { "In '$original', * and ** may only be the last segment" }
        var path: String? = parts.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "/")
        query["env"]?.let { environment = it.ifBlank { null } }
        query["path"]?.let { path = SecretScope.normalizePath(it) }
        if (path == "/") path = null
        val unknown = query.keys - setOf("env", "path", "project", "connection")
        require(unknown.isEmpty()) { "Unknown parameter(s) ${unknown.joinToString()} in '$original'" }
        return SecretReference(
            scheme = scheme,
            key = if (isScope) null else last,
            environment = environment,
            path = path,
            recursive = last == "**",
            projectId = query["project"]?.ifBlank { null },
            connectionId = query["connection"]?.ifBlank { null },
        )
    }

    override fun format(reference: SecretReference): String = buildString {
        append(reference.scheme).append("://")
        val segments = SecretScope.normalizePath(reference.path ?: "/").split('/').filter { it.isNotEmpty() }.toMutableList()
        segments += reference.key ?: if (reference.recursive) "**" else "*"
        val environment = reference.environment
        // An environment slug can always be written as the prefix (slugs have no ':' or '/'); anything else as ?env=
        val prefixed = environment != null && environment.none { it == ':' || it == '/' || it == '?' }
        if (prefixed) append(environment).append(':')
        append(segments.joinToString("/") { ReferenceSyntax.encodeSegment(it) })
        append(
            ReferenceSyntax.formatQuery(
                buildList {
                    if (environment != null && !prefixed) add("env" to environment)
                    reference.projectId?.let { add("project" to it) }
                    reference.connectionId?.let { add("connection" to it) }
                },
            ),
        )
    }

    override fun build(scheme: String, scope: SecretScope, key: String?, defaults: SecretReference.Defaults?): SecretReference {
        val sameProject = defaults != null && defaults.projectId == scope.projectId
        return SecretReference(
            scheme = scheme,
            key = key,
            environment = scope.environment.takeIf { !sameProject || it != defaults?.environment },
            path = scope.path.takeIf { it != "/" },
            recursive = key == null && scope.recursive,
            projectId = scope.projectId.takeIf { !sameProject },
        )
    }
}
