package cz.xprees.secrets.core.reference

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
