package cz.xprees.secrets.editor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.envspec.EnvSpecDecorators
import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic
import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Code
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecValidation
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.editor.varlock.VarlockCompletion
import cz.xprees.secrets.editor.varlock.VarlockMode

/**
 * env-spec decorators in `.env` files (`.env`, `.env.schema`, `.env.local`, …): syntax problems of decorator comments, unknown
 * or misspelled decorators (with a fix), misplaced and repeated ones, `@required` with `@optional`, invalid `@type` values and
 * literal values that don't fit their `@type`. Only files that use decorators are checked. Text-based, so it works with or
 * without a DotEnv language plugin.
 */
class EnvSpecInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!DotEnvLines.isDotEnv(holder.file.name)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val text = file.text
                if (text.length > EnvReferences.MAX_TEXT_LENGTH || !text.contains('@')) return
                val parsed = EnvSpecParser.parse(text)
                if (!parsed.usesDecorators) return
                for (problem in parsed.diagnostics + EnvSpecValidation.check(parsed)) {
                    val start = problem.range.first.coerceIn(0, text.length)
                    val end = (problem.range.last + 1).coerceIn(start, text.length)
                    val range = if (end > start) TextRange(start, end) else TextRange(start, minOf(start + 1, text.length))
                    if (range.isEmpty) continue
                    val fixes = suggestionFix(problem)?.let { arrayOf<LocalQuickFix>(it) } ?: LocalQuickFix.EMPTY_ARRAY
                    holder.registerProblem(file, message(problem), highlight(problem), range, *fixes)
                }
            }
        }
    }

    private fun highlight(problem: EnvSpecDiagnostic): ProblemHighlightType = when {
        problem.severity == EnvSpecDiagnostic.Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR
        problem.code == Code.UNKNOWN_DECORATOR && problem.args.getOrNull(1).isNullOrEmpty() -> ProblemHighlightType.WEAK_WARNING
        else -> ProblemHighlightType.WARNING
    }

    private fun suggestionFix(problem: EnvSpecDiagnostic): LocalQuickFix? {
        if (problem.code != Code.UNKNOWN_DECORATOR) return null
        val suggestion = problem.args.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return RenameDecoratorFix(problem.args[0], suggestion)
    }

    companion object {
        /** The message for [problem]; never contains a value. */
        fun message(problem: EnvSpecDiagnostic): String {
            val args = problem.args.toTypedArray<Any>()
            return when (problem.code) {
                Code.UNKNOWN_DECORATOR -> if (problem.args.getOrNull(1).isNullOrEmpty()) {
                    SecretsBundle.message("envspec.problem.unknown_decorator", problem.args[0])
                } else {
                    SecretsBundle.message("envspec.problem.unknown_decorator.suggestion", problem.args[0], problem.args[1])
                }

                Code.MISPLACED_DECORATOR -> SecretsBundle.message(
                    if (EnvSpecDecorators.isRoot(problem.args[0])) "envspec.problem.misplaced_decorator.root" else "envspec.problem.misplaced_decorator.item",
                    problem.args[0],
                )

                Code.UNEXPECTED_TEXT -> SecretsBundle.message("envspec.problem.unexpected_text")
                else -> SecretsBundle.message("envspec.problem." + problem.code.name.lowercase(), *args)
            }
        }
    }

    /** Replaces a misspelled decorator name (`@requried` -> `@required`). */
    class RenameDecoratorFix(private val from: String, private val to: String) : LocalQuickFix {
        override fun getFamilyName(): String = SecretsBundle.message("envspec.fix.rename.family")

        override fun getName(): String = SecretsBundle.message("envspec.fix.rename", to)

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val file = descriptor.psiElement?.containingFile ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val range = descriptor.textRangeInElement?.shiftRight(descriptor.psiElement.textRange.startOffset) ?: return
            val text = document.charsSequence.subSequence(range.startOffset, range.endOffset).toString()
            if (!text.startsWith("@$from")) return
            document.replaceString(range.startOffset + 1, range.startOffset + 1 + from.length, to)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

/**
 * Completes decorator names after `# @` (and `# @required @…`, and `KEY=value # @…`) in `.env` files. In varlock mode only the
 * decorators that fit the position are offered: root decorators (and plugins' `@init…`) in the header, item decorators elsewhere.
 */
class EnvSpecDecoratorCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!DotEnvLines.isDotEnv(parameters.originalFile.name)) return
        val linePrefix = SecretKeyCompletionContributor.linePrefix(parameters.editor, parameters.offset)
        val prefix = decoratorPrefix(linePrefix) ?: return
        // Names start with what was typed (`@sens` shouldn't offer `@defaultSensitive`)
        val matching = result.withPrefixMatcher(PlainPrefixMatcher(prefix, true))
        // varlock mode: root decorators in the header, item decorators elsewhere, plugin decorators too, `()` / `=` inserted
        val varlock = VarlockMode.isActive(parameters.originalFile)
        val names = if (varlock) {
            val trailing = DECORATOR_COMMENT.find(linePrefix) == null
            val file = parameters.originalFile
            VarlockCompletion.decoratorNames(VarlockCompletion.position(VarlockMode.parsed(file), file.viewProvider.contents, parameters.offset, trailing))
        } else {
            EnvSpecDecorators.ALL.filter { it !in EnvSpecDecorators.DEPRECATED }
        }
        for (name in names) {
            val root = EnvSpecDecorators.isRoot(name)
            var element = LookupElementBuilder.create(name).withPresentableText("@$name").withBoldness(name in EnvSpecDecorators.NATIVE)
                .withTypeText(SecretsBundle.message(if (root) "envspec.completion.root" else "envspec.completion.item"), true)
            if (varlock) element = element.withInsertHandler(VarlockCompletion.DECORATOR_INSERT)
            matching.addElement(element)
        }
        result.stopHere()
    }

    companion object {
        private val DECORATOR_COMMENT = Regex("""^\s*#\s*@(?:[^#]*\s@)?([A-Za-z0-9_]*)$""")
        private val TRAILING = Regex("""^\s*(?:export\s+)?[A-Za-z_][A-Za-z0-9_.\-]*\s*=.*\s#\s*@(?:[^#]*\s@)?([A-Za-z0-9_]*)$""")

        /** The typed part of a decorator name when [linePrefix] (the line up to the caret) ends in one, or null. */
        fun decoratorPrefix(linePrefix: String): String? =
            (DECORATOR_COMMENT.find(linePrefix) ?: TRAILING.find(linePrefix))?.groupValues?.get(1)
    }
}
