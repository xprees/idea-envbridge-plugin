package cz.xprees.secrets.core.reference

import cz.xprees.secrets.core.model.SecretScope
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A reference to one secret, or to every secret of a folder, written as the value of an environment variable:
 *
 * ```
 * infisical://KEY                        KEY in the root folder of the bound environment
 * infisical://workers/KEY                KEY in folder /workers of the bound environment
 * infisical://prod:KEY                   KEY in the root folder of environment prod
 * infisical://prod:backend/api/KEY       KEY in folder /backend/api of prod
 * infisical://(star)                     every secret of the root folder (scope import)
 * infisical://staging:backend/(star)(star)  every secret of /backend in staging, subfolders included
 * infisical://KEY?project=<id>&connection=<name>
 * ```
 *
 * Folders are absolute from the environment's root, so the reference alone says where the secret is; only the environment
 * comes from the project binding when it isn't written as `env:` before the first segment. (`?env=` and `?path=` are
 * accepted too.) (star) stands for `*`, which can't be written after a slash in a Kotlin comment. The scheme is the
 * provider's ([cz.xprees.secrets.core.provider.SecretProvider.referenceScheme]).
 */
data class SecretReference(
    val scheme: String,
    /** The secret's key, or null for a scope import (last segment `*` or `**`). */
    val key: String?,
    /** Environment slug, or null for the bound one. */
    val environment: String? = null,
    /** Absolute folder path, or null for the root folder. */
    val path: String? = null,
    /** Scope imports only: include subfolders (last segment `**`). */
    val recursive: Boolean = false,
    val projectId: String? = null,
    val connectionId: String? = null,
) {
    val isScope: Boolean get() = key == null

    /** The scope this reference reads from: the environment (and project) left open come from [binding]; no folder is the root. */
    fun scope(binding: Defaults): SecretScope = SecretScope(
        projectId = projectId ?: binding.projectId,
        environment = environment ?: binding.environment,
        path = SecretScope.normalizePath(path ?: "/"),
        recursive = recursive,
    )

    /** Canonical text form; [parse] of it gives an equal reference. */
    fun format(): String = buildString {
        append(scheme).append("://")
        val segments = mutableListOf<String>()
        SecretScope.normalizePath(path ?: "/").split('/').filter { it.isNotEmpty() }.let { segments += it }
        segments += when {
            key != null -> key
            recursive -> "**"
            else -> "*"
        }
        // An environment slug can always be written as the prefix (slugs have no ':' or '/'); anything else as ?env=
        val prefixed = environment != null && environment.none { it == ':' || it == '/' || it == '?' }
        if (prefixed) append(environment).append(':')
        append(segments.joinToString("/") { encodeSegment(it) })
        val query = buildList {
            if (environment != null && !prefixed) add("env" to environment)
            projectId?.let { add("project" to it) }
            connectionId?.let { add("connection" to it) }
        }
        if (query.isNotEmpty()) {
            append('?').append(query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8).replace("%2F", "/")}" })
        }
    }

    override fun toString(): String = format()

    /** Values a reference takes from the project binding when it does not name them itself (the folder never: it is absolute). */
    data class Defaults(val projectId: String, val environment: String, val path: String = "/")

    companion object {
        private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$""", RegexOption.DOT_MATCHES_ALL)

        /** Whether [value] looks like a reference with [scheme] (case-insensitive scheme), without parsing it fully. */
        fun looksLikeReference(value: String, scheme: String): Boolean =
            value.trimStart().regionMatches(0, "$scheme://", 0, scheme.length + 3, ignoreCase = true)

        /**
         * Parses [text] if it is a reference with one of [schemes].
         *
         * @return null when [text] is not a reference of these schemes at all (an ordinary value)
         * @throws IllegalArgumentException when it uses one of the schemes but is malformed
         */
        fun parse(text: String, schemes: Collection<String>): SecretReference? {
            val match = SCHEME.matchEntire(text.trim()) ?: return null
            val scheme = match.groupValues[1].lowercase()
            if (schemes.none { it.equals(scheme, ignoreCase = true) }) return null
            val body = match.groupValues[2]
            val (pathPart, queryPart) = body.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
            val query = parseQuery(queryPart)
            val segments = pathPart.split('/').filter { it.isNotEmpty() }.map(::decodeSegment)
            require(segments.isNotEmpty()) { "Reference '$text' names no secret: add a key, or * for every secret" }
            require(segments.none { it.isBlank() || it.any(Char::isWhitespace) }) { "Reference '$text' contains whitespace" }

            // `env:` before the first segment names the environment; every other segment is a folder from the root
            var environment: String? = null
            val parts = segments.toMutableList()
            val colon = parts.first().indexOf(':')
            if (colon >= 0) {
                environment = parts.first().substring(0, colon)
                require(environment.isNotEmpty()) { "In '$text', the environment before ':' is empty" }
                val rest = parts.first().substring(colon + 1)
                if (rest.isEmpty()) parts.removeAt(0) else parts[0] = rest
                require(parts.isNotEmpty()) { "Reference '$text' names no secret: add a key, or * for every secret" }
            }
            val last = parts.last()
            val isScope = last == "*" || last == "**"
            require(parts.dropLast(1).none { it == "*" || it == "**" }) { "In '$text', * and ** may only be the last segment" }
            var path: String? = parts.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "/")
            query["env"]?.let { environment = it.ifBlank { null } }
            query["path"]?.let { path = SecretScope.normalizePath(it) }
            if (path == "/") path = null
            val unknown = query.keys - setOf("env", "path", "project", "connection")
            require(unknown.isEmpty()) { "Unknown parameter(s) ${unknown.joinToString()} in '$text'" }
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

        private fun parseQuery(query: String?): Map<String, String> {
            if (query.isNullOrEmpty()) return emptyMap()
            return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(k, StandardCharsets.UTF_8).lowercase() to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
        }

        private fun decodeSegment(segment: String): String = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)

        private fun encodeSegment(segment: String): String =
            if (segment.all { it.isLetterOrDigit() || it in "_-.*" }) segment
            else URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
