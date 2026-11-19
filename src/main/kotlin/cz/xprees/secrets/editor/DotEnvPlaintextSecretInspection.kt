package cz.xprees.secrets.editor

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver

/**
 * `.env` files: a plaintext value of a variable that exists as a secret in the bound folder is reported, with a quick fix
 * that replaces the value by a reference (`KEY=infisical://KEY`). Run configurations and the Node.js tools' own `.env`
 * loading then get the secret instead, and the file holds nothing secret. Text-based, so it works with or without a DotEnv
 * language plugin.
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
                    if (line.name !in keys || line.value.isBlank() || resolver.hasReferences(mapOf(line.name to line.value))) continue
                    holder.registerProblem(
                        file,
                        SecretsBundle.message("dotenv.plaintext", line.name, snapshot.boundEnvironment, snapshot.path),
                        ProblemHighlightType.WEAK_WARNING,
                        line.valueRange,
                        ReplaceWithReferenceFix(line.name, SecretReference(snapshot.scheme, line.name).format()),
                    )
                }
            }
        }
    }

    /** Replaces the value of [name] (found again when applied, so "Fix all" works however earlier fixes moved the text). */
    class ReplaceWithReferenceFix(private val name: String, private val reference: String) : LocalQuickFix {
        override fun getFamilyName(): String = SecretsBundle.message("dotenv.replace.family")

        override fun getName(): String = SecretsBundle.message("dotenv.replace", reference)

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val line = DotEnvLines.parse(document.text).lastOrNull { it.name == name } ?: return
            document.replaceString(line.valueRange.startOffset, line.valueRange.endOffset, reference)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

/** Just enough of the dotenv format to find `NAME=value` lines and where their values are. */
object DotEnvLines {
    data class Line(val name: String, val value: String, val valueRange: TextRange)

    private val LINE = Regex("""^[ \t]*(?:export[ \t]+)?([A-Za-z_][A-Za-z0-9_.\-]*)[ \t]*=[ \t]*(.*?)[ \t]*$""", RegexOption.MULTILINE)

    /** `.env`, `.env.local`, `.env.production`, `app.env`, … */
    fun isDotEnv(fileName: String): Boolean = fileName == ".env" || fileName.startsWith(".env.") || fileName.endsWith(".env")

    fun parse(text: String): List<Line> = LINE.findAll(text).mapNotNull { match ->
        val group = match.groups[2] ?: return@mapNotNull null
        var start = group.range.first
        var raw = group.value
        if (raw.isEmpty()) return@mapNotNull Line(match.groupValues[1], "", TextRange(start, start))
        val quote = raw[0]
        if ((quote == '"' || quote == '\'' || quote == '`')) {
            val close = raw.indexOf(quote, 1)
            if (close < 0) return@mapNotNull null
            // The quotes stay; the fix replaces what is between them
            start += 1
            raw = raw.substring(1, close)
        } else {
            val comment = Regex("""[ \t]+#""").find(raw)
            if (comment != null) raw = raw.substring(0, comment.range.first)
        }
        Line(match.groupValues[1], raw, TextRange(start, start + raw.length))
    }.toList()
}
