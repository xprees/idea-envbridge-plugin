package cz.xprees.secrets.onepassword

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.ReferenceGrammar
import cz.xprees.secrets.core.reference.ReferenceSyntax
import cz.xprees.secrets.core.reference.SecretReference
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 1Password's own secret reference format, so references written for `op run` / `op read` work as they are:
 *
 * ```
 * op://<vault>/<item>/<field>                field of the item (in any section; top-level fields first)
 * op://<vault>/<item>/<section>/<field>      field of a section of the item
 * op://<vault>/<item>/(star)                 every field of the item (EnvBridge extension, scope import)
 * op://<vault>/<item>/<section>/(star)       every field of that section
 * op://<vault>/(star)(star)                  every field of every item of the vault (capped)
 * op://Private/Database/password?connection=<name>
 * ```
 *
 * Vault, item, section and field are names or IDs. Names may contain spaces, written as they are (as 1Password writes them);
 * other special characters can be percent-encoded (`%2F` is refused: a name with a slash is named by its ID, as 1Password
 * requires). The only parameter of 1Password's own syntax that is accepted is `?attribute=value` (the default); other
 * attributes (`otp`, `type`, …) and `ssh-format` are refused with a message. (star) stands for `*`.
 *
 * Mapping: the vault is the project, `/<item>[/<section>]` the folder path and the field the key. 1Password has no
 * environments; the environment is always [OnePasswordProvider.ENVIRONMENT].
 */
object OnePasswordGrammar : ReferenceGrammar {
    private val PARAMETERS = setOf("connection", "attribute")

    override fun parse(scheme: String, body: String, original: String): SecretReference {
        val (pathPart, queryPart) = body.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val query = ReferenceSyntax.parseQuery(queryPart)
        val unknown = query.keys - PARAMETERS
        require(unknown.isEmpty()) {
            "Unknown parameter(s) ${unknown.joinToString()} in '$original'" +
                if ("ssh-format" in unknown) " (EnvBridge reads field values only)" else " (only ?connection= is supported)"
        }
        query["attribute"]?.let { attribute ->
            require(attribute.equals("value", ignoreCase = true)) {
                "In '$original', ?attribute=$attribute is not supported: EnvBridge reads field values only (leave out ?attribute=)"
            }
        }

        val raw = pathPart.split('/')
        require(raw.none { it.isEmpty() }) { "Reference '$original' has an empty segment; write op://vault/item/[section/]field" }
        val segments = raw.map { decode(it, original) }
        require(segments.none { it.isBlank() }) { "Reference '$original' has a blank segment" }
        // Surrounding blanks written raw are typos; a name that really has them is written with %20
        require(raw.none { it != it.trim() }) { "Reference '$original' has a segment that starts or ends with whitespace" }
        require(segments.none { '/' in it }) { "In '$original', a name contains '/': name that vault, item or section by its ID" }
        // Stars are recognised as written: an encoded %2A is a name
        require(raw.dropLast(1).none { it == "*" || it == "**" }) { "In '$original', * may only be the last segment" }

        val vault = segments.first()
        val connection = query["connection"]?.ifBlank { null }
        if (raw.size == 2 && raw[1] == "**") {
            return SecretReference(scheme, key = null, environment = null, path = null, recursive = true, projectId = vault, connectionId = connection)
        }
        require(segments.size in 3..4) {
            when {
                raw.size == 2 && raw[1] == "*" -> "Reference '$original' names no item: write op://$vault/<item>/* for every field of an item, or op://$vault/** for every item"
                segments.size < 3 -> "Reference '$original' needs a vault, an item and a field: op://vault/item/[section/]field"
                else -> "Reference '$original' has too many segments: op://vault/item/[section/]field"
            }
        }
        val last = segments.last()
        require(raw.last() != "**") { "In '$original', ** is only for a whole vault (op://$vault/**); use * for every field of an item" }
        return SecretReference(
            scheme = scheme,
            key = last.takeIf { raw.last() != "*" },
            environment = null,
            path = segments.subList(1, segments.size - 1).joinToString("/", prefix = "/"),
            recursive = false,
            projectId = vault,
            connectionId = connection,
        )
    }

    override fun format(reference: SecretReference): String = buildString {
        append(reference.scheme).append("://")
        append(encode(reference.projectId ?: "?"))
        val path = SecretScope.normalizePath(reference.path).split('/').filter { it.isNotEmpty() }
        if (path.isEmpty()) {
            // The vault itself: only "every item" can be named there
            append("/**")
        } else {
            path.forEach { append('/').append(encode(it)) }
            append('/').append(reference.key?.let(::encode) ?: "*")
        }
        append(ReferenceSyntax.formatQuery(listOfNotNull(reference.connectionId?.let { "connection" to it })))
    }

    override fun build(scheme: String, scope: SecretScope, key: String?, defaults: SecretReference.Defaults?): SecretReference {
        val root = SecretScope.normalizePath(scope.path) == "/"
        return SecretReference(
            scheme = scheme,
            key = key,
            environment = null,
            path = scope.path.takeIf { !root },
            // A vault's root holds no fields: the only import there is every item
            recursive = key == null && root,
            // The vault is part of every 1Password reference
            projectId = scope.projectId,
        )
    }

    /** Characters 1Password allows in a reference's names; everything else is percent-encoded. */
    private fun allowedRaw(c: Char) = c.isLetterOrDigit() || c in "-_. "

    fun encode(segment: String): String = when {
        segment == "*" || segment == "**" -> segment.replace("*", "%2A")
        segment.all(::allowedRaw) && segment == segment.trim() -> segment
        else -> buildString {
            for (c in segment) {
                if (allowedRaw(c) && c != ' ') append(c)
                else if (c == ' ') append(' ')
                else c.toString().toByteArray(StandardCharsets.UTF_8).forEach { append('%').append("%02X".format(it.toInt() and 0xFF)) }
            }
        }.let { encoded ->
            // Leading or trailing spaces must be encoded, or they'd be lost
            val lead = encoded.takeWhile { it == ' ' }.length
            val trail = encoded.takeLastWhile { it == ' ' }.length
            if (lead == 0 && trail == 0) encoded
            else "%20".repeat(lead) + encoded.substring(lead, encoded.length - trail) + "%20".repeat(trail)
        }
    }

    private fun decode(segment: String, original: String): String =
        if ('%' !in segment) segment
        else try {
            URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Reference '$original' has a malformed %-escape in '$segment'", e)
        }
}
