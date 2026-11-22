package cz.xprees.secrets.editor.varlock

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.editor.varlock.VarlockTokens.Kind
import javax.swing.Icon

/** The colours of the varlock highlighting; each falls back to a standard colour of the scheme. */
object VarlockHighlighting {
    val DECORATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_DECORATOR", DefaultLanguageHighlighterColors.METADATA)
    val FUNCTION: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val REFERENCE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_REFERENCE", DefaultLanguageHighlighterColors.CONSTANT)
    val TYPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_TYPE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
    val ARGUMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_ARGUMENT", DefaultLanguageHighlighterColors.PARAMETER)
    val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_STRING", DefaultLanguageHighlighterColors.STRING)
    val IMPORT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_IMPORT", DefaultLanguageHighlighterColors.STRING)
    val KEY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("ENVBRIDGE_VARLOCK_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)

    /** The colour of a token kind. */
    fun of(kind: Kind): TextAttributesKey = when (kind) {
        Kind.DECORATOR -> DECORATOR
        Kind.FUNCTION -> FUNCTION
        Kind.REFERENCE -> REFERENCE
        Kind.TYPE -> TYPE
        Kind.ARGUMENT -> ARGUMENT
        Kind.KEYWORD -> KEYWORD
        Kind.STRING -> STRING
        Kind.IMPORT -> IMPORT
        Kind.KEY -> KEY
        Kind.COMMENT -> COMMENT
    }
}

/**
 * Highlights varlock syntax in `.env` files in varlock mode ([VarlockMode]), as a layer of annotations over whatever language
 * the file has: decorators, function calls, references, `@type` names and options, argument names, keywords, strings and
 * `@import` paths. Keys and comments only in files no language highlights (plain text, e.g. `.env.schema` without a DotEnv
 * plugin), so a DotEnv plugin's own colours stay. No file type is registered, so nothing competes with a DotEnv plugin.
 */
class VarlockAnnotator : ExternalAnnotator<List<VarlockTokens.Token>, List<VarlockTokens.Token>>(), DumbAware {

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): List<VarlockTokens.Token>? = collectInformation(file)

    override fun collectInformation(file: PsiFile): List<VarlockTokens.Token>? {
        if (!VarlockMode.isActive(file)) return null
        val plain = file.language == PlainTextLanguage.INSTANCE
        return VarlockTokens.scan(VarlockMode.parsed(file), file.viewProvider.contents)
            .filter { plain || (it.kind != Kind.KEY && it.kind != Kind.COMMENT) }
    }

    override fun doAnnotate(collectedInfo: List<VarlockTokens.Token>?): List<VarlockTokens.Token>? = collectedInfo

    override fun apply(file: PsiFile, annotationResult: List<VarlockTokens.Token>?, holder: AnnotationHolder) {
        val length = file.textLength
        // Comments first, so decorators inside them are drawn on top
        for (token in annotationResult.orEmpty().sortedBy { if (it.kind == Kind.COMMENT) 0 else 1 }) {
            if (token.range.isEmpty || token.range.endOffset > length) continue
            holder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).range(token.range).textAttributes(VarlockHighlighting.of(token.kind)).create()
        }
    }
}

/** *Settings › Editor › Color Scheme › varlock (EnvBridge)*: the colours of [VarlockHighlighting]. */
class VarlockColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = SecretsIcons.PROVIDER

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getDemoText(): String = """
        <comment>#</comment> <decorator>@defaultRequired</decorator>=<keyword>infer</keyword> <decorator>@defaultSensitive</decorator>=<keyword>false</keyword>
        <comment>#</comment> <decorator>@currentEnv</decorator>=<reference>${'$'}APP_ENV</reference>
        <comment>#</comment> <decorator>@import</decorator>(<import>./.env.shared</import>, <argument>allowMissing</argument>=<keyword>true</keyword>)
        <comment># ---</comment>
        <comment># The environment</comment>
        <comment>#</comment> <decorator>@type</decorator>=<type>enum</type>(dev, prod)
        <key>APP_ENV</key>=dev
        <comment>#</comment> <decorator>@sensitive</decorator> <decorator>@type</decorator>=<type>string</type>(<argument>startsWith</argument>=sk-)
        <key>API_KEY</key>=<function>fallback</function>(<function>op</function>(op://vault/api/key), <reference>${'$'}LOCAL_KEY</reference>)
        <comment>#</comment> <decorator>@docs</decorator>(<string>"https://example.com"</string>)
        <key>URL</key>=http://localhost
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "decorator" to VarlockHighlighting.DECORATOR,
        "function" to VarlockHighlighting.FUNCTION,
        "reference" to VarlockHighlighting.REFERENCE,
        "type" to VarlockHighlighting.TYPE,
        "argument" to VarlockHighlighting.ARGUMENT,
        "keyword" to VarlockHighlighting.KEYWORD,
        "string" to VarlockHighlighting.STRING,
        "import" to VarlockHighlighting.IMPORT,
        "key" to VarlockHighlighting.KEY,
        "comment" to VarlockHighlighting.COMMENT,
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor(SecretsBundle.message("varlock.color.decorator"), VarlockHighlighting.DECORATOR),
        AttributesDescriptor(SecretsBundle.message("varlock.color.function"), VarlockHighlighting.FUNCTION),
        AttributesDescriptor(SecretsBundle.message("varlock.color.reference"), VarlockHighlighting.REFERENCE),
        AttributesDescriptor(SecretsBundle.message("varlock.color.type"), VarlockHighlighting.TYPE),
        AttributesDescriptor(SecretsBundle.message("varlock.color.argument"), VarlockHighlighting.ARGUMENT),
        AttributesDescriptor(SecretsBundle.message("varlock.color.keyword"), VarlockHighlighting.KEYWORD),
        AttributesDescriptor(SecretsBundle.message("varlock.color.string"), VarlockHighlighting.STRING),
        AttributesDescriptor(SecretsBundle.message("varlock.color.import"), VarlockHighlighting.IMPORT),
        AttributesDescriptor(SecretsBundle.message("varlock.color.key"), VarlockHighlighting.KEY),
        AttributesDescriptor(SecretsBundle.message("varlock.color.comment"), VarlockHighlighting.COMMENT),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = SecretsBundle.message("varlock.color.page")
}
