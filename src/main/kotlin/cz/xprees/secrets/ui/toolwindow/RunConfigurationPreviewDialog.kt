package cz.xprees.secrets.ui.toolwindow

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.resolve.EnvResolution
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.run.EnvFileReferences
import cz.xprees.secrets.run.EnvPatcher
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/**
 * Dry run: which variables a run configuration would get and where each comes from. Values stay masked; nothing is
 * launched. Uses cached values when fresh enough, like the rest of the tool window.
 */
class RunConfigurationPreviewDialog(private val project: Project) : DialogWrapper(project) {
    private val configurations: List<RunnerAndConfigurationSettings> =
        RunManager.getInstance(project).allSettings.filter { EnvPatcher.find(it.configuration) != null }
    private val combo = ComboBox(configurations.toTypedArray()).apply {
        renderer = textListCellRenderer { settings: RunnerAndConfigurationSettings? -> settings?.name.orEmpty() }
        configurations.firstOrNull { cfg -> EnvPatcher.find(cfg.configuration)?.second?.let(LaunchSecretResolver.getInstance(project)::hasReferences) == true }
            ?.let { selectedItem = it }
        addActionListener { refresh() }
    }
    private val tableModel = object : DefaultTableModel(
        arrayOf(SecretsBundle.message("preview.column.name"), SecretsBundle.message("preview.column.value"), SecretsBundle.message("preview.column.source")),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val status = JBLabel()

    init {
        title = SecretsBundle.message("preview.title")
        setOKButtonText(SecretsBundle.message("preview.close"))
        init()
        refresh()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("preview.configuration")) { cell(combo).align(Align.FILL) }
        row {
            cell(JBScrollPane(JBTable(tableModel)).apply { preferredSize = JBUI.size(640, 320) }).align(Align.FILL)
        }.resizableRow()
        row { cell(status) }
    }

    private fun refresh() {
        tableModel.rowCount = 0
        val settings = combo.selectedItem as? RunnerAndConfigurationSettings
        if (settings == null) {
            status.text = SecretsBundle.message("preview.none")
            return
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val configured = EnvPatcher.find(settings.configuration)?.second.orEmpty()
        val envs = EnvFileReferences.effective(settings.configuration, configured, resolver).first
        val plan = resolver.plan(envs)
        if (!plan.hasSecrets) {
            envs.forEach { (k, v) -> tableModel.addRow(arrayOf(k, v, SecretsBundle.message("preview.source.explicit"))) }
            status.text = SecretsBundle.message("preview.no.references")
            return
        }
        status.text = SecretsBundle.message("preview.resolving")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { resolver.resolve(plan, fresh = false) }
            ApplicationManager.getApplication().invokeLater({
                if (settings !== combo.selectedItem) return@invokeLater
                outcome.onSuccess { result ->
                    for ((name, value) in result.environment) {
                        val source = result.sources[name]
                        val shown = when {
                            value.isEmpty() -> SecretsBundle.message("preview.empty")
                            source is EnvResolution.Source.Explicit -> value
                            else -> SecretValue.MASK
                        }
                        tableModel.addRow(arrayOf(name, shown, describe(source)))
                    }
                    status.text = (listOf(SecretsBundle.message("preview.resolved", result.environment.size)) + result.warnings).joinToString("  ·  ")
                }.onFailure { error ->
                    status.text = "<html>" + (error.message ?: error.toString()).replace("<", "&lt;").replace("\n", "<br>") + "</html>"
                }
            }, ModalityState.stateForComponent(combo))
        }
    }

    private fun describe(source: EnvResolution.Source?): String = when (source) {
        is EnvResolution.Source.LocalOverride -> SecretsBundle.message("preview.source.override", describe(source.replaced))
        is EnvResolution.Source.Reference -> SecretsBundle.message("preview.source.reference", source.reference.format(), EnvResolution.describe(source.scope))
        is EnvResolution.Source.Scope -> SecretsBundle.message("preview.source.scope", source.reference.format(), source.secretPath)
        else -> SecretsBundle.message("preview.source.explicit")
    }
}
