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
import com.intellij.util.ui.JBUI
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.ui.auth.SignInHelper
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JList

/**
 * Settings › Tools › Secrets for Infisical: connections (application level; edits apply right away, secrets go to the
 * credential store), the project binding and general options (applied with Apply/OK).
 */
class SecretsConfigurable(private val project: Project) : BoundConfigurable(SecretsBundle.message("settings.display.name")) {
    private val app = SecretsAppSettings.getInstance()
    private val projectSettings = ProjectSecretsSettings.getInstance(project)

    private val connectionModel = CollectionListModel(app.connections)
    private val connectionList = JBList(connectionModel)
    private val signedIn = ConcurrentHashMap<String, Boolean>()

    private val bindingConnection = ComboBox<SecretConnection?>()
    private val bindingProject = ComboBox<String>().apply { isEditable = true }
    private val bindingEnvironment = ComboBox<String>().apply { isEditable = true }
    private var loadedProjects: List<ProviderProject> = emptyList()
    private val effectiveBinding = JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }

    override fun createPanel(): DialogPanel {
        connectionList.cellRenderer = ConnectionRenderer()
        refreshSignInStates()
        val decorated = ToolbarDecorator.createDecorator(connectionList)
            .setAddAction { addConnection() }
            .setEditAction { editConnection() }
            .setRemoveAction { removeConnection() }
            .setPreferredSize(JBUI.size(480, 110))
            .createPanel()

        resetBindingCombos()
        bindingConnection.addActionListener { loadProjects() }
        bindingProject.addActionListener { updateEnvironments() }

        return panel {
            group(SecretsBundle.message("settings.connections")) {
                row { cell(decorated).align(AlignX.FILL) }
                row {
                    button(SecretsBundle.message("action.sign.in")) { selectedConnection()?.let { SignInHelper.signIn(project, it) { refreshSignInStates() } } }
                    button(SecretsBundle.message("action.sign.out")) { selectedConnection()?.let { SignInHelper.signOut(project, it) { refreshSignInStates() } } }
                }
                row { comment(SecretsBundle.message("settings.connections.comment"), maxLineLength = 90) }
            }
            group(SecretsBundle.message("settings.binding")) {
                row(SecretsBundle.message("settings.binding.connection")) { cell(bindingConnection).align(AlignX.FILL) }
                row(SecretsBundle.message("settings.binding.project")) { cell(bindingProject).align(AlignX.FILL) }
                row(SecretsBundle.message("settings.binding.environment")) { cell(bindingEnvironment).align(AlignX.FILL) }
                row(SecretsBundle.message("settings.binding.path")) {
                    textField().bindText({ projectSettings.state.path.orEmpty() }, { projectSettings.update { path = it.trim().ifEmpty { null } } })
                        .align(AlignX.FILL).applyToComponent { emptyText.text = "/" }
                }
                row {
                    checkBox(SecretsBundle.message("settings.binding.project.file"))
                        .bindSelected({ projectSettings.state.useProjectFile }, { projectSettings.update { useProjectFile = it } })
                }
                row { comment(SecretsBundle.message("settings.binding.comment"), maxLineLength = 90) }
                row { cell(effectiveBinding) }
                onApply { applyBinding() }
                onReset { resetBindingCombos() }
                onIsModified { isBindingModified() }
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
            }
        }
    }

    override fun apply() {
        super.apply()
        SecretsService.getInstance().invalidate()
        app.fireChanged()
        updateEffectiveBinding()
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
        resetBindingCombos()
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

    // ---- Project binding -------------------------------------------------------------------------------------------

    private fun resetBindingCombos() {
        val state = projectSettings.state
        val items = listOf<SecretConnection?>(null) + app.connections
        bindingConnection.removeAllItems()
        items.forEach(bindingConnection::addItem)
        bindingConnection.renderer = textListCellRenderer { connection: SecretConnection? ->
            connection?.name ?: SecretsBundle.message("settings.binding.auto")
        }
        bindingConnection.selectedItem = items.firstOrNull { it?.id == state.connectionId }
        bindingProject.renderer = textListCellRenderer { id: String? ->
            loadedProjects.firstOrNull { it.id == id }?.let { "${it.name}  ($id)" } ?: id.orEmpty()
        }
        bindingProject.selectedItem = state.projectId.orEmpty()
        bindingEnvironment.selectedItem = state.environment.orEmpty()
        loadProjects()
        updateEffectiveBinding()
    }

    private fun loadProjects() {
        val connection = (bindingConnection.selectedItem as? SecretConnection) ?: app.connections.firstOrNull() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val projects = runCatching { SecretsService.getInstance().projects(connection) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                loadedProjects = projects
                val current = bindingProject.editor.item?.toString().orEmpty()
                bindingProject.removeAllItems()
                bindingProject.addItem("")
                projects.forEach { bindingProject.addItem(it.id) }
                bindingProject.selectedItem = current
                updateEnvironments()
            }, ModalityState.any())
        }
    }

    private fun updateEnvironments() {
        val projectId = bindingProject.editor.item?.toString()?.trim().orEmpty()
        val current = bindingEnvironment.editor.item?.toString().orEmpty()
        bindingEnvironment.removeAllItems()
        bindingEnvironment.addItem("")
        loadedProjects.firstOrNull { it.id == projectId }?.environments?.forEach { bindingEnvironment.addItem(it.slug) }
        bindingEnvironment.selectedItem = current
    }

    private fun comboText(combo: ComboBox<String>) = (combo.editor.item ?: combo.selectedItem)?.toString()?.trim().orEmpty()

    private fun isBindingModified(): Boolean {
        val state = projectSettings.state
        return (bindingConnection.selectedItem as? SecretConnection)?.id != state.connectionId ||
            comboText(bindingProject) != state.projectId.orEmpty() ||
            comboText(bindingEnvironment) != state.environment.orEmpty()
    }

    private fun applyBinding() {
        projectSettings.update {
            connectionId = (bindingConnection.selectedItem as? SecretConnection)?.id
            projectId = comboText(bindingProject).ifEmpty { null }
            environment = comboText(bindingEnvironment).ifEmpty { null }
        }
    }

    private fun updateEffectiveBinding() {
        val provider = SecretProvider.all().firstOrNull() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                val b = SecretBindingService.getInstance(project).binding(provider)
                SecretsBundle.message(
                    "settings.binding.effective",
                    b.connection.name,
                    b.projectId ?: SecretsBundle.message("settings.binding.no.project"),
                    b.environment,
                    b.environmentSource,
                    b.path,
                    b.projectFile?.file?.fileName?.toString() ?: SecretsBundle.message("settings.binding.no.file"),
                )
            } catch (e: Exception) {
                e.message.orEmpty()
            }
            ApplicationManager.getApplication().invokeLater({ effectiveBinding.text = "<html>${text.replace("<", "&lt;")}</html>" }, ModalityState.any())
        }
    }

    companion object {
        fun show(project: Project) = ShowSettingsUtil.getInstance().showSettingsDialog(project, SecretsConfigurable::class.java)
    }
}
