package cz.xprees.secrets.editor

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.settings.ProjectSecretsSettings

/**
 * Reports secrets that the bound environment doesn't have:
 * - an `infisical://KEY` reference whose key is missing in the environment it reads;
 * - code reading a variable that other environments of the project define but the bound one doesn't (a likely missing secret;
 *   ordinary variables such as `PATH` are never reported).
 */
class MissingSecretInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val snapshot = KeysIndex.getInstance(file.project).current() ?: return null
        val document = file.viewProvider.document ?: return null
        val problems = mutableListOf<ProblemDescriptor>()
        for (found in EditorReferences.of(file)) {
            if (found.end > document.textLength) continue
            val resolved = EditorReferences.resolve(file.project, found, snapshot) ?: continue
            if (!resolved.indexed || resolved.info != null) continue
            val environment = found.reference?.environment ?: snapshot.boundEnvironment
            if (environment !in snapshot.environments) continue
            val elsewhere = resolved.coverage.filter { it != environment }
            val message = when {
                found.kind == EnvReferences.Kind.REFERENCE && elsewhere.isEmpty() ->
                    SecretsBundle.message("inspection.missing.reference", found.name, environment)
                elsewhere.isNotEmpty() -> SecretsBundle.message("inspection.missing.elsewhere", found.name, environment, elsewhere.joinToString())
                else -> continue
            }
            val fixes = elsewhere.take(3).map { SwitchEnvironmentFix(it) }.toTypedArray<LocalQuickFix>()
            problems += manager.createProblemDescriptor(
                file, TextRange(found.start, found.end), message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, *fixes,
            )
        }
        return problems.toTypedArray()
    }
}

/** Binds the IDE project to another environment. */
private class SwitchEnvironmentFix(private val environment: String) : LocalQuickFix {
    override fun getFamilyName(): String = SecretsBundle.message("inspection.fix.switch.family")

    override fun getName(): String = SecretsBundle.message("inspection.fix.switch", environment)

    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        ProjectSecretsSettings.getInstance(project).update { this.environment = this@SwitchEnvironmentFix.environment }
    }
}
