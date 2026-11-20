package cz.xprees.secrets.ui.actions

import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.run.EnvPatcher
import cz.xprees.secrets.run.SecretsNotifications
import org.jdom.Element
import javax.swing.JComponent

/**
 * Picks secrets of the bound folder and writes **references** to them (never values) into a run configuration's environment
 * variables: the same entries one would type into its Environment Variables dialog, for every supported configuration type.
 */
class AddSecretsToRunConfigurationAction : DumbAwareAction(AllIcons.General.Add) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AddSecretsDialog(project).show()
    }

    companion object {
        /** Name of the variable that carries a whole-folder import (only a label: it isn't passed to the process). */
        const val SCOPE_VARIABLE = "INFISICAL"

        /**
         * Adds [additions] to [settings]' configuration. Existing variables with plain values are kept (never overwritten by a
         * reference); returns the names that were skipped. Only references are written, through the configuration's public XML
         * form, so the IDE stores nothing secret.
         */
        fun addTo(project: Project, settings: RunnerAndConfigurationSettings, additions: Map<String, String>): List<String> {
            val configuration = settings.configuration
            val (patcher, current) = EnvPatcher.find(configuration) ?: return additions.keys.toList()
            val resolver = LaunchSecretResolver.getInstance(project)
            val skipped = additions.keys.filter { name ->
                val existing = current[name]
                existing != null && existing.isNotEmpty() && !resolver.hasReferences(mapOf(name to existing))
            }
            val merged = LinkedHashMap(current).apply { putAll(additions.filterKeys { it !in skipped }) }
            val updated = patcher.copyWith(configuration, merged)
            // Copy the updated state back into the stored configuration through its public XML form
            val state = Element("configuration")
            (updated as com.intellij.execution.configurations.RunConfiguration).writeExternal(state)
            configuration.readExternal(state)
            project.messageBus.syncPublisher(RunManagerListener.TOPIC).runConfigurationChanged(settings)
            return skipped
        }
    }
}

private class AddSecretsDialog(private val project: Project) : DialogWrapper(project) {
    private val snapshot = KeysIndex.getInstance(project).current()
    private val configurations = RunManager.getInstance(project).allSettings.filter { EnvPatcher.find(it.configuration) != null }
    private val configurationCombo = ComboBox(configurations.toTypedArray()).apply {
        renderer = textListCellRenderer { s: RunnerAndConfigurationSettings? -> s?.name.orEmpty() }
        RunManager.getInstance(project).selectedConfiguration?.takeIf { it in configurations }?.let { selectedItem = it }
    }
    private val wholeFolder = JBCheckBox(SecretsBundle.message("add.secrets.whole.folder", snapshot?.let { "${it.boundEnvironment}:${it.path}" } ?: ""))
    private val keys = CheckBoxList<String>().apply {
        snapshot?.boundKeys?.keys?.sorted()?.forEach { addItem(it, it, false) }
    }

    init {
        title = SecretsBundle.message("add.secrets.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("add.secrets.configuration")) { cell(configurationCombo).align(AlignX.FILL).resizableColumn() }
        row { cell(wholeFolder) }
        row { cell(JBScrollPane(keys).apply { preferredSize = JBUI.size(420, 240) }).align(Align.FILL) }.resizableRow()
        row { comment(SecretsBundle.message("add.secrets.comment")) }
    }

    override fun doValidate(): ValidationInfo? = when {
        snapshot == null -> ValidationInfo(SecretsBundle.message("add.secrets.no.binding"))
        configurationCombo.selectedItem == null -> ValidationInfo(SecretsBundle.message("add.secrets.no.configuration"), configurationCombo)
        !wholeFolder.isSelected && selectedKeys().isEmpty() -> ValidationInfo(SecretsBundle.message("add.secrets.nothing"), keys)
        else -> null
    }

    private fun selectedKeys(): List<String> = (0 until keys.itemsCount).mapNotNull { keys.getItemAt(it) }.filter { keys.isItemSelected(it) }

    override fun doOKAction() {
        val settings = configurationCombo.selectedItem as? RunnerAndConfigurationSettings ?: return
        val snapshot = snapshot ?: return
        val additions = LinkedHashMap<String, String>()
        if (wholeFolder.isSelected) additions[AddSecretsToRunConfigurationAction.SCOPE_VARIABLE] = snapshot.reference(null)
        selectedKeys().forEach { additions[it] = snapshot.reference(it) }
        val skipped = AddSecretsToRunConfigurationAction.addTo(project, settings, additions)
        SecretsNotifications.info(
            project,
            SecretsBundle.message("add.secrets.done", additions.size - skipped.size, settings.name),
            if (skipped.isEmpty()) "" else SecretsBundle.message("add.secrets.skipped", skipped.joinToString()),
        )
        super.doOKAction()
    }
}
