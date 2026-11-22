package cz.xprees.secrets.ui.secrets

import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.provider.MetadataEditor
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/**
 * The metadata part of the secret dialogs, filled in once [MetadataEditor] has been read (in the background): tags picked from
 * the provider's list (Infisical) or a description and `key=value` tags typed in (Vault's `custom_metadata`). Empty (and
 * invisible) while there is nothing to edit. On the EDT.
 */
class MetadataFields {
    /** Where the fields appear; empty until [show] or [showError]. */
    val component: JPanel = JPanel(BorderLayout()).apply { isVisible = false }

    private var editor: MetadataEditor? = null
    private var tagList: CheckBoxList<String>? = null
    private var descriptionField: JBTextField? = null
    private var tagsArea: JBTextArea? = null

    /** Shows what [editor] offers, filled with its current metadata. */
    fun show(editor: MetadataEditor) {
        this.editor = editor
        component.removeAll()
        val content = panel {
            editor.tagChoices?.let { choices ->
                if (choices.isEmpty()) {
                    row(SecretsBundle.message("secret.metadata.tags")) { comment(SecretsBundle.message("secret.metadata.tags.none")) }
                } else {
                    val list = CheckBoxList<String>().apply {
                        choices.forEach { addItem(it, it, it in editor.current.tags) }
                    }
                    tagList = list
                    row(SecretsBundle.message("secret.metadata.tags")) {
                        cell(ScrollPaneFactory.createScrollPane(list).apply { preferredSize = Dimension(240, 96.coerceAtMost(24 * choices.size + 8)) })
                            .align(AlignX.FILL)
                    }
                }
            }
            if (editor.freeForm) {
                val description = JBTextField(editor.current.description, 30)
                val tags = JBTextArea(editor.current.tags.entries.joinToString("\n") { (k, v) -> "$k=$v" }, 3, 30)
                descriptionField = description
                tagsArea = tags
                row(SecretsBundle.message("secret.metadata.description")) { cell(description).align(AlignX.FILL) }
                row(SecretsBundle.message("secret.metadata.custom")) {
                    cell(ScrollPaneFactory.createScrollPane(tags)).align(AlignX.FILL).comment(SecretsBundle.message("secret.metadata.custom.comment"))
                }
            }
            editor.note?.let { row { comment(it, maxLineLength = 70) } }
        }
        component.add(content, BorderLayout.CENTER)
        component.isVisible = true
        component.revalidate()
    }

    /** Shows why the metadata can't be edited (a missing permission, say); the rest of the dialog still works. */
    fun showError(message: String) {
        editor = null
        component.removeAll()
        component.add(JBLabel(SecretsBundle.message("secret.metadata.unavailable", message)).apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.CENTER)
        component.isVisible = true
        component.revalidate()
    }

    /** Why the typed metadata can't be saved, or null. */
    fun validate(): ValidationInfo? {
        val area = tagsArea ?: return null
        return parseFreeForm(area.text).exceptionOrNull()?.let { ValidationInfo(it.message.orEmpty(), area) }
    }

    /** The metadata as edited, or null when nothing can be edited or nothing changed. */
    fun result(): SecretMetadata? {
        val editor = editor ?: return null
        val tags = when {
            tagList != null -> editor.tagChoices.orEmpty().filter { tagList!!.isItemSelected(it) }.associateWith { "" }
            tagsArea != null -> parseFreeForm(tagsArea!!.text).getOrNull() ?: return null
            else -> editor.current.tags
        }
        val description = descriptionField?.text?.trim() ?: editor.current.description
        val edited = editor.current.copy(description = description, tags = tags)
        return edited.takeIf { it.tags != editor.current.tags || it.description != editor.current.description }
    }

    companion object {
        /** `key=value` lines (blank lines skipped) as tags; a line without a key, or a key twice, is refused with the reason. */
        fun parseFreeForm(text: String): Result<Map<String, String>> = runCatching {
            val tags = linkedMapOf<String, String>()
            for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                val key = line.substringBefore('=').trim()
                val value = if ('=' in line) line.substringAfter('=').trim() else ""
                require(key.isNotEmpty()) { SecretsBundle.message("secret.metadata.custom.no.key", line) }
                require(key !in tags) { SecretsBundle.message("secret.metadata.custom.twice", key) }
                require(key != "description") { SecretsBundle.message("secret.metadata.custom.description") }
                tags[key] = value
            }
            tags
        }
    }
}
