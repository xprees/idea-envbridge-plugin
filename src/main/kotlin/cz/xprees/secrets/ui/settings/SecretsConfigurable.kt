package cz.xprees.secrets.ui.settings

import com.intellij.util.ui.update.UiNotifyConnector
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
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

    /** Connections whose last sign-in (here) failed: shown so, not as signed in, though credentials may be stored. */
    private val signInFailed = ConcurrentHashMap.newKeySet<String>()

    private val binding = ProjectBindingPanel(project)

    /** Starts adding a connection once the page is shown (see [show]). */
    private var addOnOpen = false

    override fun createPanel(): DialogPanel {
        connectionList.cellRenderer = ConnectionRenderer()
        connectionList.emptyText.apply {
            text = SecretsBundle.message("settings.connections.empty")
            appendSecondaryText(SecretsBundle.message("toolwindow.add.connection"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                addConnection(RelativePoint(connectionList, java.awt.Point(JBUI.scale(8), connectionList.height / 2)))
            }
        }
        refreshSignInStates()
        val decorated = ToolbarDecorator.createDecorator(connectionList)
            // The provider chooser opens under the + button: a popup centred on the focus can end up behind the
            // modal Settings dialog (it did in Rider)
            .setAddAction { button -> addConnection(button.preferredPopupPoint) }
            .setEditAction { editConnection() }
            .setRemoveAction { removeConnection() }
            .setPreferredSize(JBUI.size(480, 110))
            .createPanel()

        if (addOnOpen) {
            addOnOpen = false
            UiNotifyConnector.doWhenFirstShown(connectionList) { addConnection(RelativePoint(connectionList, java.awt.Point(JBUI.scale(8), JBUI.scale(8)))) }
        }
        return panel {
            group(SecretsBundle.message("settings.connections")) {
                row { cell(decorated).align(AlignX.FILL) }
                row {
                    // Off without a connection to act on
                    val anyConnection = object : com.intellij.ui.layout.ComponentPredicate() {
                        override fun invoke() = selectedConnection() != null
                        override fun addListener(listener: (Boolean) -> Unit) {
                            connectionList.addListSelectionListener { listener(invoke()) }
                            connectionModel.addListDataListener(object : javax.swing.event.ListDataListener {
                                override fun intervalAdded(e: javax.swing.event.ListDataEvent?) = listener(invoke())
                                override fun intervalRemoved(e: javax.swing.event.ListDataEvent?) = listener(invoke())
                                override fun contentsChanged(e: javax.swing.event.ListDataEvent?) = listener(invoke())
                            })
                        }
                    }
                    button(SecretsBundle.message("action.sign.in")) { selectedConnection()?.let(::signIn) }.enabledIf(anyConnection)
                    button(SecretsBundle.message("action.sign.out")) {
                        selectedConnection()?.let { connection ->
                            signInFailed.remove(connection.id)
                            SignInHelper.signOut(project, connection) { refreshSignInStates() }
                        }
                    }.enabledIf(anyConnection)
                }
                row { comment(SecretsBundle.message("settings.connections.comment")) }
            }
            // Opened without a project (Welcome screen): settings of the IDE's default project would only be a template for
            // new projects, so the project's sections are left out
            if (project.isDefault) {
                row { comment(SecretsBundle.message("settings.no.project"), maxLineLength = 80) }
            } else {
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
                row {
                    checkBox(SecretsBundle.message("settings.prod.guard.enabled")).bindSelected(app.state::prodGuardEnabled)
                        .comment(SecretsBundle.message("settings.prod.guard.enabled.comment"))
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
                row {
                    checkBox(SecretsBundle.message("settings.envspec.decorators")).bindSelected(app.state::envSpecDecorators)
                        .comment(SecretsBundle.message("settings.envspec.decorators.comment"))
                }
                row {
                    checkBox(SecretsBundle.message("settings.envspec.layered")).bindSelected(app.state::layeredEnvFiles)
                        .comment(SecretsBundle.message("settings.envspec.layered.comment"))
                }
                row {
                    // The one switch of the varlock compatibility mode (editor support in editor/varlock)
                    checkBox(SecretsBundle.message("settings.varlock")).bindSelected(app.state::varlockCompatibility)
                        .comment(SecretsBundle.message("settings.varlock.comment"))
                }
            }
            if (!project.isDefault) {
                group(SecretsBundle.message("settings.overrides")) {
                    row { cell(overridesPanel()).align(AlignX.FILL) }
                    row { comment(SecretsBundle.message("settings.overrides.comment")) }
                }
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

    /** Adds a connection; with several providers, a chooser opens at [where] first. */
    private fun addConnection(where: RelativePoint) {
        val providers = SecretProvider.all()
        if (providers.size > 1) {
            JBPopupFactory.getInstance().createPopupChooserBuilder(providers)
                .setTitle(SecretsBundle.message("settings.add.connection.provider"))
                .setRenderer(listCellRenderer {
                    icon(value.icon)
                    text(value.displayName)
                })
                .setItemChosenCallback { addConnection(it) }
                .createPopup()
                .show(where)
        } else {
            addConnection(providers.firstOrNull() ?: return)
        }
    }

    private fun addConnection(provider: SecretProvider) {
        val dialog = ConnectionDialog(project, null, provider)
        if (!dialog.showAndGet()) return
        val connection = dialog.result ?: return
        app.addOrUpdate(connection)
        reloadConnections(select = connection)
        if (Messages.showYesNoDialog(project, SecretsBundle.message("settings.sign.in.now", connection.name), SecretsBundle.message("settings.display.name"), SecretsIcons.PROVIDER) == Messages.YES) {
            signIn(connection)
        }
    }

    private fun signIn(connection: SecretConnection) {
        SignInHelper.signIn(project, connection) { ok ->
            if (ok) signInFailed.remove(connection.id) else signInFailed.add(connection.id)
            refreshSignInStates()
        }
    }

    private fun editConnection() {
        val current = connectionList.selectedValue ?: return
        val dialog = ConnectionDialog(project, current)
        if (!dialog.showAndGet()) return
        val connection = dialog.result ?: return
        app.addOrUpdate(connection)
        SecretsService.getInstance().invalidate(connection.id)
        signInFailed.remove(connection.id)
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
            if (cz.xprees.secrets.core.provider.usesBaseUrl(value)) append("  ${value.baseUrl}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val method = SecretProvider.byId(value.providerId)?.authMethod(value.authMethodId)?.displayName ?: value.authMethodId
            append("  · $method", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            when (signedIn[value.id]) {
                true if value.id in signInFailed -> append("  " + SecretsBundle.message("settings.signin.failed"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                true -> append("  " + SecretsBundle.message("settings.signed.in"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                false -> append("  " + SecretsBundle.message("toolwindow.signed.out"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                null -> Unit
            }
        }
    }

    companion object {
        /** Opens this page; with [addConnection], it starts adding a connection right away. */
        fun show(project: Project, addConnection: Boolean = false) =
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SecretsConfigurable::class.java) { if (addConnection) it.addOnOpen = true }
    }
}
