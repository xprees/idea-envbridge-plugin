package cz.xprees.secrets.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/**
 * Compares the bound folder of two environments key by key: present in both and equal, different, or only in one. Values are
 * compared in memory and never shown.
 */
class CompareEnvironmentsDialog(private val project: Project) : DialogWrapper(project) {
    private val environments = KeysIndex.getInstance(project).current()?.environments?.keys?.toList().orEmpty()
    private val left = ComboBox(environments.toTypedArray())
    private val right = ComboBox(environments.toTypedArray()).apply { if (environments.size > 1) selectedIndex = 1 }
    private val model = object : DefaultTableModel(
        arrayOf(SecretsBundle.message("compare.column.key"), SecretsBundle.message("compare.column.status")),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val status = JBLabel()

    init {
        title = SecretsBundle.message("compare.title")
        setOKButtonText(SecretsBundle.message("preview.close"))
        init()
        left.addActionListener { refresh() }
        right.addActionListener { refresh() }
        refresh()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("compare.environments")) {
            cell(left)
            label("↔")
            cell(right)
        }
        row { cell(JBScrollPane(JBTable(model)).apply { preferredSize = JBUI.size(560, 320) }).align(Align.FILL) }.resizableRow()
        row { cell(status) }
    }

    private fun refresh() {
        model.rowCount = 0
        val a = left.selectedItem as? String ?: return
        val b = right.selectedItem as? String ?: return
        status.text = SecretsBundle.message("preview.resolving")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                val binding = SecretBindingService.getInstance(project).binding(SecretProvider.all().first())
                val projectId = binding.defaults().projectId
                val service = SecretsService.getInstance()
                val valuesA = service.secrets(binding.connection, SecretScope(projectId, a, binding.path)).associate { it.key to it.value }
                val valuesB = service.secrets(binding.connection, SecretScope(projectId, b, binding.path)).associate { it.key to it.value }
                (valuesA.keys + valuesB.keys).sorted().map { key ->
                    key to when {
                        key !in valuesB -> SecretsBundle.message("compare.only", a)
                        key !in valuesA -> SecretsBundle.message("compare.only", b)
                        valuesA[key] == valuesB[key] -> SecretsBundle.message("compare.same")
                        else -> SecretsBundle.message("compare.different")
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater({
                if (a != left.selectedItem || b != right.selectedItem) return@invokeLater
                outcome.onSuccess { rows ->
                    rows.forEach { (key, state) -> model.addRow(arrayOf(key, state)) }
                    status.text = SecretsBundle.message("compare.summary", rows.size, rows.count { it.second != SecretsBundle.message("compare.same") })
                }.onFailure { status.text = it.message.orEmpty() }
            }, ModalityState.stateForComponent(left))
        }
    }
}
