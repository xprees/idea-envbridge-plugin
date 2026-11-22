package cz.xprees.secrets.ui.auth

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import cz.xprees.secrets.SecretsBundle
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * A modal list to pick one of [items] from, with a search field that filters the rows (by [text], case-insensitively).
 * Enter or a double click picks the selected row; [chosen] is null when nothing is selected.
 */
class ChooseItemDialog<T : Any>(
    project: Project?,
    title: String,
    private val items: List<T>,
    private val text: (T) -> String,
    selected: T?,
) : DialogWrapper(project) {
    private val model = CollectionListModel(items)
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = textListCellRenderer { item: T? -> item?.let(text).orEmpty() }
        visibleRowCount = 12
    }
    private val search = SearchTextField(false)

    val chosen: T? get() = list.selectedValue

    init {
        this.title = title
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filter()
        })
        ScrollingUtil.installActions(list, search.textEditor)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.selectedValue == null) return false
                doOKAction()
                return true
            }
        }.installOn(list)
        list.setSelectedValue(selected ?: items.firstOrNull(), true)
        init()
    }

    private fun filter() {
        val query = search.text.trim()
        val previous = list.selectedValue
        model.replaceAll(if (query.isEmpty()) items else items.filter { text(it).contains(query, ignoreCase = true) })
        if (previous != null && model.contains(previous)) list.setSelectedValue(previous, true) else if (model.size > 0) list.selectedIndex = 0
    }

    override fun doValidate(): ValidationInfo? =
        if (list.selectedValue == null) ValidationInfo(SecretsBundle.message("choose.validation.none"), list) else null

    override fun createCenterPanel(): JComponent = BorderLayoutPanel(0, JBUI.scale(6))
        .addToTop(search)
        .addToCenter(ScrollPaneFactory.createScrollPane(list))
        .apply { preferredSize = JBUI.size(520, 320) }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor
}
