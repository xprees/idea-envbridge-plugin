package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference

/**
 * Rewrites references to secrets that were moved (to another folder or environment), so that run configurations, `.env` files
 * and other places keep pointing at the same secret. Pure text logic; [cz.xprees.secrets.ui.secrets.ReferenceUpdater] finds
 * the places and applies the changes (undoably).
 */
class MovedReferences(
    /** Reference scheme of the provider whose secrets moved (`infisical`, `vault`, …). */
    private val scheme: String,
    /** Keys that moved. */
    private val keys: Set<String>,
    private val source: SecretScope,
    private val destination: SecretScope,
    /** Whether a reference naming this connection (`?connection=`), or none (null: the bound one), is the moved connection's. */
    private val isMovedConnection: (String?) -> Boolean,
) {
    /** The reference that names the same secret after the move, or null when [reference] isn't to a moved secret. */
    fun rewrite(reference: SecretReference, defaults: SecretReference.Defaults): SecretReference? {
        if (!reference.scheme.equals(scheme, ignoreCase = true)) return null
        val key = reference.key ?: return null // a scope import names a folder, not the moved secrets
        if (key !in keys || !isMovedConnection(reference.connectionId)) return null
        val scope = reference.scope(defaults)
        if (scope.projectId != source.projectId || scope.environment != source.environment || scope.path != source.path) return null
        val grammar = SecretReference.grammarFor(reference.scheme)
        var moved = grammar.build(reference.scheme, destination.copy(recursive = false), key, defaults).copy(connectionId = reference.connectionId)
        // Keep naming the project when the reference did (the grammar leaves out what the binding says)
        if (reference.projectId != null && moved.projectId == null) moved = moved.copy(projectId = reference.projectId)
        return moved
    }

    /** One replacement in a text: [start] until [end] (exclusive) becomes [replacement]. */
    data class Edit(val start: Int, val end: Int, val old: String, val replacement: String)

    /** The edits that update every reference to a moved secret in [text] (a `.env` file, a variable's value, …). */
    fun edits(text: CharSequence, defaults: SecretReference.Defaults): List<Edit> =
        EnvReferences.find(text, listOf(scheme)).filter { it.kind == EnvReferences.Kind.REFERENCE }.mapNotNull { found ->
            val reference = found.reference ?: return@mapNotNull null
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
    fun rewriteText(text: String, defaults: SecretReference.Defaults): String? {
        val edits = edits(text, defaults)
        return if (edits.isEmpty()) null else apply(text, edits)
    }
}
