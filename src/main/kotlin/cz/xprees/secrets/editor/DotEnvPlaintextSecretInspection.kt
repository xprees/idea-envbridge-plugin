package cz.xprees.secrets.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.DotEnv
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver

/**
 * `.env` files: a plaintext value of a variable that exists as a secret in the bound folder is reported. The quick fixes bind
 * the variable with a comment, `KEY= # infisical://KEY`: launches from the IDE get the secret, tools outside the IDE read the
 * plain value (cleared, or kept as a local fallback). Lines that already hold a reference or a binding aren't reported.
 * Text-based, so it works with or without a DotEnv language plugin.
 */
class DotEnvPlaintextSecretInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!DotEnvLines.isDotEnv(holder.file.name)) return PsiElementVisitor.EMPTY_VISITOR
        val snapshot = KeysIndex.getInstance(holder.project).current() ?: return PsiElementVisitor.EMPTY_VISITOR
        val keys = snapshot.boundKeys.keys
        if (keys.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
        val resolver = LaunchSecretResolver.getInstance(holder.project)
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                for (line in DotEnvLines.parse(file.text)) {
                    if (line.name !in keys || line.binding != null || line.value.isBlank()) continue
                    if (resolver.hasReferences(mapOf(line.name to line.value))) continue
                    val reference = SecretReference(snapshot.scheme, line.name).format()
                    holder.registerProblem(
                        file,
                        SecretsBundle.message("dotenv.plaintext", line.name, snapshot.boundEnvironment, snapshot.path),
                        ProblemHighlightType.WEAK_WARNING,
                        line.valueRange,
                        BindFix(line.name, reference, keepValue = false),
                        BindFix(line.name, reference, keepValue = true),
                    )
                }
            }
        }
    }

    /** Binds [name] with a comment; the line is found again when applied, so "Fix all" works however earlier fixes moved text. */
    class BindFix(private val name: String, private val reference: String, private val keepValue: Boolean) : LocalQuickFix {
        override fun getFamilyName(): String =
            SecretsBundle.message(if (keepValue) "dotenv.bind.keep.family" else "dotenv.bind.clear.family")

        override fun getName(): String = SecretsBundle.message(if (keepValue) "dotenv.bind.keep" else "dotenv.bind.clear", reference)

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            DotEnvLines.bind(document, name, reference, keepValue)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

/** On a `KEY=` line without a value whose key is a secret in the bound folder: *Bind to Infisical* (`KEY= # infisical://KEY`). */
class DotEnvBindIntention : IntentionAction {
    override fun getFamilyName(): String = SecretsBundle.message("dotenv.bind.intention.family")

    override fun getText(): String = familyName

    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = target(project, editor, file) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val (line, reference) = target(project, editor, file) ?: return
        DotEnvLines.bind(editor!!.document, line.name, reference, keepValue = true)
    }

    private fun target(project: Project, editor: Editor?, file: PsiFile?): Pair<DotEnvLines.Line, String>? {
        if (editor == null || file == null || !DotEnvLines.isDotEnv(file.name)) return null
        val snapshot = KeysIndex.getInstance(project).current() ?: return null
        val document = editor.document
        val lineNumber = document.getLineNumber(editor.caretModel.offset)
        val lineRange = TextRange(document.getLineStartOffset(lineNumber), document.getLineEndOffset(lineNumber))
        val line = DotEnvLines.parse(document.text).firstOrNull { lineRange.containsOffset(it.valueRange.startOffset) } ?: return null
        if (line.binding != null || line.value.isNotBlank() || line.name !in snapshot.boundKeys) return null
        return line to SecretReference(snapshot.scheme, line.name).format()
    }
}

/** Just enough of the dotenv format to find `NAME=value` lines, where their values are and their binding comments. */
object DotEnvLines {
    data class Line(
        val name: String,
        /** The value without quotes or comment. */
        val value: String,
        /** Where [value] is (inside the quotes, if any). */
        val valueRange: TextRange,
        /** Everything after `=` up to the end of the line (value, quotes and comment). */
        val afterEquals: TextRange,
        /** End of the value including its closing quote. */
        val valueEnd: Int,
        /** The reference of a binding comment (`KEY=… # infisical://…`), if any. */
        val binding: String?,
    )

    private val LINE = Regex("""^[ \t]*(?:export[ \t]+)?([A-Za-z_][A-Za-z0-9_.\-]*)[ \t]*=([^\n]*)$""", RegexOption.MULTILINE)

    /** `.env`, `.env.local`, `.env.production`, `app.env`, … */
    fun isDotEnv(fileName: String): Boolean = fileName == ".env" || fileName.startsWith(".env.") || fileName.endsWith(".env")

    fun parse(text: String): List<Line> {
        val schemes = SecretProvider.all().map { it.referenceScheme }
        return LINE.findAll(text).mapNotNull { match ->
            val group = match.groups[2] ?: return@mapNotNull null
            val afterEquals = TextRange(group.range.first, group.range.last + 1)
            val leading = group.value.length - group.value.trimStart().length
            val start = group.range.first + leading
            val body = group.value.trimStart()
            val quote = body.firstOrNull()
            val (valueRange, valueEnd, comment) = if (quote == '"' || quote == '\'' || quote == '`') {
                val close = body.indexOf(quote, 1)
                if (close < 0) return@mapNotNull null
                Triple(TextRange(start + 1, start + close), start + close + 1, body.substring(close + 1))
            } else {
                // `KEY=#…` is a comment only when it is a binding (`COLOR=#fff` is a value)
                val hash = when {
                    body.startsWith('#') && (leading > 0 || DotEnv.bindingOf(body.substring(1), schemes) != null) -> 0
                    else -> Regex("""[ \t]#""").find(body)?.range?.first?.plus(1) ?: -1
                }
                val raw = (if (hash >= 0) body.substring(0, hash) else body).trimEnd()
                Triple(TextRange(start, start + raw.length), start + raw.length, if (hash >= 0) body.substring(hash) else "")
            }
            val binding = comment.trim().takeIf { it.startsWith('#') }?.let { DotEnv.bindingOf(it.substring(1), schemes) }
            Line(match.groupValues[1], valueRange.substring(text), valueRange, afterEquals, valueEnd, binding)
        }.toList()
    }

    /**
     * The comment block directly above [line] in [text]: consecutive `#` lines without a blank line in between, `#` and
     * surrounding spaces removed, joined with spaces. Commented-out assignments (`# OLD=1`) end the block. Empty when none.
     */
    fun commentAbove(text: String, line: Line): String {
        val lineStart = text.lastIndexOf('\n', line.afterEquals.startOffset - 1) + 1
        val above = text.substring(0, lineStart).removeSuffix("\n").removeSuffix("\r").split('\n')
        val block = ArrayDeque<String>()
        for (raw in above.asReversed()) {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("#")) break
            val content = trimmed.trimStart('#').trim()
            if (ASSIGNMENT.matches(content)) break
            block.addFirst(content)
        }
        return block.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val ASSIGNMENT = Regex("""(?:export\s+)?[A-Za-z_][A-Za-z0-9_.\-]*\s*=.*""")

    /** A secret the key chooser offers: its key, the folder it is in, and its comment. */
    data class KeyChoice(val key: String, val path: String, val comment: String = "")

    /**
     * [choices] in the order the chooser offers them for the variable [name]: the same name, then names sharing words with
     * it (most shared first); within that, the bound folder ([boundPath]) first, then shallower folders, then by key.
     */
    fun rankChoices(name: String, boundPath: String, choices: Collection<KeyChoice>): List<KeyChoice> {
        fun words(s: String) = s.split('_', '-', '.').filter { it.isNotEmpty() }.map(String::lowercase).toSet()
        val wanted = words(name)
        return choices.sortedWith(
            compareByDescending<KeyChoice> { it.key.equals(name, ignoreCase = true) }
                .thenByDescending { (words(it.key) intersect wanted).size }
                .thenByDescending { it.path == boundPath }
                .thenBy { it.path.count { c -> c == '/' } - (if (it.path == "/") 1 else 0) }
                .thenBy { it.key.lowercase() }
                .thenBy { it.path },
        )
    }

    /**
     * Binds the last [name] line of [document] to [reference] with a comment, replacing any comment it had. With [keepValue]
     * the value stays as the fallback for tools outside the IDE; otherwise it is cleared.
     */
    fun bind(document: Document, name: String, reference: String, keepValue: Boolean) {
        val line = parse(document.text).lastOrNull { it.name == name } ?: return
        val value = if (keepValue) document.charsSequence.subSequence(line.afterEquals.startOffset, line.valueEnd).toString().trim() else ""
        val replacement = if (value.isEmpty()) " # $reference" else "$value # $reference"
        document.replaceString(line.afterEquals.startOffset, line.afterEquals.endOffset, replacement)
    }
}
