package cz.xprees.secrets.ui.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.ui.settings.SecretsConfigurable
import javax.swing.Icon

class SecretsStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = SecretsStatusBarWidget.ID

    override fun getDisplayName(): String = SecretsBundle.message("status.display.name")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = SecretsStatusBarWidget(project)
}

/**
 * The bound environment in the status bar (`Infisical: dev`), with a switcher like the Git branch widget. Production-like
 * environments (see the prod guard setting) show a warning icon.
 */
class SecretsStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    @Volatile
    private var text: String? = null

    @Volatile
    private var environment: String? = null

    @Volatile
    private var tooltip: String? = null
    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(ProjectSecretsSettings.BINDING_TOPIC, ProjectSecretsSettings.BindingListener { refresh() })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SecretsAppSettings.TOPIC, SecretsAppSettings.Listener { refresh() })
        refresh()
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getSelectedValue(): String? = text

    override fun getTooltipText(): String? = tooltip

    override fun getIcon(): Icon? = environment?.let { if (ProdGuard.matches(it)) AllIcons.General.Warning else SecretsIcons.PROVIDER }

    override fun getPopup(): JBPopup? {
        val snapshot = KeysIndex.getInstance(project).current()
        val environments = snapshot?.environments?.keys?.toList().orEmpty().ifEmpty { listOfNotNull(environment) }
        val items = environments + listOf(DEFAULT_ITEM, SETTINGS_ITEM)
        return JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<String>(SecretsBundle.message("status.popup.title"), items) {
            override fun getTextFor(value: String): String = when (value) {
                DEFAULT_ITEM -> SecretsBundle.message("status.popup.default")
                SETTINGS_ITEM -> SecretsBundle.message("status.popup.settings")
                else -> value
            }

            override fun getIconFor(value: String): Icon? = when {
                value == SETTINGS_ITEM -> AllIcons.General.Settings
                value == DEFAULT_ITEM -> null
                ProdGuard.matches(value) -> AllIcons.General.Warning
                value == environment -> AllIcons.Actions.Checked
                else -> null
            }

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep {
                    when (selectedValue) {
                        SETTINGS_ITEM -> SecretsConfigurable.show(project)
                        DEFAULT_ITEM -> ProjectSecretsSettings.getInstance(project).update { this.environment = null }
                        else -> ProjectSecretsSettings.getInstance(project).update { this.environment = selectedValue }
                    }
                }
                return PopupStep.FINAL_CHOICE
            }
        })
    }

    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val provider = SecretProvider.all().firstOrNull()
            val binding = provider?.let { runCatching { SecretBindingService.getInstance(project).binding(it) }.getOrNull() }
            environment = binding?.environment
            text = binding?.let { SecretsBundle.message("status.text", it.provider.displayName, it.environment) }
            tooltip = binding?.let { SecretsBundle.message("status.tooltip", it.connection.name, it.projectId ?: "–", it.environment, it.environmentSource, it.path) }
            ApplicationManager.getApplication().invokeLater({ statusBar?.updateWidget(ID) }, ModalityState.any(), project.disposed)
        }
    }

    companion object {
        const val ID = "SecretsForInfisical"
        private const val DEFAULT_ITEM = "\u0000default"
        private const val SETTINGS_ITEM = "\u0000settings"
    }
}
