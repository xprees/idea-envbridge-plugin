package cz.xprees.secrets.vault

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.ReferenceGrammar
import cz.xprees.secrets.core.reference.ReferenceSyntax
import cz.xprees.secrets.core.reference.SecretReference

/**
 * Vault / OpenBao references, written the way Vault users name secrets (`vault kv get secret/app/db`):
 *
 * ```
 * vault://secret/app/db#password       full form: field password of the secret app/db in the mount secret
 * vault://secret/app/db#(star)         every field of that secret (scope import)
 * vault://secret/app#(star)(star)      every field of every secret below app (and app itself)
 * vault://secret/app/db#password?namespace=team-a&connection=<name>
 * vault://#password                    short form: the bound project's own path in the selected environment
 * vault://./db#password                short form: folder /db of the bound project in the selected environment
 * vault://./db#password?env=prod       short form with the environment fixed (?project= names a layout project)
 * ```
 *
 * Full form: the first segment is the mount (a mount with a slash in its name is written with `%2F`), the rest is the secret's
 * path; the namespace comes from the binding (or, with an environment layout, the connection) unless given. Short form: no
 * path, or a first segment `.`; it reads relative to the binding and, on a connection with a [VaultEnvironmentLayout], follows
 * the selected environment. (star) stands for `*`. See `docs/DESIGN.md`, "Environment layout".
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
        // Vault paths may contain spaces, written encoded (%20); raw whitespace is a typo
        require(pathPart.none(Char::isWhitespace) && segments.none { it.isBlank() }) { "Reference '$original' contains whitespace" }
        val field = ReferenceSyntax.decodeSegment(fieldPart)
        require(field.isNotEmpty()) { "Reference '$original' names no field: add a field after #, or * for every field" }
        val isScope = field == "*" || field == "**"
        // The short form: no path at all, or a raw first segment "." (a full reference never had one: . was always refused)
        if (pathPart.isEmpty()) return parseShort(scheme, original, emptyList(), field, isScope, query)
        if (pathPart.split('/').first() == ".") return parseShort(scheme, original, segments.drop(1), field, isScope, query)

        require(segments.size >= 2) { "Reference '$original' needs a mount and a secret path, as in vault://secret/app/db#field" }
        require(segments.none { it == "." || it == ".." }) { "Reference '$original' contains . or .. (write the secret's full path)" }
        require("env" !in query && "project" !in query) {
            "In '$original', ?env= and ?project= belong to the short form (vault://./path#field): a full path already names its secret"
        }
        val unknown = query.keys - setOf("namespace", "connection")
        require(unknown.isEmpty()) { "Unknown parameter(s) ${unknown.joinToString()} in '$original'" }
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

    /** `vault://#field`, `vault://./folder#field`: relative to the binding (and the selected environment, with a layout). */
    private fun parseShort(
        scheme: String,
        original: String,
        folder: List<String>,
        field: String,
        isScope: Boolean,
        query: Map<String, String>,
    ): SecretReference {
        require(folder.none { it == "." || it == ".." }) {
            "Reference '$original' contains . or .. after ./ (write the folder's path from the environment's root)"
        }
        require("namespace" !in query) {
            "In '$original', ?namespace= belongs to the full form (vault://<mount>/<path>#field); the short form reads in the " +
                "connection's namespace, or names an environment with ?env="
        }
        val unknown = query.keys - setOf("env", "project", "connection")
        require(unknown.isEmpty()) { "Unknown parameter(s) ${unknown.joinToString()} in '$original'" }
        val environment = query["env"]?.trim()?.ifEmpty { null }
        val project = query["project"]?.trim()?.ifEmpty { null }?.also {
            require(VaultLayoutProject.isLayoutId(it)) {
                "In '$original', ?project=$it isn't an environment layout project (as in secret/myapp/{env}); for a mount, write its full path"
            }
        }
        return SecretReference(
            scheme = scheme,
            key = if (isScope) null else field,
            environment = environment,
            path = folder.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "/"),
            recursive = field == "**",
            projectId = project,
            connectionId = query["connection"]?.ifBlank { null },
        )
    }

    /** Whether [reference] is in the short form (no project, or a layout project) rather than a mount and a path. */
    fun isShort(reference: SecretReference): Boolean = reference.projectId == null || VaultLayoutProject.isLayoutId(reference.projectId)

    override fun format(reference: SecretReference): String = buildString {
        append(reference.scheme).append("://")
        val short = isShort(reference)
        val segments = SecretScope.normalizePath(reference.path).split('/').filter { it.isNotEmpty() }.map(ReferenceSyntax::encodeSegment)
        if (short) {
            if (segments.isNotEmpty()) append("./").append(segments.joinToString("/"))
        } else {
            append(ReferenceSyntax.encodeSegment(reference.projectId!!)).append('/').append(segments.joinToString("/"))
        }
        append('#').append(reference.key?.let(ReferenceSyntax::encodeSegment) ?: if (reference.recursive) "**" else "*")
        val query = ReferenceSyntax.formatQuery(
            buildList {
                if (short) {
                    reference.environment?.let { add("env" to it) }
                    reference.projectId?.let { add("project" to it) }
                } else {
                    reference.environment?.let { add("namespace" to it) }
                }
                reference.connectionId?.let { add("connection" to it) }
            },
        )
        // A layout project's {env} reads better unencoded, and parses the same
        append(query.replace("%7B", "{").replace("%7D", "}"))
    }

    /**
     * Mount scopes: the mount is always written (the reference alone says which secret it is), the namespace only when it isn't
     * the bound one. Layout scopes ([VaultLayoutProject]): the short form in the bound project (the environment left out when it
     * is the bound one, so the reference follows the selection), else the full path, readable without knowing the layout.
     */
    override fun build(scheme: String, scope: SecretScope, key: String?, defaults: SecretReference.Defaults?): SecretReference {
        val recursive = key == null && scope.recursive
        val layout = VaultLayoutProject.parse(scope.projectId)
            ?: return SecretReference(
                scheme = scheme,
                key = key,
                environment = scope.environment.takeIf { it != defaults?.environment },
                path = scope.path.takeIf { it != "/" },
                recursive = recursive,
                projectId = scope.projectId,
            )
        if (defaults != null && defaults.projectId == scope.projectId) {
            return SecretReference(
                scheme = scheme,
                key = key,
                environment = scope.environment.takeIf { it != defaults.environment },
                path = scope.path.takeIf { it != "/" },
                recursive = recursive,
            )
        }
        return SecretReference(
            scheme = scheme,
            key = key,
            path = SecretScope.normalizePath(layout.basePath(scope.environment) + scope.path),
            recursive = recursive,
            projectId = layout.mount,
        )
    }

    /** `/team-a/` -> `team-a`; blank and `root` -> [VaultApi.ROOT_NAMESPACE]. */
    fun normalizeNamespace(namespace: String?): String {
        val trimmed = namespace.orEmpty().trim().trim('/')
        return if (trimmed.isEmpty() || trimmed.equals(VaultApi.ROOT_NAMESPACE, ignoreCase = true)) VaultApi.ROOT_NAMESPACE else trimmed
    }
}
