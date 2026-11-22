package cz.xprees.secrets.editor

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.reference.ReferenceCompletion
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.KeysIndex

/**
 * Completes secret keys wherever code reads an environment variable (`System.getenv("…")`, `process.env.…`,
 * `os.environ["…"]`, `os.Getenv("…")`, `${…}`, …) and after `infisical://`, in every language; inside `op://` and `vault://`
 * references the vault / mount, items / path segments and fields as their grammar writes them. Names and keys only.
 */
class SecretKeyCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val editor = parameters.editor
        EnvReferences.referenceCompletion(linePrefix(editor, parameters.offset), EditorReferences.schemes())?.let { (scheme, context) ->
            val snapshot = KeysIndex.getInstance(parameters.originalFile.project).current() ?: return
            if (scheme.equals(snapshot.scheme, ignoreCase = true)) {
                completeReference(context, snapshot, result)
                // Inside a reference, words of the file aren't names of secrets
                result.stopHere()
            }
            return
        }
        val prefix = EnvReferences.completionPrefix(linePrefix(editor, parameters.offset), EditorReferences.schemes()) ?: return
        val snapshot = KeysIndex.getInstance(parameters.originalFile.project).current() ?: return
        val matching = result.withPrefixMatcher(prefix)
        val linePrefix = linePrefix(editor, parameters.offset)
        // Inside a reference: what is typed before the key (environment prefix and folders)
        val typed = EditorReferences.schemes().firstNotNullOfOrNull { scheme ->
            Regex("(?i)\\b${Regex.escape(scheme)}://(?:([A-Za-z0-9_.\\-]+):)?((?:[^\\s\"'?/:]+/)*)[A-Za-z0-9_.\\-]*$").find(linePrefix)
        }
        val typedEnvironment = typed?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        val typedFolder = typed?.groupValues?.get(2)?.trimEnd('/')?.let { if (it.isEmpty()) null else "/$it" }
        val inBoundEnvironment = typedEnvironment == null || typedEnvironment == snapshot.boundEnvironment
        if (typed != null && typedFolder != null) {
            // infisical://workers/… : the keys of that folder only
            if (inBoundEnvironment) {
                snapshot.folders[typedFolder].orEmpty().values.forEach { info -> matching.addElement(keyElement(info.key, info.comment, typedFolder, snapshot)) }
            }
            return
        }
        // The root (what a bare reference reads), each key with its folder
        for (info in snapshot.environments[typedEnvironment ?: snapshot.boundEnvironment].orEmpty().values) {
            matching.addElement(keyElement(info.key, info.comment, "/", snapshot, typedEnvironment, info.origin == SecretOrigin.IMPORTED))
        }
        if (typed != null && inBoundEnvironment) {
            // Keys of the other folders, inserted with their folder: infisical://workers/KEY
            for ((folder, keys) in snapshot.folders) {
                if (folder == "/") continue
                keys.values.forEach { info -> matching.addElement(keyElement(info.key, info.comment, folder, snapshot, insertFolder = true)) }
            }
        }
        if (typed != null) return
        // Keys that exist only in other environments, so a typo and a missing secret look different
        val bound = snapshot.boundKeys.keys
        snapshot.environments.filterKeys { it != snapshot.boundEnvironment }.values.flatMap { it.keys }.toSet()
            .filter { it !in bound }
            .forEach { key ->
                matching.addElement(
                    LookupElementBuilder.create(key).withIcon(SecretsIcons.PROVIDER).withStrikeoutness(true)
                        .withTypeText(SecretsBundle.message("completion.other.environments", snapshot.coverage(key).joinToString()), true),
                )
            }
    }

    /**
     * Names inside a reference whose grammar completes them ([ReferenceCompletion]): projects as listed, folders and keys as the
     * keys index knows them (the bound project only; another project gets nothing, not a guess).
     */
    private fun completeReference(context: ReferenceCompletion, snapshot: KeysIndex.Snapshot, result: CompletionResultSet) {
        val grammar = SecretReference.grammarFor(snapshot.scheme)
        // Names may contain spaces (1Password): a plain, case-insensitive prefix match on what is typed
        val matching = result.withPrefixMatcher(PlainPrefixMatcher(context.prefix, true))
        when (context) {
            is ReferenceCompletion.Project -> snapshot.projectNames.forEachIndexed { index, name ->
                matching.addElement(
                    LookupElementBuilder.create(grammar.formatSegment(name)).withLookupString(name).withPresentableText(name).withIcon(SecretsIcons.PROVIDER)
                        .withBoldness(index == 0 && snapshot.namesProject(name))
                        .withTypeText(snapshot.connectionName, true),
                )
            }
            is ReferenceCompletion.Folder -> {
                if (context.project != null && !snapshot.namesProject(context.project)) return
                val parent = snapshot.canonicalFolder(context.parent)
                val below = if (parent == "/") "/" else "$parent/"
                snapshot.folders.keys.filter { it.startsWith(below) && it.length > below.length }
                    .map { it.substring(below.length).substringBefore('/') }
                    .distinct()
                    .forEach { segment ->
                        matching.addElement(
                            LookupElementBuilder.create(grammar.formatSegment(segment)).withLookupString(segment).withPresentableText(segment)
                                .withIcon(AllIcons.Nodes.Folder)
                                .withTailText("  $below$segment", true)
                                .withTypeText(snapshot.connectionName, true),
                        )
                    }
            }
            is ReferenceCompletion.Key -> {
                if (context.project != null && !snapshot.namesProject(context.project)) return
                val keys = context.folder?.let(snapshot::keysOf) ?: snapshot.boundKeys
                for (info in keys.values) {
                    matching.addElement(
                        LookupElementBuilder.create(grammar.formatSegment(info.key))
                            .withLookupString(info.key)
                            .withPresentableText(info.key)
                            .withIcon(SecretsIcons.PROVIDER)
                            .withTailText("  ${info.path}" + (info.comment.lines().firstOrNull()?.takeIf { it.isNotBlank() }?.let { "  $it" } ?: ""), true)
                            .withTypeText(SecretsBundle.message("completion.type", snapshot.boundEnvironment), true),
                    )
                }
            }
        }
    }

    /** A key with its folder (and environment) shown; [insertFolder] inserts `folder/KEY` instead of the key alone. */
    private fun keyElement(
        key: String,
        comment: String,
        folder: String,
        snapshot: KeysIndex.Snapshot,
        environment: String? = null,
        italic: Boolean = false,
        insertFolder: Boolean = false,
    ) = LookupElementBuilder.create(if (insertFolder) folder.trimStart('/') + "/" + key else key)
        .withLookupString(key)
        .withPresentableText(key)
        .withIcon(SecretsIcons.PROVIDER)
        .withTailText("  $folder" + (comment.lines().firstOrNull()?.takeIf { it.isNotBlank() }?.let { "  $it" } ?: ""), true)
        .withTypeText(SecretsBundle.message("completion.type", environment ?: snapshot.boundEnvironment), true)
        .withItemTextItalic(italic)

    companion object {
        fun linePrefix(editor: Editor, offset: Int): String {
            val document = editor.document
            val lineStart = document.getLineStartOffset(document.getLineNumber(offset.coerceAtMost(document.textLength)))
            return document.charsSequence.subSequence(lineStart, offset.coerceAtMost(document.textLength)).toString()
        }
    }
}

/** Opens the completion popup right after `process.env.`, `getenv("`, `${`, `infisical://`, `op://Dev/`, `vault://./db#` and the like. */
class SecretKeyAutoPopup : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped !in TRIGGERS) return Result.CONTINUE
        val prefix = SecretKeyCompletionContributor.linePrefix(editor, editor.caretModel.offset) + charTyped
        // Inside op:// or vault:// references: after a `/` or `#` that starts a name
        EnvReferences.referenceCompletion(prefix, EditorReferences.schemes())?.let { (scheme, context) ->
            val snapshot = KeysIndex.getInstance(project).current()
            if (context.prefix.isEmpty() && snapshot != null && scheme.equals(snapshot.scheme, ignoreCase = true)) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
            return Result.CONTINUE
        }
        if (EnvReferences.completionPrefix(prefix, EditorReferences.schemes()) == "" &&
            KeysIndex.getInstance(project).current()?.boundKeys?.isNotEmpty() == true
        ) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }

    private companion object {
        val TRIGGERS = setOf('.', '"', '\'', '{', '/', '[', '#')
    }
}
