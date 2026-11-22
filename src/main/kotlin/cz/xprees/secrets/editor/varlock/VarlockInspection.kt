package cz.xprees.secrets.editor.varlock

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.envspec.EnvSpecType
import cz.xprees.secrets.core.envspec.EnvSpecValue
import cz.xprees.secrets.editor.EnvSpecInspection
import cz.xprees.secrets.editor.varlock.VarlockTokens.Kind

/**
 * varlock's own rules, in varlock mode only ([VarlockMode]); the general decorator checks (unknown, misplaced, repeated,
 * `@required` with `@optional`, types) stay with [EnvSpecInspection]. Reported here: deprecated decorators (with their
 * replacement) and bare `/regex/` type options, unknown functions, plugin functions whose plugin no header loads, references to
 * keys no layer defines, an `@currentEnv` naming a missing key, `@import`s of missing files (unless `allowMissing=true`) and
 * plain values that don't fit a `@type` declared in another layer (an invalid enum value in `.env` for an enum of
 * `.env.schema`). Nothing is evaluated: `exec(…)` and plugin functions are only looked at.
 */
class VarlockInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!VarlockMode.isActive(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                check(file, holder)
            }
        }
    }

    private fun check(file: PsiFile, holder: ProblemsHolder) {
        val text = file.viewProvider.contents
        val context = VarlockContext.of(file)
        val parsed = context.parsed
        val tokens = VarlockTokens.scan(parsed, text)
        fun report(range: TextRange, message: String, type: ProblemHighlightType = ProblemHighlightType.WARNING, vararg fixes: LocalQuickFix) {
            if (range.isEmpty || range.endOffset > text.length) return
            holder.registerProblem(file, message, type, range, *fixes)
        }

        // Deprecated decorators
        for (decorator in parsed.allDecorators) {
            val replacement = VarlockCatalog.DEPRECATED_REPLACEMENTS[decorator.name] ?: continue
            val fixes = if (decorator.name in VarlockCatalog.RENAMEABLE_DEPRECATIONS) {
                arrayOf<LocalQuickFix>(EnvSpecInspection.RenameDecoratorFix(decorator.name, replacement))
            } else {
                LocalQuickFix.EMPTY_ARRAY
            }
            report(
                TextRange(decorator.range.first, decorator.nameRange.last + 1),
                SecretsBundle.message("varlock.problem.deprecated", decorator.name, replacement),
                ProblemHighlightType.LIKE_DEPRECATED,
                *fixes,
            )
        }
        // `@type=string(matches=/…/)`: the older bare regex
        for (item in parsed.items) {
            val call = item.decorator("type")?.value as? EnvSpecValue.Call ?: continue
            val matches = call.named("matches") as? EnvSpecValue.Literal ?: continue
            if (matches.quote == null && matches.value.startsWith('/')) {
                report(TextRange(matches.range.first, matches.range.last + 1), SecretsBundle.message("varlock.problem.bare_regex"), ProblemHighlightType.LIKE_DEPRECATED)
            }
        }

        // Functions: unknown ones, and plugin functions without their plugin
        for (token in tokens.filter { it.kind == Kind.FUNCTION }) {
            val function = VarlockCatalog.function(token.name)
            when {
                function == null -> if (!context.unknownPlugins) {
                    val suggestion = VarlockCatalog.functionSuggestion(token.name)
                    report(
                        token.range,
                        if (suggestion == null) {
                            SecretsBundle.message("varlock.problem.unknown_function", token.name)
                        } else {
                            SecretsBundle.message("varlock.problem.unknown_function.suggestion", token.name, suggestion)
                        },
                    )
                }

                function.plugin != null && function.plugin !in context.plugins -> report(
                    token.range,
                    SecretsBundle.message("varlock.problem.plugin_missing", token.name, SecretsBundle.message(function.plugin.nameKey), function.plugin.initDecorator),
                )
            }
        }

        // References and @currentEnv
        val currentEnv = parsed.rootDecorator("currentEnv")?.value as? EnvSpecValue.Ref
        val currentEnvRange = currentEnv?.let { TextRange(it.range.first, it.range.last + 1) }
        for (token in tokens.filter { it.kind == Kind.REFERENCE }) {
            if (context.knows(token.name)) continue
            if (token.range == currentEnvRange) {
                report(token.range, SecretsBundle.message("varlock.problem.current_env_missing", token.name))
            } else {
                report(token.range, SecretsBundle.message("varlock.problem.unknown_reference", token.name))
            }
        }

        // @import of missing files
        val own = file.originalFile.virtualFile
        if (own != null) {
            for (decorator in parsed.rootDecorators.filter { it.name == "import" }) {
                val args = decorator.args ?: continue
                val path = args.firstOrNull { it.key == null }?.value as? EnvSpecValue.Literal ?: continue
                val allowMissing = (args.lastOrNull { it.key == "allowMissing" }?.value as? EnvSpecValue.Literal)?.value == "true"
                if (allowMissing || !VarlockContext.isFilePath(path.value)) continue
                if (VarlockContext.resolveImport(own, path.value) == null) {
                    report(TextRange(path.range.first, path.range.last + 1), SecretsBundle.message("varlock.problem.import_missing", path.value))
                }
            }
        }

        // Plain values against a @type of another layer (the file's own @type is EnvSpecInspection's)
        for (item in parsed.items) {
            if (item.has("type")) continue
            val literal = item.value as? EnvSpecValue.Literal ?: continue
            if (literal.value.isEmpty()) continue
            val (type, layer) = context.layers.asReversed().firstNotNullOfOrNull { layer ->
                val parsedType = layer.parsed.item(item.key)?.decorator("type")?.value?.let(EnvSpecType::parse)
                (parsedType as? EnvSpecType.Parsed.Ok)?.let { it.type to layer }
            } ?: continue
            if (!type.accepts(literal.value)) {
                report(
                    TextRange(literal.range.first, literal.range.last + 1),
                    SecretsBundle.message("varlock.problem.layer_type_mismatch", item.key, type.expected, layer.file.name),
                )
            }
        }
    }
}
