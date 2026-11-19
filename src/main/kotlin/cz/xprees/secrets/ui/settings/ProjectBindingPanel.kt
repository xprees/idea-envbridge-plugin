package cz.xprees.secrets.ui.settings

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

    private val automatic = Choice(null, SecretsBundle.message("settings.binding.auto.short"))
    private val enterId = Choice(ENTER_ID, SecretsBundle.message("settings.binding.enter.id"))

    private val connectionCombo = ComboBox<SecretConnection?>().apply {
        renderer = textListCellRenderer { c: SecretConnection? -> c?.name ?: SecretsBundle.message("settings.binding.auto.short") }
    }
    private val projectCombo = ComboBox<Choice>().apply { renderer = textListCellRenderer { c: Choice? -> c?.label.orEmpty() } }
    private val environmentCombo = ComboBox<Choice>().apply { renderer = textListCellRenderer { c: Choice? -> c?.label.orEmpty() } }
    private val folderCombo = ComboBox<String>().apply { isEditable = true }
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
            button(SecretsBundle.message("settings.binding.check")) { check() }
            button(SecretsBundle.message("settings.binding.reload")) { loadProjects(force = true) }
        }
        row { cell(status).align(AlignX.FILL).resizableColumn() }
        row {
            checkBox(SecretsBundle.message("settings.binding.project.file.short"))
                .bindSelected({ settings.state.useProjectFile }, { value -> settings.update { useProjectFile = value } })
                .comment(SecretsBundle.message("settings.binding.project.file.comment"))
        }
        row { comment(SecretsBundle.message("settings.binding.comment")) }

        connectionCombo.addActionListener { if (updating == 0) loadProjects(force = false) }
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
        } finally {
            updating--
        }
        status.text = ""
        loadProjects(force = false)
    }

    fun isModified(): Boolean {
        val state = settings.state
        return selectedConnection()?.id != state.connectionId || selectedProjectId() != state.projectId ||
            selectedEnvironment() != state.environment || selectedPath() != state.path
    }

    fun apply() {
        settings.update {
            connectionId = selectedConnection()?.id
            projectId = selectedProjectId()
            environment = selectedEnvironment()
            path = selectedPath()
        }
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
        background({ SecretsService.getInstance().projects(connection, fresh = force) }) { result ->
            result.onSuccess { loaded ->
                setProjects(loaded, keep = selectedProjectId() ?: settings.state.projectId)
                fillEnvironments(keep = selectedEnvironment() ?: settings.state.environment)
                status.text = SecretsBundle.message("settings.binding.projects.loaded", loaded.size, connection.name)
            }.onFailure { error ->
                setProjects(emptyList(), keep = selectedProjectId() ?: settings.state.projectId)
                status.text = when ((error as? SecretProviderException)?.kind) {
                    SecretProviderException.Kind.FORBIDDEN -> SecretsBundle.message("settings.binding.projects.forbidden")
                    SecretProviderException.Kind.AUTH_REQUIRED -> SecretsBundle.message("settings.binding.sign.in.first", connection.name)
                    else -> error.message.orEmpty()
                }
            }
        }
    }

    private fun setProjects(loaded: List<ProviderProject>, keep: String?) {
        projects = loaded
        updating++
        try {
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
            val service = SecretsService.getInstance()
            val found = mutableListOf("/")
            var level = listOf("/")
            repeat(MAX_FOLDER_DEPTH) {
                level = level.flatMap { parent -> service.folders(connection, SecretScope(scope.projectId, scope.environment, parent)).map { it.path } }
                found += level
                if (level.isEmpty() || found.size > MAX_FOLDERS) return@repeat
            }
            found.take(MAX_FOLDERS)
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
        val defaults = fileBinding()
        return SecretScope(
            projectId = projectId,
            environment = selectedEnvironment() ?: defaults?.environment ?: SecretBindingService.DEFAULT_ENVIRONMENT,
            path = SecretScope.normalizePath(selectedPath() ?: defaults?.path),
        )
    }

    private fun effectiveProjectId(): String? = fileBinding()?.projectId

    private fun fileBinding(): SecretBindingService.Binding? =
        SecretProvider.all().firstOrNull()?.let { SecretBindingService.getInstance(project).bindingOrNull(it) }

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
