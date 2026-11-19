package cz.xprees.secrets.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.reference.SecretReference
import javax.swing.Icon

/**
 * Marks variable names and references that are secrets in the bound project: a key icon in the gutter (reveal, copy, override,
 * show in the tool window) and a tooltip with the environments that define the key and its comment. Values are never shown
 * unless asked for. Works in every language; runs off the EDT.
 */
class SecretReferenceAnnotator : ExternalAnnotator<SecretReferenceAnnotator.Input, List<EditorReferences.Resolved>>(), DumbAware {

    class Input(val file: PsiFile, val snapshot: KeysIndex.Snapshot)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        val snapshot = KeysIndex.getInstance(file.project).current() ?: return null
        return Input(file, snapshot)
    }

    override fun collectInformation(file: PsiFile): Input? = null

    override fun doAnnotate(input: Input): List<EditorReferences.Resolved> =
        EditorReferences.of(input.file).mapNotNull { EditorReferences.resolve(input.file.project, it, input.snapshot) }
            .filter { it.info != null }

    override fun apply(file: PsiFile, results: List<EditorReferences.Resolved>, holder: AnnotationHolder) {
        val markedLines = HashSet<Int>()
        val document = file.viewProvider.document ?: return
        for (resolved in results) {
            val found = resolved.found
            if (found.end > document.textLength) continue
            val line = document.getLineNumber(found.start)
            val builder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(found.start, found.end))
                .tooltip(tooltip(resolved))
            // One key icon per line is enough
            if (markedLines.add(line)) builder.gutterIconRenderer(SecretGutter(found.name, found.reference, tooltip(resolved)))
            builder.create()
        }
    }

    private fun tooltip(resolved: EditorReferences.Resolved): String {
        val s = resolved.snapshot
        val info = resolved.info
        val environments = resolved.coverage.joinToString { if (it == s.boundEnvironment) "<b>$it</b>" else it }
        return buildString {
            append("<html><b>").append(StringUtil.escapeXmlEntities(resolved.found.name)).append("</b> · ")
            append(StringUtil.escapeXmlEntities(SecretsBundle.message("editor.tooltip.source", s.connectionName, s.path)))
            append("<br>").append(SecretsBundle.message("editor.tooltip.environments", environments))
            info?.comment?.takeIf { it.isNotBlank() }?.let { append("<br><i>").append(StringUtil.escapeXmlEntities(it)).append("</i>") }
            append("</html>")
        }
    }
}

/** The key icon in the gutter, with the actions on that secret. */
private class SecretGutter(private val key: String, private val reference: SecretReference?, private val tooltip: String) : GutterIconRenderer(), DumbAware {
    private val environment: String? get() = reference?.environment

    override fun getIcon(): Icon = SecretsIcons.PROVIDER

    override fun getTooltipText(): String = tooltip

    override fun isNavigateAction(): Boolean = true

    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
        action("editor.action.reveal") { e -> e.getData(CommonDataKeys.EDITOR)?.let { SecretValueActions.reveal(e.project!!, it, key, environment) } },
        action("editor.action.copy") { e -> SecretValueActions.copy(e.project!!, key, environment) },
        action("editor.action.override") { e -> SecretValueActions.editOverride(e.project!!, key) },
        action("editor.action.show") { e -> ShowSecretInToolWindow.show(e.project!!, key, reference) },
    )

    override fun getClickAction(): AnAction = action("editor.action.reveal") { e ->
        e.getData(CommonDataKeys.EDITOR)?.let { SecretValueActions.reveal(e.project!!, it, key, environment) }
    }

    override fun equals(other: Any?) = other is SecretGutter && other.key == key && other.reference == reference

    override fun hashCode() = key.hashCode() * 31 + reference.hashCode()

    private fun action(textKey: String, perform: (AnActionEvent) -> Unit): AnAction =
        object : DumbAwareAction(SecretsBundle.message(textKey)) {
            override fun actionPerformed(e: AnActionEvent) {
                if (e.project != null) perform(e)
            }
        }
}
