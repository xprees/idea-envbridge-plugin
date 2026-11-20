package cz.xprees.secrets.editor

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.KeysIndex

/**
 * Completes secret keys wherever code reads an environment variable (`System.getenv("…")`, `process.env.…`,
 * `os.environ["…"]`, `os.Getenv("…")`, `${…}`, …) and after `infisical://`, in every language.
 */
class SecretKeyCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val editor = parameters.editor
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

/** Opens the completion popup right after `process.env.`, `getenv("`, `${`, `infisical://` and the like. */
class SecretKeyAutoPopup : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped !in TRIGGERS) return Result.CONTINUE
        val prefix = SecretKeyCompletionContributor.linePrefix(editor, editor.caretModel.offset) + charTyped
        if (EnvReferences.completionPrefix(prefix, EditorReferences.schemes()) == "" &&
            KeysIndex.getInstance(project).current()?.boundKeys?.isNotEmpty() == true
        ) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }

    private companion object {
        val TRIGGERS = setOf('.', '"', '\'', '{', '/', '[')
    }
}
