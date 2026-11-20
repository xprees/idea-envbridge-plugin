package cz.xprees.secrets.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.util.ui.JBUI
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.LocalOverrides
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.ui.auth.SignInHelper
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JList

/**
 * Settings › Tools › EnvBridge: connections (application level; edits apply right away, secrets go to the
 * credential store), the project binding and general options (applied with Apply/OK).
 */
class SecretsConfigurable(private val project: Project) : BoundConfigurable(SecretsBundle.message("settings.display.name")) {
    private val app = SecretsAppSettings.getInstance()
    private val projectSettings = ProjectSecretsSettings.getInstance(project)

    private val connectionModel = CollectionListModel(app.connections)
    private val connectionList = JBList(connectionModel)
    private val signedIn = ConcurrentHashMap<String, Boolean>()

    private val binding = ProjectBindingPanel(project)

    override fun createPanel(): DialogPanel {
        connectionList.cellRenderer = ConnectionRenderer()
        refreshSignInStates()
        val decorated = ToolbarDecorator.createDecorator(connectionList)
            .setAddAction { addConnection() }
            .setEditAction { editConnection() }
            .setRemoveAction { removeConnection() }
            .setPreferredSize(JBUI.size(480, 110))
            .createPanel()

        return panel {
            group(SecretsBundle.message("settings.connections")) {
                row { cell(decorated).align(AlignX.FILL) }
                row {
                    button(SecretsBundle.message("action.sign.in")) { selectedConnection()?.let { SignInHelper.signIn(project, it) { refreshSignInStates() } } }
                    button(SecretsBundle.message("action.sign.out")) { selectedConnection()?.let { SignInHelper.signOut(project, it) { refreshSignInStates() } } }
                }
                row { comment(SecretsBundle.message("settings.connections.comment")) }
            }
            group(SecretsBundle.message("settings.binding")) {
                binding.build(this)
                onApply { binding.apply() }
                onReset { binding.reset() }
                onIsModified { binding.isModified() }
            }
            group(SecretsBundle.message("settings.terminal")) {
                lateinit var terminalCheckBox: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
                row {
                    terminalCheckBox = checkBox(SecretsBundle.message("terminal.enable"))
                        .bindSelected({ projectSettings.state.terminalEnabled }, { value -> projectSettings.update { terminalEnabled = value } })
                }
                row {
                    textArea().rows(3).align(AlignX.FILL)
                        .bindText(
                            { projectSettings.state.terminalReferences ?: ProjectSecretsSettings.DEFAULT_TERMINAL_REFERENCES },
                            { value -> projectSettings.update { terminalReferences = value } },
                        )
                        .comment(SecretsBundle.message("terminal.references.comment"))
                        .enabledIf(terminalCheckBox.selected)
                }
            }
            group(SecretsBundle.message("settings.general")) {
                row(SecretsBundle.message("settings.cache.ttl")) {
                    intTextField(0..86_400).columns(6).bindIntText(app.state::cacheTtlSeconds)
                    label(SecretsBundle.message("settings.seconds"))
                }.rowComment(SecretsBundle.message("settings.cache.comment"))
                row(SecretsBundle.message("settings.reveal")) {
                    intTextField(1..600).columns(6).bindIntText(app.state::revealSeconds)
                    label(SecretsBundle.message("settings.seconds"))
                }
                row(SecretsBundle.message("settings.clipboard")) {
                    intTextField(0..3600).columns(6).bindIntText(app.state::clipboardClearSeconds)
                    label(SecretsBundle.message("settings.seconds.zero.never"))
                }
                row(SecretsBundle.message("settings.prod.guard")) {
                    textField().align(AlignX.FILL).resizableColumn()
                        .bindText({ app.state.prodGuardPattern.orEmpty() }, { app.state.prodGuardPattern = it.trim() })
                }.rowComment(SecretsBundle.message("settings.prod.guard.comment"))
                row {
                    checkBox(SecretsBundle.message("settings.mask.console")).bindSelected(app.state::maskConsoleOutput)
                }
                row {
                    checkBox(SecretsBundle.message("settings.read.only")).bindSelected(app.state::readOnly)
                        .comment(SecretsBundle.message("settings.read.only.comment"))
                }
            }
            group(SecretsBundle.message("settings.overrides")) {
                row { cell(overridesPanel()).align(AlignX.FILL) }
                row { comment(SecretsBundle.message("settings.overrides.comment")) }
            }
        }
    }

    override fun apply() {
        super.apply()
        SecretsService.getInstance().invalidate()
        app.fireChanged()
    }

    // ---- Local overrides -------------------------------------------------------------------------------------------

    private val overrideModel = CollectionListModel<String>()

    /** The names of this project's local overrides (values stay in the credential store); remove-only. */
    private fun overridesPanel(): javax.swing.JComponent {
        val list = JBList(overrideModel).apply { emptyText.text = SecretsBundle.message("settings.overrides.empty") }
        ApplicationManager.getApplication().executeOnPooledThread {
            val names = LocalOverrides.getInstance(project).names().sorted()
            ApplicationManager.getApplication().invokeLater({ overrideModel.replaceAll(names) }, ModalityState.any())
        }
        return ToolbarDecorator.createDecorator(list)
            .disableAddAction()
            .setRemoveAction {
                val selected = list.selectedValuesList
                selected.forEach(overrideModel::remove)
                ApplicationManager.getApplication().executeOnPooledThread { selected.forEach(LocalOverrides.getInstance(project)::remove) }
            }
            .setPreferredSize(JBUI.size(480, 80))
            .createPanel()
    }

    // ---- Connections -----------------------------------------------------------------------------------------------

    private fun selectedConnection(): SecretConnection? = connectionList.selectedValue ?: connectionModel.items.singleOrNull()

    private fun addConnection() {
        val dialog = ConnectionDialog(project, null)
        if (!dialog.showAndGet()) return
        val connection = dialog.result ?: return
        app.addOrUpdate(connection)
        reloadConnections(select = connection)
        if (Messages.showYesNoDialog(project, SecretsBundle.message("settings.sign.in.now", connection.name), SecretsBundle.message("settings.display.name"), SecretsIcons.PROVIDER) == Messages.YES) {
            SignInHelper.signIn(project, connection) { refreshSignInStates() }
        }
    }

    private fun editConnection() {
        val current = connectionList.selectedValue ?: return
        val dialog = ConnectionDialog(project, current)
        if (!dialog.showAndGet()) return
        val connection = dialog.result ?: return
        app.addOrUpdate(connection)
        SecretsService.getInstance().invalidate(connection.id)
        reloadConnections(select = connection)
    }

    private fun removeConnection() {
        val current = connectionList.selectedValue ?: return
        if (Messages.showYesNoDialog(project, SecretsBundle.message("settings.remove.confirm", current.name), SecretsBundle.message("settings.display.name"), null) != Messages.YES) return
        val provider = SecretProvider.byId(current.providerId)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { SecretsService.getInstance().client(current).signOut() }
            CredentialVault.removeAll(current.id, provider?.authMethods?.flatMap { m -> m.secretOptions.map { it.key } }.orEmpty())
        }
        app.remove(current.id)
        SecretsService.getInstance().invalidate(current.id)
        reloadConnections(select = null)
    }

    private fun reloadConnections(select: SecretConnection?) {
        connectionModel.replaceAll(app.connections)
        select?.let { wanted -> connectionList.setSelectedValue(connectionModel.items.firstOrNull { it.id == wanted.id }, true) }
        binding.reset()
        refreshSignInStates()
    }

    private fun refreshSignInStates() {
        val connections = app.connections
        ApplicationManager.getApplication().executeOnPooledThread {
            connections.forEach { signedIn[it.id] = runCatching { SecretsService.getInstance().client(it).hasCredentials() }.getOrDefault(false) }
            ApplicationManager.getApplication().invokeLater({ connectionList.repaint() }, ModalityState.any())
        }
    }

    private inner class ConnectionRenderer : ColoredListCellRenderer<SecretConnection>() {
        override fun customizeCellRenderer(list: JList<out SecretConnection>, value: SecretConnection?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = SecretsIcons.PROVIDER
            append(value.name)
            append("  ${value.baseUrl}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val method = SecretProvider.byId(value.providerId)?.authMethod(value.authMethodId)?.displayName ?: value.authMethodId
            append("  · $method", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            when (signedIn[value.id]) {
                true -> append("  " + SecretsBundle.message("settings.signed.in"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                false -> append("  " + SecretsBundle.message("toolwindow.signed.out"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                null -> Unit
            }
        }
    }

    companion object {
        fun show(project: Project) = ShowSettingsUtil.getInstance().showSettingsDialog(project, SecretsConfigurable::class.java)
    }
}
