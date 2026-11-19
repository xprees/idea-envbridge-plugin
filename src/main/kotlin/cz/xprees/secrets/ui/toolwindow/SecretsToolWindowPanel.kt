package cz.xprees.secrets.ui.toolwindow

import cz.xprees.secrets.core.provider.CreateOutcome
import cz.xprees.secrets.ui.secrets.NewEnvironmentDialog
import cz.xprees.secrets.ui.secrets.NewSecretDialog

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.ui.SecretClipboard
import cz.xprees.secrets.ui.auth.SignInHelper
import cz.xprees.secrets.ui.settings.SecretsConfigurable
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The Secrets tool window: connections › projects › environments › folders › secrets. Keys and comments are shown, values
 * stay masked until revealed (and are hidden again after a few seconds). Copied values are removed from the clipboard after
 * a while if they are still there.
 */
class SecretsToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val root = RootNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? LazyNode)?.let(::load)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                when (val node = selectedNode()) {
                    is SecretNode -> toggleReveal(node)
                    is MessageNode -> if (node.kind == MessageNode.Kind.SIGN_IN) node.connection?.let(::signIn)
                    else -> Unit
                }
            }
        })
        TreeSpeedSearch.installOn(tree, false) { path -> text(path.lastPathComponent) }

        val actions = DefaultActionGroup(
            RefreshAction(), NewSecretAction(), NewEnvironmentAction(), Separator.create(), RevealAction(), CopyValueAction(), CopyReferenceAction(), CopyAsEnvironmentVariablesAction(),
            CopyEnvLineAction(), ActionManager.getInstance().getAction("cz.xprees.secrets.AddSecretsToRunConfiguration"),
            OpenDashboardAction(), OverrideAction(), Separator.create(), PreviewAction(),
            ActionManager.getInstance().getAction("cz.xprees.secrets.RunWithEnvironment"), CompareAction(),
            Separator.create(), ShowAllProjectsAction(), SignInOutAction(), SettingsAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("SecretsToolWindow", actions, true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        PopupHandler.installPopupMenu(tree, actions, "SecretsToolWindowPopup")
        setContent(ScrollPaneFactory.createScrollPane(tree))

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SecretsAppSettings.TOPIC, SecretsAppSettings.Listener { rebuild() })
        project.messageBus.connect(this).subscribe(ProjectSecretsSettings.BINDING_TOPIC, ProjectSecretsSettings.BindingListener { rebuild() })
        rebuild()
    }

    override fun dispose() = Unit

    /** Rebuilds the tree from the configured connections (values are re-read from the cache or the instance on expand). */
    fun rebuild() {
        rebuilding = true
        ApplicationManager.getApplication().executeOnPooledThread {
            // With a bound project (and "only use the bound project"), only its connection is listed
            val restricted = restrictedBinding()
            val connections = SecretsAppSettings.getInstance().connections.filter { restricted == null || it.id == restricted.connection.id }
            val nodes = connections.map { connection ->
                val client = runCatching { SecretsService.getInstance().client(connection) }.getOrNull()
                ConnectionNode(connection, client?.hasCredentials() == true, null)
            }
            ApplicationManager.getApplication().invokeLater({
                root.removeAllChildren()
                if (nodes.isEmpty()) root.add(MessageNode(null, SecretsBundle.message("toolwindow.no.connections"), MessageNode.Kind.SIGN_IN))
                nodes.forEach(root::add)
                model.reload()
                // Open the connection of the project binding right away
                nodes.firstOrNull()?.let { tree.expandPath(TreePath(arrayOf(root, it))) }
                rebuilding = false
                pendingSelection?.let { pendingSelection = null; select(it) }
            }, ModalityState.any(), project.disposed)
        }
    }

    private fun load(node: LazyNode, force: Boolean = false) {
        if (node.loading || (node.loaded && !force)) return
        node.loading = true
        node.removeAllChildren()
        node.add(MessageNode(node.connection, SecretsBundle.message("toolwindow.loading"), MessageNode.Kind.LOADING))
        model.nodeStructureChanged(node)
        ApplicationManager.getApplication().executeOnPooledThread {
            val children = try {
                loadChildren(node, force)
            } catch (e: SecretProviderException) {
                listOf(errorNode(node, e))
            } catch (e: Exception) {
                listOf(MessageNode(node.connection, e.message ?: e.toString(), MessageNode.Kind.ERROR))
            }
            ApplicationManager.getApplication().invokeLater({
                node.removeAllChildren()
                children.ifEmpty { listOf(MessageNode(node.connection, SecretsBundle.message("toolwindow.empty"))) }.forEach(node::add)
                node.loaded = true
                node.loading = false
                model.nodeStructureChanged(node)
                afterLoad.remove(node)?.invoke()
            }, ModalityState.any(), project.disposed)
        }
    }

    // ---- Selecting a secret from elsewhere (editor hints, gutter, after creating one) ------------------------------------

    private var rebuilding = false
    private var pendingSelection: SecretLocation? = null
    private val afterLoad = HashMap<LazyNode, () -> Unit>()

    /**
     * Expands the tree down to [location] (loading each level as needed) and selects the secret, or the deepest node that
     * exists on the way when the secret isn't there. On the EDT.
     */
    fun select(location: SecretLocation) {
        if (rebuilding) {
            pendingSelection = location
            return
        }
        val connection = root.children().asSequence().filterIsInstance<ConnectionNode>().firstOrNull { it.connection.id == location.connectionId }
            ?: return
        whenLoaded(connection) {
            val projectNode = childrenOf<ProjectNode>(connection).firstOrNull { it.project.id == location.projectId }
                ?: return@whenLoaded selectNode(connection)
            whenLoaded(projectNode) {
                val environment = childrenOf<EnvironmentNode>(projectNode).firstOrNull { it.environment.slug == location.environment }
                    ?: return@whenLoaded selectNode(projectNode)
                descend(environment, location.path.split('/').filter(String::isNotEmpty), location.key)
            }
        }
    }

    private fun descend(node: LazyNode, segments: List<String>, key: String?): Unit = whenLoaded(node) {
        if (segments.isEmpty()) {
            selectNode(key?.let { k -> childrenOf<SecretNode>(node).firstOrNull { it.entry.key == k } } ?: node)
            return@whenLoaded
        }
        val folder = childrenOf<FolderNode>(node).firstOrNull { it.name == segments.first() } ?: return@whenLoaded selectNode(node)
        descend(folder, segments.drop(1), key)
    }

    private fun whenLoaded(node: LazyNode, then: () -> Unit) {
        val path = TreePath(node.path)
        if (node.loaded && !node.loading) {
            tree.expandPath(path)
            then()
            return
        }
        afterLoad[node] = then
        load(node)
        tree.expandPath(path)
    }

    /** Selects [location] after a change there (e.g. a created secret): the folder is read again first. */
    fun refreshAndSelect(location: SecretLocation) {
        // Walk the loaded part of the tree along the location and mark its folder for reloading
        var node: SecretsNode? = root.children().asSequence().filterIsInstance<ConnectionNode>().firstOrNull { it.connection.id == location.connectionId }
        node = node?.let { c -> childrenOf<ProjectNode>(c).firstOrNull { it.project.id == location.projectId } }
        node = node?.let { p -> childrenOf<EnvironmentNode>(p).firstOrNull { it.environment.slug == location.environment } }
        for (segment in location.path.split('/').filter(String::isNotEmpty)) {
            node = node?.let { n -> childrenOf<FolderNode>(n).firstOrNull { it.name == segment } }
        }
        (node as? LazyNode)?.takeIf { !it.loading }?.loaded = false
        select(location)
    }

    private inline fun <reified T : SecretsNode> childrenOf(node: SecretsNode): List<T> = node.children().asSequence().filterIsInstance<T>().toList()

    private fun selectNode(node: SecretsNode) {
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        tree.requestFocusInWindow()
    }

    private fun errorNode(node: LazyNode, e: SecretProviderException): MessageNode =
        if (e.kind == SecretProviderException.Kind.AUTH_REQUIRED) {
            MessageNode(node.connection, SecretsBundle.message("toolwindow.sign.in"), MessageNode.Kind.SIGN_IN)
        } else {
            MessageNode(node.connection, e.message ?: e.kind.name, MessageNode.Kind.ERROR)
        }

    /** Off the EDT. */
    private fun loadChildren(node: LazyNode, fresh: Boolean): List<SecretsNode> {
        val service = SecretsService.getInstance()
        return when (node) {
            is ConnectionNode -> {
                if (!service.client(node.connection).hasCredentials()) {
                    return listOf(MessageNode(node.connection, SecretsBundle.message("toolwindow.sign.in"), MessageNode.Kind.SIGN_IN))
                }
                val binding = binding(node)
                val projects = try {
                    service.projects(node.connection, fresh)
                } catch (e: SecretProviderException) {
                    // Least-privilege tokens (OAuth secrets:read) may not list projects: show the bound one
                    val boundId = binding?.projectId
                    if (e.kind != SecretProviderException.Kind.FORBIDDEN || boundId == null) throw e
                    listOf(ProviderProject(boundId, SecretsBundle.message("toolwindow.bound.project"), boundId, listOf(ProviderEnvironment(binding.environment, binding.environment))))
                }
                val restrictedTo = restrictedBinding()?.takeIf { it.connection.id == node.connection.id }?.projectId
                if (restrictedTo != null) {
                    val bound = projects.firstOrNull { it.id == restrictedTo }
                        ?: ProviderProject(restrictedTo, SecretsBundle.message("toolwindow.bound.project"), restrictedTo, listOfNotNull(binding?.environment?.let { ProviderEnvironment(it, it) }))
                    return listOf(ProjectNode(node.connection, bound, true))
                }
                projects.map { ProjectNode(node.connection, it, it.id == binding?.projectId) }
                    .sortedByDescending { it.bound }
            }

            is ProjectNode -> {
                val binding = binding(node)
                node.project.environments.map {
                    EnvironmentNode(node.connection, node.project, it, node.bound && it.slug == binding?.environment)
                }
            }

            is EnvironmentNode -> folderChildren(node.connection, node.scope, fresh)
            is FolderNode -> folderChildren(node.connection, node.scope, fresh)
            else -> emptyList()
        }
    }

    private fun folderChildren(connection: cz.xprees.secrets.core.model.SecretConnection, scope: SecretScope, fresh: Boolean): List<SecretsNode> {
        val service = SecretsService.getInstance()
        val folders = service.folders(connection, scope).map { FolderNode(connection, SecretScope(scope.projectId, scope.environment, it.path), it.name) }
        val secrets = service.secrets(connection, scope, fresh).sortedBy { it.key.lowercase() }.map { SecretNode(connection, scope, it) }
        return folders + secrets
    }

    /** Whether to show connections and projects other than the bound one (a deliberate, temporary choice). */
    @Volatile
    private var showAll = false

    /** The binding the tool window is limited to, or null when everything may be shown. Off the EDT. */
    private fun restrictedBinding(): SecretBindingService.Binding? {
        if (showAll || !ProjectSecretsSettings.getInstance(project).state.restrictToBoundProject) return null
        val provider = SecretProvider.all().firstOrNull() ?: return null
        return SecretBindingService.getInstance(project).bindingOrNull(provider)?.takeIf { it.projectId != null }
    }

    private fun binding(node: SecretsNode): SecretBindingService.Binding? {
        val connection = node.connection ?: return null
        val provider = SecretProvider.byId(connection.providerId) ?: return null
        return SecretBindingService.getInstance(project).bindingOrNull(provider)?.takeIf { it.connection.id == connection.id }
    }

    private fun selectedNode(): SecretsNode? = tree.selectionPath?.lastPathComponent as? SecretsNode

    private fun selectedSecret(): SecretNode? = selectedNode() as? SecretNode

    private fun toggleReveal(node: SecretNode) {
        node.revealed = !node.revealed
        model.nodeChanged(node)
        if (node.revealed) {
            val seconds = SecretsAppSettings.getInstance().state.revealSeconds.toLong().coerceAtLeast(1)
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater({
                    node.revealed = false
                    model.nodeChanged(node)
                }, ModalityState.any(), project.disposed)
            }, seconds, TimeUnit.SECONDS)
        }
    }

    private fun signIn(connection: cz.xprees.secrets.core.model.SecretConnection) = SignInHelper.signIn(project, connection)

    private fun text(node: Any?): String = when (node) {
        is ConnectionNode -> node.connection.name
        is ProjectNode -> node.project.name
        is EnvironmentNode -> node.environment.name
        is FolderNode -> node.name
        is SecretNode -> node.entry.key
        is MessageNode -> node.text
        else -> ""
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (value) {
                is ConnectionNode -> {
                    icon = SecretsIcons.PROVIDER
                    append(value.connection.name)
                    append("  ${value.connection.baseUrl.removePrefix("https://")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (!value.signedIn) append("  " + SecretsBundle.message("toolwindow.signed.out"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }

                is ProjectNode -> {
                    icon = AllIcons.Nodes.Module
                    append(value.project.name, if (value.bound) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (value.bound) append("  " + SecretsBundle.message("toolwindow.bound"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is EnvironmentNode -> {
                    icon = AllIcons.Nodes.ConfigFolder
                    append(value.environment.name, if (value.bound) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${value.environment.slug}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is FolderNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(value.name)
                }

                is SecretNode -> {
                    val entry = value.entry
                    icon = AllIcons.Nodes.Property
                    append(entry.key, if (entry.origin == SecretOrigin.IMPORTED) SimpleTextAttributes.GRAY_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ")
                    when {
                        entry.valueHidden -> append(SecretsBundle.message("toolwindow.value.hidden"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        value.revealed -> append(entry.value.reveal().replace("\n", "⏎"), SimpleTextAttributes.SYNTHETIC_ATTRIBUTES)
                        else -> append(SecretValue.MASK, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    when (entry.origin) {
                        SecretOrigin.IMPORTED -> append("  " + SecretsBundle.message("toolwindow.imported", "${entry.environment}:${entry.path}"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        SecretOrigin.PERSONAL -> append("  " + SecretsBundle.message("toolwindow.personal"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        SecretOrigin.LOCAL -> if (entry.path != value.scope.path) append("  ${entry.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    if (entry.comment.isNotBlank()) append("  — ${entry.comment.lines().first()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }

                is MessageNode -> {
                    icon = when (value.kind) {
                        MessageNode.Kind.LOADING -> AllIcons.Process.Step_1
                        MessageNode.Kind.ERROR -> AllIcons.General.Error
                        MessageNode.Kind.SIGN_IN -> AllIcons.General.User
                        MessageNode.Kind.INFO -> AllIcons.General.Information
                    }
                    append(value.text, if (value.kind == MessageNode.Kind.SIGN_IN) SimpleTextAttributes.LINK_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }

    // ---- Actions -------------------------------------------------------------------------------------------------------

    private abstract inner class PanelAction(text: String, icon: javax.swing.Icon?) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class RefreshAction : PanelAction(SecretsBundle.message("action.refresh"), AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            val node = selectedNode()
            val lazy = node as? LazyNode ?: (node?.parent as? LazyNode)
            if (lazy == null) {
                SecretsService.getInstance().invalidate()
                rebuild()
            } else {
                lazy.connection?.let { SecretsService.getInstance().invalidate(it.id) }
                load(lazy, force = true)
                tree.expandPath(TreePath(lazy.path))
            }
        }
    }

    /** Creates a secret in the selected environment or folder (or the bound one). */
    private inner class NewSecretAction : PanelAction(SecretsBundle.message("action.new.secret"), AllIcons.General.Add) {
        private fun context(): Pair<ProjectNode, SecretScope?>? {
            val selection = tree.selectionPath
            val projectNode = TreeUtil.findObjectInPath(selection, ProjectNode::class.java)
                ?: root.children().asSequence().filterIsInstance<ConnectionNode>()
                    .flatMap { childrenOf<ProjectNode>(it).asSequence() }.firstOrNull { it.bound }
                ?: return null
            val scope = when (val node = selectedNode()) {
                is SecretNode -> node.scope
                is FolderNode -> node.scope
                is EnvironmentNode -> node.scope
                else -> null
            }?.takeIf { it.projectId == projectNode.project.id }
            return projectNode to scope
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = context() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val (projectNode, scope) = context() ?: return
            val binding = binding(projectNode)?.takeIf { it.projectId == projectNode.project.id }
            val environments = projectNode.project.environments.map { it.slug }
            NewSecretDialog(
                project, projectNode.connection, projectNode.project.id, environments,
                environment = scope?.environment ?: binding?.environment ?: environments.firstOrNull() ?: return,
                path = scope?.path ?: binding?.path ?: "/",
            ) { location, outcome ->
                if (outcome == CreateOutcome.CREATED) refreshAndSelect(location)
            }.show()
        }
    }

    /** Creates an environment in the selected project. */
    private inner class NewEnvironmentAction : PanelAction(SecretsBundle.message("action.new.environment"), AllIcons.Nodes.ConfigFolder) {
        private fun projectNode(): ProjectNode? = TreeUtil.findObjectInPath(tree.selectionPath, ProjectNode::class.java)

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = projectNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = projectNode() ?: return
            NewEnvironmentDialog(project, node.connection, node.project.id, node.project.name, node.project.environments.map { it.slug }) { created ->
                // Projects carry their environments: read the connection's projects again, then select the new one
                TreeUtil.findObjectInPath(TreePath(node.path), ConnectionNode::class.java)?.takeIf { !it.loading }?.loaded = false
                select(SecretLocation(node.connection.id, node.project.id, created.slug, "/", null))
            }.show()
        }
    }

    private inner class RevealAction : PanelAction(SecretsBundle.message("action.reveal"), AllIcons.Actions.Show) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedSecret()?.entry?.valueHidden == false
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedSecret()?.let(::toggleReveal)
        }
    }

    private inner class CopyValueAction : PanelAction(SecretsBundle.message("action.copy.value"), AllIcons.Actions.Copy) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedSecret()?.entry?.valueHidden == false
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedSecret()?.let { SecretClipboard.copy(it.entry.value.reveal()) }
        }
    }

    private inner class CopyEnvLineAction : PanelAction(SecretsBundle.message("action.copy.env.line"), null) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedSecret()?.entry?.valueHidden == false
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedSecret()?.let { SecretClipboard.copy("${it.entry.key}=${it.entry.value.reveal()}") }
        }
    }

    /** A reference to [node] (a secret, or a folder / environment as a scope import), as short as the project binding allows. */
    private fun referenceOf(node: SecretsNode): SecretReference? {
        val connection = node.connection ?: return null
        val provider = SecretProvider.byId(connection.providerId) ?: return null
        val binding = binding(node)
        val (scope, key) = when (node) {
            is SecretNode -> node.scope to node.entry.key
            is FolderNode -> node.scope to null
            is EnvironmentNode -> node.scope to null
            else -> return null
        }
        // Leave out what the project binding already says
        val sameProject = binding?.projectId == scope.projectId
        val sameEnv = sameProject && binding?.environment == scope.environment
        val samePath = sameEnv && binding?.path == scope.path
        return SecretReference(
            scheme = provider.referenceScheme,
            key = key,
            environment = if (samePath) null else scope.environment,
            path = if (samePath) null else scope.path,
            projectId = if (sameProject) null else scope.projectId,
            connectionId = if (binding != null) null else connection.name,
        )
    }

    private fun selectedNodes(): List<SecretsNode> = tree.selectionPaths.orEmpty().mapNotNull { it.lastPathComponent as? SecretsNode }

    private inner class CopyReferenceAction : PanelAction(SecretsBundle.message("action.copy.reference"), AllIcons.Actions.InlayGlobe) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode()?.let(::referenceOf) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val reference = selectedNode()?.let(::referenceOf) ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(reference.format()))
        }
    }

    /**
     * `NAME=infisical://…` lines for the selected secrets (a folder becomes a whole-folder import), ready for the Paste button
     * of a run configuration's Environment Variables dialog. References only, never values.
     */
    private inner class CopyAsEnvironmentVariablesAction : PanelAction(SecretsBundle.message("action.copy.env.references"), AllIcons.Actions.MenuPaste) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNodes().any { referenceOf(it) != null }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val lines = selectedNodes().mapNotNull { node ->
                val reference = referenceOf(node) ?: return@mapNotNull null
                val name = reference.key ?: cz.xprees.secrets.ui.actions.AddSecretsToRunConfigurationAction.SCOPE_VARIABLE
                "$name=${reference.format()}"
            }.distinct()
            if (lines.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(lines.joinToString("\n")))
        }
    }

    private inner class OpenDashboardAction : PanelAction(SecretsBundle.message("action.open.dashboard"), AllIcons.General.Web) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode()?.connection != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = selectedNode() ?: return
            val connection = node.connection ?: return
            val scope = when (node) {
                is SecretNode -> node.scope
                is FolderNode -> node.scope
                is EnvironmentNode -> node.scope
                is ProjectNode -> node.project.environments.firstOrNull()?.let { SecretScope(node.project.id, it.slug) }
                else -> null
            }
            // The project node above the selection knows the organization the dashboard URL needs
            val organizationId = TreeUtil.findObjectInPath(tree.selectionPath, ProjectNode::class.java)?.project?.organizationId
            val key = (node as? SecretNode)?.entry?.key
            SecretProvider.byId(connection.providerId)?.dashboardUrl(connection, scope, organizationId, key)?.let(BrowserUtil::browse)
        }
    }

    private inner class SignInOutAction : PanelAction(SecretsBundle.message("action.sign.in"), AllIcons.General.User) {
        override fun update(e: AnActionEvent) {
            val connectionNode = TreeUtil.findObjectInPath(tree.selectionPath, ConnectionNode::class.java)
                ?: (root.childCount == 1).takeIf { it }?.let { root.firstChild as? ConnectionNode }
            e.presentation.isEnabled = connectionNode != null
            e.presentation.text = SecretsBundle.message(if (connectionNode?.signedIn == true) "action.sign.out" else "action.sign.in")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = TreeUtil.findObjectInPath(tree.selectionPath, ConnectionNode::class.java)
                ?: root.firstChild as? ConnectionNode ?: return
            if (node.signedIn) SignInHelper.signOut(project, node.connection) else signIn(node.connection)
        }
    }

    private inner class PreviewAction : PanelAction(SecretsBundle.message("action.preview"), AllIcons.Actions.Preview) {
        override fun actionPerformed(e: AnActionEvent) = RunConfigurationPreviewDialog(project).show()
    }

    private inner class OverrideAction : PanelAction(SecretsBundle.message("editor.action.override"), AllIcons.Actions.Edit) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedSecret() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedSecret()?.let { cz.xprees.secrets.editor.SecretValueActions.editOverride(project, it.entry.key) }
        }
    }

    private inner class CompareAction : PanelAction(SecretsBundle.message("action.compare"), AllIcons.Actions.Diff) {
        override fun actionPerformed(e: AnActionEvent) = CompareEnvironmentsDialog(project).show()
    }

    private inner class ShowAllProjectsAction : ToggleAction(
        SecretsBundle.message("toolwindow.show.all"), SecretsBundle.message("toolwindow.show.all.description"), AllIcons.Actions.Show,
    ), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = showAll

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showAll = state
            rebuild()
        }
    }

    private inner class SettingsAction : PanelAction(SecretsBundle.message("action.settings"), AllIcons.General.Settings) {
        override fun actionPerformed(e: AnActionEvent) = SecretsConfigurable.show(project)
    }


}
