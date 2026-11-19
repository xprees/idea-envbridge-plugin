package cz.xprees.secrets.dotnet

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.resolve.KeysIndex
import javax.swing.Icon

/** The configuration path of a JSON value: property names from the root, array indexes as numbers. */
internal object JsonConfigPath {
    fun of(property: JsonProperty): List<String> {
        val path = ArrayDeque<String>()
        var element: PsiElement? = property
        while (element != null) {
            val parent = element.parent
            when {
                element is JsonProperty -> path.addFirst(element.name)
                element is JsonValue && parent is JsonArray -> path.addFirst(parent.valueList.indexOf(element).toString())
            }
            if (parent == null || parent is com.intellij.psi.PsiFile) break
            element = parent
        }
        return path
    }

    /** Leaf values only: objects and arrays are sections, not settings. */
    fun isLeaf(property: JsonProperty): Boolean = property.value.let { it != null && it !is JsonObject && it !is JsonArray }

    fun secretFor(property: JsonProperty, keys: Collection<String>): String? =
        if (isLeaf(property)) AppSettingsKeys.match(of(property), keys) else null
}

/**
 * In `appsettings*.json`: marks settings that an Infisical secret overrides at run time (the environment variable
 * `Section__Key` wins over `Section:Key` in ASP.NET Core), with a key icon and the environments that define it.
 */
class AppSettingsSecretAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val property = element as? JsonProperty ?: return
        if (!AppSettingsKeys.isAppSettings(property.containingFile.name)) return
        val snapshot = KeysIndex.getInstance(property.project).current() ?: return
        val key = JsonConfigPath.secretFor(property, snapshot.boundKeys.keys) ?: return
        val tooltip = SecretsBundle.message("appsettings.overridden", key, snapshot.coverage(key).joinToString())
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(property.nameElement)
            .tooltip(tooltip)
            .gutterIconRenderer(OverriddenGutter(tooltip))
            .create()
    }

    private class OverriddenGutter(private val tooltip: String) : GutterIconRenderer(), DumbAware {
        override fun getIcon(): Icon = SecretsIcons.PROVIDER

        override fun getTooltipText(): String = tooltip

        override fun equals(other: Any?) = other is OverriddenGutter && other.tooltip == tooltip

        override fun hashCode() = tooltip.hashCode()
    }
}

/**
 * Reports a non-empty plaintext value in `appsettings*.json` for a setting that an Infisical secret provides: the secret
 * belongs in Infisical, not in a committed file. Quick fix: clear the value.
 */
class AppSettingsPlaintextSecretInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!AppSettingsKeys.isAppSettings(holder.file.name)) return PsiElementVisitor.EMPTY_VISITOR
        val snapshot = KeysIndex.getInstance(holder.project).current() ?: return PsiElementVisitor.EMPTY_VISITOR
        val keys = snapshot.environments.values.flatMap { it.keys }.toSet()
        return object : JsonElementVisitor() {
            override fun visitProperty(property: JsonProperty) {
                val value = property.value as? JsonStringLiteral ?: return
                if (value.value.isBlank()) return
                val key = JsonConfigPath.secretFor(property, keys) ?: return
                holder.registerProblem(
                    value,
                    SecretsBundle.message("appsettings.plaintext", key),
                    ProblemHighlightType.WEAK_WARNING,
                    ClearValueFix(),
                )
            }
        }
    }

    private class ClearValueFix : LocalQuickFix {
        override fun getFamilyName(): String = SecretsBundle.message("appsettings.clear.value")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val literal = descriptor.psiElement as? JsonStringLiteral ?: return
            literal.replace(JsonElementGenerator(project).createStringLiteral(""))
        }
    }
}
