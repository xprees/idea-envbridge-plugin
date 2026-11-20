package cz.xprees.secrets.core.reference

import cz.xprees.secrets.core.model.SecretScope

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

    /** Canonical text form in the provider's grammar; [parse] of it gives an equal reference. */
    fun format(): String = grammarFor(scheme).format(this)

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
            return grammarFor(scheme).parse(scheme, match.groupValues[2], text)
        }

        /** Grammars set by tests (the IDE's extension point isn't there in plain unit tests). */
        val grammarOverrides: MutableMap<String, ReferenceGrammar> = java.util.concurrent.ConcurrentHashMap()

        /** The grammar of [scheme]: its provider's, or the path grammar. */
        fun grammarFor(scheme: String): ReferenceGrammar =
            grammarOverrides[scheme.lowercase()]
                ?: runCatching { cz.xprees.secrets.core.provider.SecretProvider.byScheme(scheme)?.referenceGrammar }.getOrNull()
                ?: PathGrammar
    }
}
