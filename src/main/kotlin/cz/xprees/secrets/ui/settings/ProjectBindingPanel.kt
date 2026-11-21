package cz.xprees.secrets.ui.settings

import com.intellij.ui.components.JBTextField

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.ProjectFileDefaults
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import javax.swing.JComponent

/**
 * The "This Project" part of the settings: connection, project, environment and folder as drop-downs filled from the
 * instance (names, not IDs), with a Check button that loads the chosen folder and says what it found. Fields left on
 * "Automatic" come from `.infisical.yaml` / `.infisical.json`.
 */
internal class ProjectBindingPanel(private val project: Project) {
    private val settings = ProjectSecretsSettings.getInstance(project)
    private val app = SecretsAppSettings.getInstance()

    /** A drop-down entry: [value] is what gets stored (null = automatic). */
    data class Choice(val value: String?, val label: String)

    /**
     * "Automatic" for the chosen connection: the provider's project file (`.infisical.yaml`) when it has one, else the
     * connection's default (a Vault connection's namespace).
     */
    private val automatic: Choice
        get() {
            val connection = connectionToUse()
            val file = connection?.let { SecretProvider.byId(it.providerId)?.projectFileName }
            return Choice(null, SecretsBundle.message("settings.binding.auto.short", file ?: SecretsBundle.message("settings.binding.auto.default")))
        }
    private val enterId = Choice(ENTER_ID, SecretsBundle.message("settings.binding.enter.id"))

    private val connectionCombo = ComboBox<SecretConnection?>().apply {
        renderer = textListCellRenderer { c: SecretConnection? -> c?.name ?: SecretsBundle.message("settings.binding.auto.connection") }
    }
    private val projectCombo = ComboBox<Choice>().apply { renderer = textListCellRenderer { c: Choice? -> c?.label.orEmpty() } }
    private val environmentCombo = ComboBox<Choice>().apply { renderer = textListCellRenderer { c: Choice? -> c?.label.orEmpty() } }
    private val folderCombo = ComboBox<String>().apply { isEditable = true }
    @org.jetbrains.annotations.VisibleForTesting
    internal val productionField = JBTextField()
    private val status = JBLabel().apply {
        setAllowAutoWrapping(true)
        setCopyable(true)
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    private var projects: List<ProviderProject> = emptyList()
    /** > 0 while the drop-downs are being refilled: their listeners must not react then. */
    private var updating = 0

    /** Adds the rows to [panel]; call [reset] afterwards. */
    fun build(panel: Panel) = with(panel) {
        row(SecretsBundle.message("settings.binding.connection")) { cell(connectionCombo).align(AlignX.FILL).resizableColumn() }
        row(SecretsBundle.message("settings.binding.project")) { cell(projectCombo).align(AlignX.FILL).resizableColumn() }
        row(SecretsBundle.message("settings.binding.environment")) { cell(environmentCombo).align(AlignX.FILL).resizableColumn() }
        row(SecretsBundle.message("settings.binding.path")) { cell(folderCombo).align(AlignX.FILL).resizableColumn() }
        row {
            // Nothing to check or load without a connection
            val anyConnection = object : com.intellij.ui.layout.ComponentPredicate() {
                override fun invoke() = connectionCombo.itemCount > 1 // the first item is Automatic
                override fun addListener(listener: (Boolean) -> Unit) {
                    connectionCombo.model.addListDataListener(object : javax.swing.event.ListDataListener {
                        override fun intervalAdded(e: javax.swing.event.ListDataEvent?) = listener(invoke())
                        override fun intervalRemoved(e: javax.swing.event.ListDataEvent?) = listener(invoke())
                        override fun contentsChanged(e: javax.swing.event.ListDataEvent?) = listener(invoke())
                    })
                }
            }
            button(SecretsBundle.message("settings.binding.check")) { check() }.enabledIf(anyConnection)
            button(SecretsBundle.message("settings.binding.reload")) { loadProjects(force = true) }.enabledIf(anyConnection)
        }
        row { cell(status).align(AlignX.FILL).resizableColumn() }
        row(SecretsBundle.message("settings.binding.production")) {
            cell(productionField).align(AlignX.FILL).resizableColumn()
            button(SecretsBundle.message("settings.binding.production.choose")) { chooseProduction(it.source as JComponent) }
        }.rowComment(SecretsBundle.message("settings.binding.production.comment"))
        row {
            checkBox(SecretsBundle.message("settings.binding.project.file.short"))
                .bindSelected({ settings.state.useProjectFile }, { value -> settings.update { useProjectFile = value } })
                .comment(SecretsBundle.message("settings.binding.project.file.comment"))
        }
        row {
            checkBox(SecretsBundle.message("settings.binding.restrict"))
                .bindSelected({ settings.state.restrictToBoundProject }, { value -> settings.update { restrictToBoundProject = value } })
                .comment(SecretsBundle.message("settings.binding.restrict.comment"))
        }
        row { comment(SecretsBundle.message("settings.binding.comment"), maxLineLength = 90) }

        connectionCombo.addActionListener {
            if (updating > 0) return@addActionListener
            // Projects and environments belong to a connection: another connection starts from Automatic
            setProjects(emptyList(), keep = null)
            fillEnvironments(keep = null)
            loadProjects(force = false)
        }
        projectCombo.addActionListener {
            if (updating > 0) return@addActionListener
            if ((projectCombo.selectedItem as? Choice)?.value == ENTER_ID) askForProjectId() else fillEnvironments(keep = null)
        }
        environmentCombo.addActionListener { if (updating == 0) loadFolders() }
    }

    // ---- State ---------------------------------------------------------------------------------------------------

    fun reset() {
        updating++
        try {
            val state = settings.state
            connectionCombo.removeAllItems()
            connectionCombo.addItem(null)
            app.connections.forEach(connectionCombo::addItem)
            connectionCombo.selectedItem = app.connection(state.connectionId)
            setProjects(projects, keep = state.projectId)
            fillEnvironments(keep = state.environment)
            folderCombo.removeAllItems()
            folderCombo.addItem("/")
            folderCombo.selectedItem = state.path ?: "/"
            productionField.text = state.productionEnvironments.orEmpty()
        } finally {
            updating--
        }
        status.text = ""
        loadProjects(force = false)
    }

    fun isModified(): Boolean {
        val state = settings.state
        // A binding to a connection removed since shows no connection: compare with what reset() could show
        // (Automatic, no connection chosen, is a binding too: its project, environment and folder count)
        val connection = app.connection(state.connectionId)
        if (!state.connectionId.isNullOrBlank() && connection == null) return selectedConnection() != null
        return selectedConnection()?.id != connection?.id || selectedProjectId() != state.projectId ||
            selectedEnvironment() != state.environment || selectedPath() != state.path ||
            selectedProduction() != state.productionEnvironments.orEmpty()
    }

    fun apply() {
        settings.update {
            connectionId = selectedConnection()?.id
            projectId = selectedProjectId()
            environment = selectedEnvironment()
            path = selectedPath()
            productionEnvironments = selectedProduction().ifEmpty { null }
        }
    }

    private fun selectedProduction(): String = ProjectSecretsSettings.parseEnvironments(productionField.text).joinToString(", ")

    /** A checkbox list of the chosen project's environments; the ticked ones fill the field. */
    private fun chooseProduction(anchor: JComponent) {
        val projectId = selectedProjectId() ?: effectiveProjectId()
        val environments = projects.firstOrNull { it.id == projectId }?.environments.orEmpty()
        if (environments.isEmpty()) {
            status.text = SecretsBundle.message("settings.binding.production.none")
            return
        }
        val current = ProjectSecretsSettings.parseEnvironments(productionField.text)
        val list = com.intellij.ui.CheckBoxList<String>().apply {
            environments.forEach { addItem(it.slug, "${it.name}  (${it.slug})", it.slug in current) }
        }
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(com.intellij.ui.components.JBScrollPane(list), list)
            .setTitle(SecretsBundle.message("settings.binding.production.title"))
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
                override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                    val ticked = (0 until list.itemsCount).mapNotNull { list.getItemAt(it) }.filter { list.isItemSelected(it) }
                    // Keep slugs typed by hand that aren't in this project's list
                    val others = current - environments.map { it.slug }.toSet()
                    productionField.text = (ticked + others).joinToString(", ")
                }
            })
            .createPopup()
        popup.showUnderneathOf(anchor)
    }

    private fun selectedConnection(): SecretConnection? = connectionCombo.selectedItem as? SecretConnection

    private fun selectedProjectId(): String? = (projectCombo.selectedItem as? Choice)?.value?.takeIf { it != ENTER_ID }

    private fun selectedEnvironment(): String? = (environmentCombo.selectedItem as? Choice)?.value

    private fun selectedPath(): String? =
        (folderCombo.editor.item ?: folderCombo.selectedItem)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
            ?.let(SecretScope::normalizePath)

    // ---- Loading -------------------------------------------------------------------------------------------------

    private fun connectionToUse(): SecretConnection? = selectedConnection() ?: app.connections.firstOrNull()

    private fun loadProjects(force: Boolean) {
        val connection = connectionToUse() ?: run {
            status.text = SecretsBundle.message("settings.binding.no.connection")
            return
        }
        status.text = SecretsBundle.message("settings.binding.loading")
        val provider = SecretProvider.byId(connection.providerId)
        background({
            // The project file of the selected connection's provider is read here too (off the EDT: it reads the disk)
            fileDefaults = connection.providerId to provider?.let { runCatching { SecretBindingService.getInstance(project).projectFile(it) }.getOrNull() }
            SecretsService.getInstance().projects(connection, fresh = force)
        }) { result ->
            result.onSuccess { loaded ->
                setProjects(loaded, keep = selectedProjectId() ?: storedFor(connection) { projectId })
                fillEnvironments(keep = selectedEnvironment() ?: storedFor(connection) { environment })
                status.text = SecretsBundle.message("settings.binding.projects.loaded", loaded.size, connection.name)
            }.onFailure { error ->
                setProjects(emptyList(), keep = selectedProjectId() ?: storedFor(connection) { projectId })
                fillEnvironments(keep = selectedEnvironment() ?: storedFor(connection) { environment })
                status.text = when ((error as? SecretProviderException)?.kind) {
                    SecretProviderException.Kind.FORBIDDEN -> SecretsBundle.message("settings.binding.projects.forbidden")
                    SecretProviderException.Kind.AUTH_REQUIRED -> SecretsBundle.message("settings.binding.sign.in.first", connection.name)
                    else -> error.message.orEmpty()
                }
            }
        }
    }

    /** A stored binding value, only when the stored binding is for [connection] (not another connection's project). */
    private fun storedFor(connection: SecretConnection, value: ProjectSecretsSettings.ProjectState.() -> String?): String? {
        val state = settings.state
        return if (state.connectionId == null || state.connectionId == connection.id) state.value() else null
    }

    private fun setProjects(loaded: List<ProviderProject>, keep: String?) {
        projects = loaded
        updating++
        try {
            val automatic = automatic
            projectCombo.removeAllItems()
            projectCombo.addItem(automatic)
            loaded.forEach { projectCombo.addItem(Choice(it.id, it.name)) }
            if (keep != null && loaded.none { it.id == keep }) projectCombo.addItem(Choice(keep, keep))
            projectCombo.addItem(enterId)
            projectCombo.selectedItem = (0 until projectCombo.itemCount).map(projectCombo::getItemAt).firstOrNull { it.value == keep } ?: automatic
        } finally {
            updating--
        }
    }

    private fun fillEnvironments(keep: String?) {
        val projectId = selectedProjectId() ?: effectiveProjectId()
        val environments = projects.firstOrNull { it.id == projectId }?.environments.orEmpty()
        updating++
        try {
            val automatic = automatic
            environmentCombo.removeAllItems()
            environmentCombo.addItem(automatic)
            environments.forEach { environmentCombo.addItem(Choice(it.slug, "${it.name}  (${it.slug})")) }
            if (keep != null && environments.none { it.slug == keep }) environmentCombo.addItem(Choice(keep, keep))
            environmentCombo.selectedItem = (0 until environmentCombo.itemCount).map(environmentCombo::getItemAt).firstOrNull { it.value == keep } ?: automatic
        } finally {
            updating--
        }
        loadFolders()
    }

    /** The folders of the chosen project and environment (subfolders too, a few levels deep). */
    private fun loadFolders() {
        val connection = connectionToUse() ?: return
        val scope = effectiveScope() ?: return
        val current = (folderCombo.editor.item ?: folderCombo.selectedItem)?.toString() ?: "/"
        background({
            SecretsService.getInstance().allFolders(connection, scope.projectId, scope.environment, MAX_FOLDER_DEPTH, MAX_FOLDERS)
        }) { result ->
            result.onSuccess { folders ->
                folderCombo.removeAllItems()
                folders.forEach(folderCombo::addItem)
                folderCombo.selectedItem = current
            }
        }
    }

    /** Loads the secrets of the chosen scope and reports what was found (keys counted, values not shown). */
    private fun check() {
        val connection = connectionToUse() ?: return
        val scope = effectiveScope() ?: run {
            status.text = SecretsBundle.message("settings.binding.check.no.project")
            return
        }
        status.icon = null
        status.text = SecretsBundle.message("settings.binding.loading")
        background({ SecretsService.getInstance().secrets(connection, scope, fresh = true) }) { result ->
            result.onSuccess { secrets ->
                status.icon = AllIcons.General.InspectionsOK
                status.text = SecretsBundle.message(
                    "settings.binding.check.ok", secrets.size, "${scope.environment}:${scope.path}", connection.name,
                    secrets.take(5).joinToString { it.key } + if (secrets.size > 5) ", …" else "",
                )
            }.onFailure { error ->
                status.icon = AllIcons.General.Error
                status.text = error.message.orEmpty()
            }
        }
    }

    /** What the binding would resolve to with the values currently chosen here (file defaults for "Automatic"). */
    private fun effectiveScope(): SecretScope? {
        val projectId = selectedProjectId() ?: effectiveProjectId() ?: return null
        val defaults = fileDefaults()
        return SecretScope(
            projectId = projectId,
            environment = selectedEnvironment() ?: defaults?.branchEnvironment ?: defaults?.environment
                ?: connectionToUse()?.let { c -> SecretProvider.byId(c.providerId)?.defaultEnvironment(c) } ?: SecretBindingService.DEFAULT_ENVIRONMENT,
            path = SecretScope.normalizePath(selectedPath() ?: defaults?.path),
        )
    }

    private fun effectiveProjectId(): String? = fileDefaults()?.projectId

    /** The selected connection's provider's project file, as last read off the EDT ([loadProjects]); null until then or without one. */
    @Volatile
    private var fileDefaults: Pair<String, ProjectFileDefaults?>? = null

    private fun fileDefaults(): ProjectFileDefaults? {
        val providerId = connectionToUse()?.providerId ?: return null
        return fileDefaults?.takeIf { it.first == providerId }?.second
    }

    private fun askForProjectId() {
        val id = Messages.showInputDialog(
            project, SecretsBundle.message("settings.binding.enter.id.prompt"), SecretsBundle.message("settings.binding.enter.id.title"), null,
        )?.trim()
        setProjects(projects, keep = id?.ifEmpty { null })
        fillEnvironments(keep = selectedEnvironment())
    }

    private fun <T> background(task: () -> T, then: (Result<T>) -> Unit) {
        val component: JComponent = projectCombo
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(task)
            ApplicationManager.getApplication().invokeLater({ then(result) }, ModalityState.stateForComponent(component))
        }
    }

    private companion object {
        const val ENTER_ID = "\u0000enter-id"
        const val MAX_FOLDER_DEPTH = 4
        const val MAX_FOLDERS = 200
    }
}
