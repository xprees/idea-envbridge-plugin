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
        // Right after `infisical://` (no environment or folder typed): keys of every folder, each with its folder
        val bareReference = EditorReferences.schemes().any { Regex("(?i)\\b${Regex.escape(it)}://[A-Za-z0-9_.\\-]*$").containsMatchIn(linePrefix) }
        for (info in snapshot.boundKeys.values) {
            val elsewhere = bareReference && snapshot.foldersOf(info.key).any { it != snapshot.path }
            matching.addElement(
                LookupElementBuilder.create(info.key)
                    .withIcon(SecretsIcons.PROVIDER)
                    .withTypeText(SecretsBundle.message("completion.type", snapshot.boundEnvironment) + if (elsewhere) "  ${snapshot.path}" else "", true)
                    .withTailText(info.comment.lines().firstOrNull()?.takeIf { it.isNotBlank() }?.let { "  $it" }, true)
                    .withItemTextItalic(info.origin == SecretOrigin.IMPORTED),
            )
        }
        if (bareReference) {
            for ((folder, keys) in snapshot.folders) {
                if (folder == snapshot.path) continue
                for (info in keys.values) {
                    // Inserts KEY?path=/folder: the reference then names its folder and can't pick up a same-named key elsewhere
                    matching.addElement(
                        LookupElementBuilder.create("${info.key}?path=$folder")
                            .withPresentableText(info.key)
                            .withLookupString(info.key)
                            .withIcon(SecretsIcons.PROVIDER)
                            .withTailText("  $folder", true)
                            .withTypeText(SecretsBundle.message("completion.type", snapshot.boundEnvironment) + "  $folder", true),
                    )
                }
            }
        }
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
