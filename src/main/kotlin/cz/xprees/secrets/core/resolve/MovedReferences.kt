package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference

/**
 * Rewrites references to secrets that were moved (to another folder or environment) or renamed, so that run configurations,
 * `.env` files and other places keep pointing at the same secret. Pure text logic; [cz.xprees.secrets.ui.secrets.ReferenceUpdater]
 * finds the places and applies the changes (undoably).
 */
class MovedReferences(
    /** Reference scheme of the provider whose secrets moved (`infisical`, `vault`, …). */
    private val scheme: String,
    /** Keys that moved (their names before a rename). */
    private val keys: Set<String>,
    private val source: SecretScope,
    private val destination: SecretScope,
    /** Whether a reference naming this connection (`?connection=`), or none (null: the bound one), is the moved connection's. */
    private val isMovedConnection: (String?) -> Boolean,
    /** New names of renamed keys (old -> new); a key not in here keeps its name. */
    private val renames: Map<String, String> = emptyMap(),
    /** Other names of the source project a reference may use (1Password: the vault's name besides its ID). */
    private val projectAliases: Set<String> = emptySet(),
    /**
     * Whether [reference] follows the selected environment (names none, and nothing fixes one): such a reference reads
     * another environment when the selection changes, where the secret wasn't moved or renamed, so it is only rewritten
     * with [includeFollowing].
     */
    private val followsSelection: (SecretReference) -> Boolean = { false },
    private val includeFollowing: Boolean = true,
    /**
     * The same source and destination folders as references written in another form name them (Vault with an environment
     * layout: by mount and full path, see [cz.xprees.secrets.core.provider.SecretProvider.equivalentScope]): a reference to
     * one of these sources is rewritten to its destination.
     */
    private val alsoNamed: List<Pair<SecretScope, SecretScope>> = emptyList(),
) {
    /** References to a moved secret left as they were because they follow the selected environment (see [followsSelection]). */
    var followingSkipped: Int = 0
        private set

    /** The reference that names the same secret after the move, or null when [reference] isn't to a moved secret. */
    fun rewrite(reference: SecretReference, defaults: SecretReference.Defaults): SecretReference? {
        if (!reference.scheme.equals(scheme, ignoreCase = true)) return null
        val key = reference.key ?: return null // a scope import names a folder, not the moved secrets
        if (key !in keys || !isMovedConnection(reference.connectionId)) return null
        val scope = reference.scope(defaults)
        val (source, destination) = (listOf(source to destination) + alsoNamed).firstOrNull { (from, _) ->
            val sameProject = scope.projectId == from.projectId || projectAliases.any { it.equals(scope.projectId, ignoreCase = true) }
            sameProject && scope.environment == from.environment && scope.path == from.path
        } ?: return null
        if (!includeFollowing && followsSelection(reference)) {
            followingSkipped++
            return null
        }
        val grammar = SecretReference.grammarFor(reference.scheme)
        var moved = grammar.build(reference.scheme, destination.copy(recursive = false), renames[key] ?: key, defaults).copy(connectionId = reference.connectionId)
        // A grammar that names the destination in another form than its scope (Vault: another layout project by its full path)
        // wrote it completely; the rules below assume the scope's terms
        if (moved.projectId != null && moved.projectId != destination.projectId) return moved
        // Keep the reference's own form where the move doesn't change it (the grammar leaves out what the defaults say):
        // an explicit environment stays explicit, one left out stays out (it follows the selection or the configuration),
        // and a project named (perhaps by name, not ID) stays named as written
        val sameEnvironment = destination.environment == source.environment
        moved = when {
            reference.environment != null -> moved.copy(environment = destination.environment)
            sameEnvironment -> moved.copy(environment = null)
            else -> moved
        }
        val projectKept = destination.projectId == source.projectId
        moved = when {
            projectKept && reference.projectId != null -> moved.copy(projectId = reference.projectId)
            projectKept -> moved.copy(projectId = null)
            reference.projectId != null && moved.projectId == null -> moved.copy(projectId = destination.projectId)
            else -> moved
        }
        return moved
    }

    /** One replacement in a text: [start] until [end] (exclusive) becomes [replacement]. */
    data class Edit(val start: Int, val end: Int, val old: String, val replacement: String)

    /** The edits that update every reference to a moved secret in [text] (a `.env` file, a variable's value, …). */
    fun edits(text: CharSequence, defaults: SecretReference.Defaults): List<Edit> = edits(text) { defaults }

    /**
     * Like [edits], with defaults per reference ([defaultsFor]: null when the reference reads another connection, so it
     * can't be to a moved secret).
     */
    fun edits(text: CharSequence, defaultsFor: (SecretReference) -> SecretReference.Defaults?): List<Edit> =
        EnvReferences.find(text, listOf(scheme)).filter { it.kind == EnvReferences.Kind.REFERENCE }.mapNotNull { found ->
            val reference = found.reference ?: return@mapNotNull null
            val defaults = defaultsFor(reference) ?: return@mapNotNull null
            val moved = rewrite(reference, defaults) ?: return@mapNotNull null
            Edit(found.start, found.end, text.subSequence(found.start, found.end).toString(), moved.format())
        }.filter { it.old != it.replacement }

    /** [text] with [edits] applied (they must not overlap). */
    fun apply(text: String, edits: List<Edit>): String {
        val builder = StringBuilder(text)
        edits.sortedByDescending { it.start }.forEach { builder.replace(it.start, it.end, it.replacement) }
        return builder.toString()
    }

    /** [text] with every reference to a moved secret updated, or null when nothing changes. */
    fun rewriteText(text: String, defaults: SecretReference.Defaults): String? = rewriteText(text) { defaults }

    /** Like [rewriteText], with defaults per reference (see [edits]). */
    fun rewriteText(text: String, defaultsFor: (SecretReference) -> SecretReference.Defaults?): String? {
        val edits = edits(text, defaultsFor)
        return if (edits.isEmpty()) null else apply(text, edits)
    }

    companion object {
        /** References to [oldKey] of the folder of [scope], renamed to [newKey] (same folder and environment). */
        fun renamed(scheme: String, scope: SecretScope, oldKey: String, newKey: String, isMovedConnection: (String?) -> Boolean) =
            MovedReferences(scheme, setOf(oldKey), scope, scope, isMovedConnection, mapOf(oldKey to newKey))
    }
}
