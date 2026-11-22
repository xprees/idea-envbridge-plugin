package cz.xprees.secrets.editor.varlock

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbAware
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.envspec.EnvSpecDecorators
import cz.xprees.secrets.core.envspec.EnvSpecFile
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecType
import cz.xprees.secrets.editor.SecretKeyCompletionContributor

/**
 * Completion in varlock mode ([VarlockMode]), beyond decorator names (see `EnvSpecDecoratorCompletionContributor`): `@type=`
 * names and their options (`string(startsWith=…)`), the values of decorators (`true`, `false`, `infer`, `forEnv()`, …), function
 * names in values and arguments (core functions and the 1Password, Infisical and Vault plugins'), the allowed values after
 * `KEY=` when the key's `@type` (in any layer) is an `enum(…)`, and keys of the layered files after `$`, `${` and in `ref(…)`.
 */
class VarlockCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!VarlockMode.isActive(file)) return
        val line = SecretKeyCompletionContributor.linePrefix(parameters.editor, parameters.offset)
        if (VarlockCompletion.complete(line, VarlockContext.of(file), result)) result.stopHere()
    }
}

/** What to complete for the text before the caret; split out so it is testable without an editor. */
object VarlockCompletion {
    /** Where a decorator is being typed: in the header (root decorators), at an item, or not yet decided (top of a file without header). */
    enum class Position { ROOT, ITEM, ANY }

    private val REFERENCE = Regex("""\$\{?([A-Za-z0-9_]*)$""")
    private val TYPE_NAME = Regex("""@type=([A-Za-z]*)$""")
    private val TYPE_OPTION = Regex("""@type=([A-Za-z]+)\(([^()]*)$""")
    private val DECORATOR_VALUE = Regex("""@([A-Za-z][A-Za-z0-9_]*)=([A-Za-z]*)$""")
    private val ARGUMENT = Regex("""[(,=]\s*([A-Za-z0-9_]*)$""")
    private val ITEM = Regex("""^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_.\-]*)\s*=(.*)$""")
    private val SIMPLE_VALUE = Regex("""^[A-Za-z0-9_.\-]*$""")
    private val IDENTIFIER = Regex("""^[A-Za-z][A-Za-z0-9_]*$""")
    private val TRAILING_COMMENT = Regex("""[ \t]#""")

    /**
     * Where a decorator at [offset] of [file] (parsed from [text]) goes; [trailing] when it is typed after an item's value. Below
     * a divider or an item it is an item decorator; above the first divider of a file without header it may be either.
     */
    fun position(file: EnvSpecFile, text: CharSequence, offset: Int, trailing: Boolean): Position {
        if (trailing) return Position.ITEM
        val header = file.header
        if (header != null && offset >= header.range.first && offset <= header.range.last + 1) return Position.ROOT
        if (header != null || file.items.any { it.range.first < offset }) return Position.ITEM
        val dividerAbove = text.subSequence(0, offset.coerceIn(0, text.length)).lines().dropLast(1)
            .any { line -> line.trimStart().startsWith("#") && EnvSpecParser.isDivider(line.trimStart().substring(1)) }
        return if (dividerAbove) Position.ITEM else Position.ANY
    }

    /** The decorator names offered at [position], root decorators including the plugins' `@init…`; deprecated ones left out. */
    fun decoratorNames(position: Position): List<String> {
        val root = (EnvSpecDecorators.ROOT.sorted() + VarlockCatalog.PLUGIN_ROOT_DECORATORS).filter { it !in EnvSpecDecorators.DEPRECATED }
        val item = EnvSpecDecorators.ITEM.sorted().filter { it !in EnvSpecDecorators.DEPRECATED }
        return when (position) {
            Position.ROOT -> root
            Position.ITEM -> item
            Position.ANY -> root + item
        }
    }

    /** Inserts `()` (or `=`) after a decorator name, by how varlock writes it. */
    val DECORATOR_INSERT: InsertHandler<LookupElement> = InsertHandler { context, item ->
        val name = item.lookupString
        val tail = context.tailOffset
        val next = context.document.charsSequence.let { if (tail < it.length) it[tail] else ' ' }
        when {
            name in VarlockCatalog.FUNCTION_STYLE && next != '(' -> {
                context.document.insertString(tail, "()")
                context.editor.caretModel.moveToOffset(tail + 1)
            }

            name in VarlockCatalog.VALUE_STYLE && next != '=' -> {
                context.document.insertString(tail, "=")
                context.editor.caretModel.moveToOffset(tail + 1)
            }
        }
    }

    /** Adds what fits the [line] before the caret; false when nothing here is varlock's to complete. */
    fun complete(line: String, context: VarlockContext, result: CompletionResultSet): Boolean {
        REFERENCE.find(line)?.let { match ->
            keys(context, result.withPrefixMatcher(match.groupValues[1]), dollar = false)
            return true
        }
        val trimmed = line.trimStart()
        val comment = when {
            trimmed.startsWith("#") -> trimmed.substring(1)
            else -> TRAILING_COMMENT.find(line)?.let { line.substring(it.range.last + 1) }
        }
        if (comment != null) return if (comment.trimStart().startsWith('@')) decoratorComment(comment, context, result) else false
        val item = ITEM.find(line) ?: return false
        return value(item.groupValues[1], item.groupValues[2].trimStart(), context, result)
    }

    private fun decoratorComment(comment: String, context: VarlockContext, result: CompletionResultSet): Boolean {
        TYPE_NAME.find(comment)?.let { match ->
            val matching = result.withPrefixMatcher(match.groupValues[1])
            VarlockCatalog.TYPES.forEach { matching.addElement(LookupElementBuilder.create(it).withTypeText(SecretsBundle.message("varlock.completion.type"), true)) }
            return true
        }
        TYPE_OPTION.find(comment)?.let { match ->
            val current = match.groupValues[2].substringAfterLast(',').trimStart()
            if ('=' in current || current.any { !it.isLetterOrDigit() }) return false
            val options = VarlockCatalog.TYPE_OPTIONS[match.groupValues[1]] ?: return false
            val matching = result.withPrefixMatcher(current)
            options.forEach {
                matching.addElement(LookupElementBuilder.create("$it=").withPresentableText(it).withTypeText(SecretsBundle.message("varlock.completion.option", match.groupValues[1]), true))
            }
            return true
        }
        DECORATOR_VALUE.find(comment)?.let { match ->
            val name = match.groupValues[1]
            val matching = result.withPrefixMatcher(match.groupValues[2])
            if (name == "currentEnv" || name == "envFlag") {
                keys(context, matching, dollar = name == "currentEnv")
                return true
            }
            val values = VarlockCatalog.decoratorValues(name)
            values.forEach { matching.addElement(valueElement(it)) }
            if (name in VarlockCatalog.SWITCHES || name == "disable") functions(matching)
            return values.isNotEmpty()
        }
        // Inside the arguments of a decorator or of a function in its value: functions
        val open = comment.count { it == '(' } - comment.count { it == ')' }
        if (open > 0) {
            ARGUMENT.find(comment)?.let { match ->
                functions(result.withPrefixMatcher(match.groupValues[1]))
                return true
            }
        }
        return false
    }

    private fun value(key: String, value: String, context: VarlockContext, result: CompletionResultSet): Boolean {
        if (TRAILING_COMMENT.containsMatchIn(value)) return false
        if (SIMPLE_VALUE.matches(value)) {
            val matching = result.withPrefixMatcher(value)
            val type = context.typeOf(key)
            val allowed = when (type) {
                is EnvSpecType.EnumType -> type.values
                is EnvSpecType.Simple -> if (type.name == "boolean") listOf("true", "false") else emptyList()
                else -> emptyList()
            }
            allowed.forEach {
                matching.addElement(
                    PrioritizedLookupElement.withPriority(LookupElementBuilder.create(it).withBoldness(true).withTypeText(SecretsBundle.message("varlock.completion.enum"), true), 100.0),
                )
            }
            if (value.isEmpty() || IDENTIFIER.matches(value)) functions(matching)
            return true
        }
        // Inside a call: keys in ref(…), functions elsewhere
        val call = innermostCall(value) ?: return false
        val current = value.substring(maxOf(value.lastIndexOf('('), value.lastIndexOf(','), value.lastIndexOf('=')) + 1).trimStart()
        if (!current.all { it.isLetterOrDigit() || it == '_' }) return false
        val matching = result.withPrefixMatcher(current)
        if (call == "ref") keys(context, matching, dollar = false) else functions(matching)
        return true
    }

    /** The name of the innermost call still open at the end of [text], or null. */
    private fun innermostCall(text: String): String? {
        var depth = 0
        for (i in text.indices.reversed()) {
            when (text[i]) {
                ')' -> depth++
                '(' -> if (depth == 0) {
                    var start = i
                    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
                    return text.substring(start, i).takeIf { it.isNotEmpty() }
                } else {
                    depth--
                }
            }
        }
        return null
    }

    private fun functions(result: CompletionResultSet) {
        for (function in VarlockCatalog.FUNCTIONS) {
            val source = function.plugin?.let { SecretsBundle.message(it.nameKey) } ?: SecretsBundle.message("varlock.completion.core")
            result.addElement(LookupElementBuilder.create(function.name).withTailText(function.signature, true).withTypeText(source, true).withInsertHandler(PARENTHESES))
        }
    }

    private fun keys(context: VarlockContext, result: CompletionResultSet, dollar: Boolean) {
        for ((key, definitions) in context.keys) {
            val element = LookupElementBuilder.create(if (dollar) "$$key" else key).withLookupString(key).withPresentableText(key)
                .withTypeText(definitions.joinToString { it.file.name }, true)
            result.addElement(element)
        }
    }

    private fun valueElement(value: String): LookupElement {
        val element = LookupElementBuilder.create(value).withTypeText(SecretsBundle.message("varlock.completion.value"), true)
        return if (value.endsWith("()")) element.withInsertHandler { context, _ -> context.editor.caretModel.moveToOffset(context.tailOffset - 1) } else element
    }

    private val PARENTHESES: InsertHandler<LookupElement> = InsertHandler { context, _ ->
        val tail = context.tailOffset
        val text = context.document.charsSequence
        if (tail >= text.length || text[tail] != '(') context.document.insertString(tail, "()")
        context.editor.caretModel.moveToOffset(tail + 1)
    }
}
