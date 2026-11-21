package cz.xprees.secrets.aws

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.ReferenceGrammar
import cz.xprees.secrets.core.reference.ReferenceSyntax
import cz.xprees.secrets.core.reference.SecretReference
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * How AWS secret names map to EnvBridge projects and environments: the **environment is the last segment** of the name, the
 * **project is everything before it** (the name prefix). `myapp/dev` is project `myapp`, environment `dev`;
 * `team/myapp/prod` is `team/myapp` / `prod`; `/prod/web` is `/prod` / `web`; a name without `/` belongs to the project
 * [NO_PREFIX]. The name is always rebuilt exactly by [join].
 */
object AwsSecretNames {
    /** Project of names without `/`. `~` can't occur in an AWS secret name, so it never clashes with a real prefix. */
    const val NO_PREFIX = "~"

    /** (project, environment) of [name], or null for names the mapping can't express (blank, ending with `/`, containing `//`). */
    fun split(name: String): Pair<String, String>? {
        if (name.isBlank() || name.endsWith('/') || "//" in name) return null
        val slash = name.lastIndexOf('/')
        return when {
            slash < 0 -> NO_PREFIX to name
            slash == 0 -> "/" to name.substring(1)
            else -> name.substring(0, slash) to name.substring(slash + 1)
        }
    }

    /** The secret name of [projectId] and [environment] (the inverse of [split]). */
    fun join(projectId: String, environment: String): String = when (projectId) {
        NO_PREFIX -> environment
        "/" -> "/$environment"
        else -> "$projectId/$environment"
    }

    /** Characters AWS allows in secret names besides letters and digits. */
    const val NAME_PUNCTUATION = "/_+=.@-"

    /** Whether [c] may appear in an AWS secret name. */
    fun isNameChar(c: Char): Boolean = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c in NAME_PUNCTUATION
}

/**
 * AWS Secrets Manager references. The secret is named as AWS users name it (`aws secretsmanager get-secret-value --secret-id
 * myapp/dev`), the key of its JSON object after `#`:
 *
 * ```
 * aws-sm://myapp/dev#DB_PASSWORD            key DB_PASSWORD of secret myapp/dev (project myapp, environment dev)
 * aws-sm://team/myapp/prod#DB_PASSWORD      project team/myapp, environment prod
 * aws-sm://myapp/dev#(star)                 every key of myapp/dev (scope import; (star)(star) means the same)
 * aws-sm://#DB_PASSWORD                     the bound secret: bound project and the selected environment
 * aws-sm://#DB_PASSWORD?env=prod            bound project, environment prod (?project= names the prefix)
 * aws-sm://myapp/dev#DB_PASSWORD?connection=Work
 * ```
 *
 * The full name fixes project and environment; `#KEY` alone follows the binding (and the environment picked in the status
 * bar). `?env=` / `?project=` only appear where the binding gives the rest, so they can't be combined with a name. Names are
 * written raw when they use only the characters AWS allows ([AwsSecretNames.isNameChar]); anything else is percent-encoded.
 * (star) stands for `*`.
 */
object AwsGrammar : ReferenceGrammar {
    override fun parse(scheme: String, body: String, original: String): SecretReference {
        val hash = body.indexOf('#')
        require(hash >= 0) { "Reference '$original' names no key: add #KEY, or #* for every key of the secret, as in $scheme://myapp/dev#DB_PASSWORD" }
        // The query may follow the name or the key: aws-sm://a/b?connection=x#K and aws-sm://a/b#K?connection=x
        val (namePart, nameQuery) = body.substring(0, hash).split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val (keyPart, keyQuery) = body.substring(hash + 1).split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val query = ReferenceSyntax.parseQuery(nameQuery) + ReferenceSyntax.parseQuery(keyQuery)
        val unknown = query.keys - setOf("connection", "env", "project")
        require(unknown.isEmpty()) { "Unknown parameter(s) ${unknown.joinToString()} in '$original' (a connection is one region: use ?connection= for another)" }
        require(namePart.none(Char::isWhitespace) && keyPart.none(Char::isWhitespace)) { "Reference '$original' contains whitespace" }

        val key = ReferenceSyntax.decodeSegment(keyPart)
        require(key.isNotEmpty()) { "Reference '$original' names no key: add a key after #, or * for every key" }
        // Only a raw star imports; %2A is a key called *
        val isScope = keyPart == "*" || keyPart == "**"

        val name = ReferenceSyntax.decodeSegment(namePart)
        var projectId: String? = null
        var environment: String? = null
        if (name.isNotEmpty()) {
            require(query["env"] == null && query["project"] == null) {
                "In '$original', ?env= and ?project= can't be combined with a secret name (the name already says both)"
            }
            require('*' !in namePart) { "In '$original', * may only follow # (every key of one secret)" }
            val (project, env) = AwsSecretNames.split(name)
                ?: throw IllegalArgumentException("Reference '$original' names no secret EnvBridge can map: a name may not end with / or contain //")
            projectId = project
            environment = env
        } else {
            environment = query["env"]?.trim()?.ifEmpty { null }?.also {
                require('/' !in it) { "In '$original', the environment ?env=$it can't contain / (it is the last segment of a secret name)" }
            }
            projectId = query["project"]?.trim()?.ifEmpty { null }
        }
        return SecretReference(
            scheme = scheme,
            key = if (isScope) null else key,
            environment = environment,
            path = null,
            recursive = false,
            projectId = projectId,
            connectionId = query["connection"]?.ifBlank { null },
        )
    }

    override fun format(reference: SecretReference): String = buildString {
        append(reference.scheme).append("://")
        val project = reference.projectId
        val environment = reference.environment
        val named = project != null && environment != null
        if (named) append(encodeName(AwsSecretNames.join(project!!, environment!!)))
        append('#').append(reference.key?.let(::encodeKey) ?: "*")
        append(
            ReferenceSyntax.formatQuery(
                buildList {
                    if (!named) {
                        environment?.let { add("env" to it) }
                        project?.let { add("project" to it) }
                    }
                    reference.connectionId?.let { add("connection" to it) }
                },
            ),
        )
    }

    /**
     * The full name, except for the bound secret itself (bound project and environment), which is written `#KEY` so it follows
     * the selected environment, as `doppler://KEY` and `infisical://KEY` do.
     */
    override fun build(scheme: String, scope: SecretScope, key: String?, defaults: SecretReference.Defaults?): SecretReference {
        val bound = defaults != null && defaults.projectId == scope.projectId && defaults.environment == scope.environment
        return SecretReference(
            scheme = scheme,
            key = key,
            environment = scope.environment.takeIf { !bound },
            path = null,
            recursive = false,
            projectId = scope.projectId.takeIf { !bound },
        )
    }

    /** A secret name, raw where AWS allows the characters, percent-encoded elsewhere (`!` of managed secrets, `#`, `?`, …). */
    fun encodeName(name: String): String = buildString {
        name.codePoints().forEach { cp ->
            val text = String(Character.toChars(cp))
            if (cp < 0x80 && AwsSecretNames.isNameChar(cp.toChar())) append(text)
            else append(URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"))
        }
    }

    /** A key: raw when it is a plain identifier, else percent-encoded; a key called `*` is written `%2A`. */
    fun encodeKey(key: String): String = if (key == "*" || key == "**") key.replace("*", "%2A") else ReferenceSyntax.encodeSegment(key)
}
