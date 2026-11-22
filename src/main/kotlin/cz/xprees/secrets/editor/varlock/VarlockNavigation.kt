package cz.xprees.secrets.editor.varlock

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.editor.varlock.VarlockTokens.Kind

/**
 * Ctrl+click / Go to Declaration in varlock mode ([VarlockMode]): from `$KEY`, `${KEY}`, `ref(KEY)` and `@currentEnv=$KEY` to
 * the key's definitions in the layered files (`.env.schema` first), and from `@import(./.env.other)` to that file or directory.
 * Text-based, so it works whatever language the file has.
 */
class VarlockGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        if (!VarlockMode.isActive(file)) return null
        return targets(file, offset).takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    companion object {
        /** What the reference or import at [offset] of [file] leads to. */
        fun targets(file: PsiFile, offset: Int): List<PsiElement> {
            val tokens = VarlockTokens.scan(VarlockMode.parsed(file), file.viewProvider.contents)
            val token = VarlockTokens.at(tokens, offset, setOf(Kind.REFERENCE, Kind.IMPORT)) ?: return emptyList()
            if (token.kind == Kind.IMPORT) {
                val own = file.originalFile.virtualFile ?: return emptyList()
                val target = VarlockContext.resolveImport(own, token.name) ?: return emptyList()
                val manager = PsiManager.getInstance(file.project)
                return listOfNotNull(if (target.isDirectory) manager.findDirectory(target) else manager.findFile(target))
            }
            return VarlockContext.of(file).keys[token.name].orEmpty().map { KeyTarget(it.file, it.range, token.name) }
        }
    }

    /** A key's definition in a `.env` file, navigable to its offset. */
    class KeyTarget(private val file: PsiFile, private val range: TextRange, private val key: String) : FakePsiElement() {
        override fun getParent(): PsiElement = file

        override fun getContainingFile(): PsiFile = file

        override fun getTextRange(): TextRange = range

        override fun getTextOffset(): Int = range.startOffset

        override fun getName(): String = key

        override fun getPresentableText(): String = key

        override fun getLocationString(): String = file.name

        override fun canNavigate(): Boolean = file.virtualFile != null

        override fun navigate(requestFocus: Boolean) {
            val virtualFile = file.virtualFile ?: return
            OpenFileDescriptor(file.project, virtualFile, range.startOffset).navigate(requestFocus)
        }

        override fun isValid(): Boolean = file.isValid
    }
}

/**
 * Enter inside a comment of a `.env` file in varlock mode ([VarlockMode]) continues the comment with `# `, so a decorator block
 * (`# @required` / `# @type=…` / description lines) can be written line by line. Not after a divider or an empty `#` line, and
 * not when the language already continued it.
 */
class VarlockEnterHandler : EnterHandlerDelegateAdapter() {
    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (!VarlockMode.isActive(file)) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val caret = editor.caretModel.offset
        val line = document.getLineNumber(caret)
        if (line == 0) return EnterHandlerDelegate.Result.Continue
        val text = document.charsSequence
        val previous = text.subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).toString()
        val trimmed = previous.trimStart()
        if (!trimmed.startsWith("#")) return EnterHandlerDelegate.Result.Continue
        val body = trimmed.substring(1)
        if (body.isBlank() || EnvSpecParser.isDivider(body)) return EnterHandlerDelegate.Result.Continue
        val lineStart = document.getLineStartOffset(line)
        val before = text.subSequence(lineStart, caret)
        val after = text.subSequence(caret, document.getLineEndOffset(line)).toString()
        if (before.isNotBlank() || after.trimStart().startsWith("#")) return EnterHandlerDelegate.Result.Continue
        val indent = previous.substring(0, previous.length - trimmed.length)
        document.replaceString(lineStart, caret, "$indent# ")
        editor.caretModel.moveToOffset(lineStart + indent.length + 2)
        return EnterHandlerDelegate.Result.Continue
    }
}
