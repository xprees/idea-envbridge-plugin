package cz.xprees.secrets.core.reference

import cz.xprees.secrets.core.model.SecretScope
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A reference to one secret, or to every secret of a folder, written as the value of an environment variable:
 *
 * ```
 * infisical://KEY                        KEY from the bound environment and folder
 * infisical://prod/KEY                   KEY from the root folder of environment prod
 * infisical://prod/backend/api/KEY       KEY from folder /backend/api of prod
 * infisical://(star)                     every secret of the bound environment and folder (scope import)
 * infisical://staging/backend/(star)(star)  every secret of /backend in staging, subfolders included
 * infisical://KEY?env=prod&path=/backend&project=<id>&connection=<id>
 * ```
 *
 * With a single segment the environment and folder come from the project binding. With more, the first segment is the
 * environment and the segments between it and the key form the folder (root when there are none). Query parameters
 * override the parsed parts. (star) stands for `*`, which can't be written after a slash in a Kotlin comment. The scheme is the provider's ([cz.xprees.secrets.core.provider.SecretProvider.referenceScheme]).
 */
data class SecretReference(
    val scheme: String,
    /** The secret's key, or null for a scope import (last segment `*` or `**`). */
    val key: String?,
    /** Environment slug, or null for the bound one. */
    val environment: String? = null,
    /** Absolute folder path, or null for the bound one. */
    val path: String? = null,
    /** Scope imports only: include subfolders (last segment `**`). */
    val recursive: Boolean = false,
    val projectId: String? = null,
    val connectionId: String? = null,
) {
    val isScope: Boolean get() = key == null

    /** The scope this reference reads from, filling what it leaves open from [binding]. */
    fun scope(binding: Defaults): SecretScope = SecretScope(
        projectId = projectId ?: binding.projectId,
        environment = environment ?: binding.environment,
        path = SecretScope.normalizePath(path ?: binding.path),
        recursive = recursive,
    )

    /** Canonical text form; [parse] of it gives an equal reference. */
    fun format(): String = buildString {
        append(scheme).append("://")
        val segments = mutableListOf<String>()
        val folder = path?.let(SecretScope::normalizePath)
        // Positional form only when it can express the parts; otherwise query parameters.
        val positional = environment != null && environment.none { it == '/' }
        if (positional) {
            segments += environment!!
            folder?.split('/')?.filter { it.isNotEmpty() }?.let { segments += it }
        }
        segments += when {
            key != null -> key
            recursive -> "**"
            else -> "*"
        }
        append(segments.joinToString("/") { encodeSegment(it) })
        val query = buildList {
            if (!positional) {
                environment?.let { add("env" to it) }
                folder?.let { add("path" to it) }
            }
            projectId?.let { add("project" to it) }
            connectionId?.let { add("connection" to it) }
        }
        if (query.isNotEmpty()) {
            append('?').append(query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" })
        }
    }

    override fun toString(): String = format()

    /** Values a reference takes from the project binding when it does not name them itself. */
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

            val last = segments.last()
            val isScope = last == "*" || last == "**"
            require(segments.dropLast(1).none { it == "*" || it == "**" }) { "In '$text', * and ** may only be the last segment" }
            var environment: String? = null
            var path: String? = null
            if (segments.size > 1) {
                environment = segments.first()
                path = "/" + segments.subList(1, segments.size - 1).joinToString("/")
            }
            query["env"]?.let { environment = it.ifBlank { null } }
            query["path"]?.let { path = SecretScope.normalizePath(it) }
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
