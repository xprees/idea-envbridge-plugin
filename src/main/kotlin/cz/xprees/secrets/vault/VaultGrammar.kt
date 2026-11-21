package cz.xprees.secrets.vault

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.ReferenceGrammar
import cz.xprees.secrets.core.reference.ReferenceSyntax
import cz.xprees.secrets.core.reference.SecretReference

/**
 * Vault / OpenBao references, written the way Vault users name secrets (`vault kv get secret/app/db`):
 *
 * ```
 * vault://secret/app/db#password       field password of the secret app/db in the KV v2 mount secret
 * vault://secret/app/db#(star)         every field of that secret (scope import)
 * vault://secret/app#(star)(star)      every field of every secret below app (and app itself)
 * vault://secret/app/db#password?namespace=team-a&connection=<name>
 * ```
 *
 * The first segment is the mount (a mount with a slash in its name is written with `%2F`), the rest is the secret's path.
 * The namespace (the EnvBridge environment) comes from the binding unless given. (star) stands for `*`.
 */
object VaultGrammar : ReferenceGrammar {
    override fun parse(scheme: String, body: String, original: String): SecretReference {
        val hash = body.indexOf('#')
        require(hash >= 0) { "Reference '$original' names no field: add #field, or #* for every field of the secret" }
        // The query may follow the path or the field: vault://m/p?namespace=x#f and vault://m/p#f?namespace=x
        val (pathPart, pathQuery) = body.substring(0, hash).split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val (fieldPart, fieldQuery) = body.substring(hash + 1).split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val query = ReferenceSyntax.parseQuery(pathQuery) + ReferenceSyntax.parseQuery(fieldQuery)

        val segments = pathPart.split('/').filter { it.isNotEmpty() }.map(ReferenceSyntax::decodeSegment)
        require(segments.size >= 2) { "Reference '$original' needs a mount and a secret path, as in vault://secret/app/db#field" }
        // Vault paths may contain spaces, written encoded (%20); raw whitespace is a typo
        require(pathPart.none(Char::isWhitespace) && segments.none { it.isBlank() }) { "Reference '$original' contains whitespace" }
        require(segments.none { it == "." || it == ".." }) { "Reference '$original' contains . or .. (write the secret's full path)" }
        val field = ReferenceSyntax.decodeSegment(fieldPart)
        require(field.isNotEmpty()) { "Reference '$original' names no field: add a field after #, or * for every field" }

        val unknown = query.keys - setOf("namespace", "connection")
        require(unknown.isEmpty()) { "Unknown parameter(s) ${unknown.joinToString()} in '$original'" }
        val isScope = field == "*" || field == "**"
        return SecretReference(
            scheme = scheme,
            key = if (isScope) null else field,
            environment = query["namespace"]?.let(::normalizeNamespace),
            path = segments.drop(1).joinToString("/", prefix = "/"),
            recursive = field == "**",
            projectId = segments.first(),
            connectionId = query["connection"]?.ifBlank { null },
        )
    }

    override fun format(reference: SecretReference): String = buildString {
        append(reference.scheme).append("://")
        reference.projectId?.let { append(ReferenceSyntax.encodeSegment(it)).append('/') }
        append(SecretScope.normalizePath(reference.path).split('/').filter { it.isNotEmpty() }.joinToString("/") { ReferenceSyntax.encodeSegment(it) })
        append('#').append(reference.key?.let(ReferenceSyntax::encodeSegment) ?: if (reference.recursive) "**" else "*")
        append(
            ReferenceSyntax.formatQuery(
                buildList {
                    reference.environment?.let { add("namespace" to it) }
                    reference.connectionId?.let { add("connection" to it) }
                },
            ),
        )
    }

    override fun build(scheme: String, scope: SecretScope, key: String?, defaults: SecretReference.Defaults?): SecretReference =
        SecretReference(
            scheme = scheme,
            key = key,
            environment = scope.environment.takeIf { it != defaults?.environment },
            path = scope.path.takeIf { it != "/" },
            recursive = key == null && scope.recursive,
            // The mount is always written: the reference alone says which secret it is
            projectId = scope.projectId,
        )

    /** `/team-a/` -> `team-a`; blank and `root` -> [VaultApi.ROOT_NAMESPACE]. */
    fun normalizeNamespace(namespace: String?): String {
        val trimmed = namespace.orEmpty().trim().trim('/')
        return if (trimmed.isEmpty() || trimmed.equals(VaultApi.ROOT_NAMESPACE, ignoreCase = true)) VaultApi.ROOT_NAMESPACE else trimmed
    }
}
