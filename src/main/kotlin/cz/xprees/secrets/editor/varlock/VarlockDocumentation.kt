package cz.xprees.secrets.editor.varlock

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.envspec.EnvSpecDecorators
import cz.xprees.secrets.editor.varlock.VarlockTokens.Kind

/**
 * Quick documentation and hover for decorators and functions in varlock mode ([VarlockMode]): a short description from the
 * bundle and a link to varlock's documentation. Works on any language the file has, since it is offset-based.
 */
class VarlockDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!VarlockMode.isActive(file)) return emptyList()
        val tokens = VarlockTokens.scan(VarlockMode.parsed(file.originalFile), file.originalFile.viewProvider.contents)
        val token = VarlockTokens.at(tokens, offset, setOf(Kind.DECORATOR, Kind.FUNCTION))
            // The caret right after a name (`@required|`) documents it too
            ?: VarlockTokens.at(tokens, offset - 1, setOf(Kind.DECORATOR, Kind.FUNCTION))
            ?: return emptyList()
        val html = when (token.kind) {
            Kind.DECORATOR -> VarlockDocs.decorator(token.name)
            else -> VarlockDocs.function(token.name)
        } ?: return emptyList()
        return listOf(VarlockDocumentationTarget(if (token.kind == Kind.DECORATOR) "@${token.name}" else "${token.name}()", html))
    }
}

/** The documentation of one decorator or function; [html] is what quick documentation and the hover show. */
class VarlockDocumentationTarget(private val title: String, val html: String) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(title).presentation()

    override fun computeDocumentationHint(): String = html

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(html)
}

/** The texts of [VarlockDocumentationTargetProvider]. */
object VarlockDocs {
    /** The documentation of decorator [name], or null for an unknown one. */
    fun decorator(name: String): String? {
        if (!EnvSpecDecorators.isKnown(name)) return null
        val root = EnvSpecDecorators.isRoot(name)
        val kind = SecretsBundle.message(if (root) "envspec.completion.root" else "envspec.completion.item")
        val plugin = VarlockCatalog.Plugin.entries.firstOrNull { it.initDecorator == name }
        val text = when {
            name in EnvSpecDecorators.ROOT || name in EnvSpecDecorators.ITEM -> SecretsBundle.message("varlock.doc.decorator.$name")
            plugin != null -> SecretsBundle.message("varlock.doc.decorator.init", SecretsBundle.message(plugin.nameKey))
            else -> SecretsBundle.message("varlock.doc.plugin.decorator")
        }
        val deprecated = VarlockCatalog.DEPRECATED_REPLACEMENTS[name]?.let { SecretsBundle.message("varlock.problem.deprecated", name, it) }
        return html("@$name", kind, listOfNotNull(deprecated, text), VarlockCatalog.decoratorDocsUrl(name))
    }

    /** The documentation of function [name], or null for an unknown one. */
    fun function(name: String): String? {
        val function = VarlockCatalog.function(name) ?: return null
        val source = function.plugin?.let { SecretsBundle.message("varlock.doc.from.plugin", SecretsBundle.message(it.nameKey), it.initDecorator) }
            ?: SecretsBundle.message("varlock.completion.core")
        val text = SecretsBundle.message("varlock.doc.function.$name")
        return html(name + function.signature, source, listOf(text, SecretsBundle.message("varlock.doc.never.evaluated")), VarlockCatalog.functionDocsUrl(function))
    }

    private fun html(definition: String, kind: String, paragraphs: List<String>, url: String): String = buildString {
        append(DocumentationMarkup.DEFINITION_START).append(StringUtil.escapeXmlEntities(definition)).append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        append("<p>").append(StringUtil.escapeXmlEntities(kind)).append("</p>")
        paragraphs.forEach { append("<p>").append(StringUtil.escapeXmlEntities(it)).append("</p>") }
        append("<p><a href=\"").append(url).append("\">").append(StringUtil.escapeXmlEntities(SecretsBundle.message("varlock.doc.link"))).append("</a></p>")
        append(DocumentationMarkup.CONTENT_END)
    }
}
