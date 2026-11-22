package cz.xprees.secrets.ui.toolwindow

import cz.xprees.secrets.ui.secrets.DeleteSecretsDialog

import cz.xprees.secrets.ui.secrets.MoveSecretsDialog

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.IdeActions

import com.intellij.ide.dnd.DnDDragStartBean

import com.intellij.ide.dnd.DnDSupport

import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.ui.secrets.EditSecretDialog
import cz.xprees.secrets.ui.secrets.NewEnvironmentDialog
import cz.xprees.secrets.ui.secrets.NewSecretDialog

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
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
import cz.xprees.secrets.core.resolve.KeysIndex
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

    /** Secrets cut with Cut, waiting for Paste on a folder or environment (kept here only, never on the system clipboard). */
    private var cutSecrets: List<SecretNode> = emptyList()

    /** Secrets marked with Copy (Ctrl+C): Paste copies them to the selected folder, leaving these where they are. */
    private var copiedSecrets: List<SecretNode> = emptyList()

    private val moveAction = MoveSecretsAction()
    private val deleteAction = DeleteSecretsAction()

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
                    is MessageNode -> if (node.kind == MessageNode.Kind.SIGN_IN) node.connection?.let(::signIn) ?: SecretsConfigurable.show(project, addConnection = true)
                    else -> Unit
                }
            }
        })
        TreeSpeedSearch.installOn(tree, false) { path -> text(path.lastPathComponent) }
        tree.emptyText.apply {
            text = SecretsBundle.message("toolwindow.no.connections")
            appendSecondaryText(SecretsBundle.message("toolwindow.add.connection"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { SecretsConfigurable.show(project, addConnection = true) }
        }
        installMoving()

        val newSecret = NewSecretAction()
        val editSecret = EditSecretAction()
        val reveal = RevealAction()
        val refresh = RefreshAction()
        val preview = PreviewAction()
        val compare = CompareAction()
        val runWithEnvironment = ActionManager.getInstance().getAction("cz.xprees.secrets.RunWithEnvironment")
        // The toolbar keeps what is used all the time; everything else is in the context menu (sign-out also in the settings)
        val toolbarActions = DefaultActionGroup(
            refresh, newSecret, editSecret, deleteAction, Separator.create(), reveal, Separator.create(), preview, runWithEnvironment, compare,
            Separator.create(), ShowAllProjectsAction(), SettingsAction(),
        )
        val copyReference = CopyReferenceAction()
        val cut = CutSecretsAction()
        val copy = CopySecretsAction()
        val paste = PasteSecretsAction()
        val newEnvironment = NewEnvironmentAction()
        installShortcuts(
            IdeActions.ACTION_NEW_ELEMENT to newSecret, // Alt+Insert
            IdeActions.ACTION_EDIT_SOURCE to editSecret, // F4
            IdeActions.ACTION_RENAME to editSecret, // Shift+F6: the Edit dialog changes the key
            IdeActions.ACTION_CUT to cut,
            IdeActions.ACTION_COPY to copy,
            IdeActions.ACTION_PASTE to paste,
            IdeActions.ACTION_DELETE to deleteAction,
            "Move" to moveAction, // F6
            IdeActions.ACTION_COPY_REFERENCE to copyReference, // Ctrl+Alt+Shift+C
            IdeActions.ACTION_QUICK_JAVADOC to reveal, // Ctrl+Q / F1: show the value
            "Synchronize" to refresh, // Ctrl+Alt+Y
            "Refresh" to refresh, // F5 where the keymap has it
        )
        val copyGroup = DefaultActionGroup(
            SecretsBundle.message("action.copy.group"),
            listOf(CopyKeyAction(), CopyValueAction(), copyReference, CopyAsEnvironmentVariablesAction(), CopyEnvLineAction()),
        ).apply {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.Copy
        }
        val popupActions = DefaultActionGroup(
            newSecret, editSecret, reveal, copyGroup, Separator.create(),
            cut, copy, paste, moveAction, deleteAction, Separator.create(),
            newEnvironment, OverrideAction(), ActionManager.getInstance().getAction("cz.xprees.secrets.AddSecretsToRunConfiguration"),
            OpenDashboardAction(), Separator.create(), SignInOutAction(), refresh,
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("SecretsToolWindow", toolbarActions, true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        PopupHandler.installPopupMenu(tree, popupActions, "SecretsToolWindowPopup")
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
                // No connections: the tree's empty text says so, with a link to the settings
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
        val provider = SecretBindingService.getInstance(project).boundProvider() ?: return null
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
                    if (cz.xprees.secrets.core.provider.usesBaseUrl(value.connection)) {
                        append("  ${value.connection.baseUrl.removePrefix("https://")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
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
                    val cut = cutSecrets.any { it.connection.id == value.connection.id && it.scope == value.scope && it.entry.key == entry.key }
                    append(entry.key, if (entry.origin == SecretOrigin.IMPORTED || cut) SimpleTextAttributes.GRAY_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
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
            // The cache is cleared: completion and hover reload what they know from the server too
            KeysIndex.getInstance(project).refreshAsync()
        }
    }

    // ---- Moving and deleting secrets: Move…, Delete…, cut and paste, drag and drop -------------------------------------

    /** Secrets that can be moved or deleted from here: defined in their folder (not imported or personal), all in one folder. */
    private fun movable(nodes: List<SecretsNode>): List<SecretNode>? {
        val secrets = nodes.filterIsInstance<SecretNode>()
        if (secrets.isEmpty() || secrets.size != nodes.size) return null
        if (secrets.any { it.entry.origin != SecretOrigin.LOCAL }) return null
        val first = secrets.first()
        return secrets.takeIf { all -> all.all { it.connection.id == first.connection.id && sourceOf(it) == sourceOf(first) } }
    }

    /** The folder a secret is defined in (a recursive listing can show secrets of subfolders). */
    private fun sourceOf(node: SecretNode): SecretScope =
        SecretScope(node.scope.projectId, node.entry.environment.ifBlank { node.scope.environment }, node.entry.path)

    /** The folder a node stands for as a destination: an environment's root, a folder, or a secret's folder. */
    private fun destinationOf(node: SecretsNode?): SecretScope? = when (node) {
        is EnvironmentNode -> node.scope
        is FolderNode -> node.scope
        is SecretNode -> node.scope
        else -> null
    }?.let { SecretScope(it.projectId, it.environment, it.path) }

    private fun environmentsOf(node: SecretsNode): List<String> =
        TreeUtil.findObjectInPath(TreePath(node.path), ProjectNode::class.java)?.project?.environments?.map { it.slug }.orEmpty()

    /** Opens the confirmation for moving [secrets] to [destination] (it can still be changed there). */
    private fun confirmMove(secrets: List<SecretNode>, destination: SecretScope, copy: Boolean = false) {
        val first = secrets.first()
        val source = sourceOf(first)
        if (destination.projectId != source.projectId) return
        MoveSecretsDialog(project, first.connection, source, secrets.map { it.entry }, environmentsOf(first), destination, copy = copy) { target, _ ->
            if (!copy) cutSecrets = emptyList()
            reloadFolder(first.connection.id, source)
            reloadFolder(first.connection.id, target)
        }.show()
    }

    /** Reads a folder again if it is in the tree already (after a move or delete). */
    private fun reloadFolder(connectionId: String, scope: SecretScope) {
        var node: SecretsNode? = root.children().asSequence().filterIsInstance<ConnectionNode>().firstOrNull { it.connection.id == connectionId }
        node = node?.let { c -> childrenOf<ProjectNode>(c).firstOrNull { it.project.id == scope.projectId } }
        node = node?.let { p -> childrenOf<EnvironmentNode>(p).firstOrNull { it.environment.slug == scope.environment } }
        for (segment in scope.path.split('/').filter(String::isNotEmpty)) {
            node = node?.let { n -> childrenOf<FolderNode>(n).firstOrNull { it.name == segment } }
        }
        (node as? LazyNode)?.takeIf { it.loaded && !it.loading }?.let { load(it, force = true) }
    }

    /**
     * Gives actions the shortcuts of the IDE's standard actions (from the user's keymap, so remapped keys follow), active in
     * the tree. Several standard actions may map to one of ours; their shortcuts add up. The menu shows them too.
     */
    private fun installShortcuts(vararg mapping: Pair<String, AnAction>) {
        val manager = ActionManager.getInstance()
        mapping.groupBy({ it.second }, { it.first }).forEach { (action, ids) ->
            val shortcuts = ids.flatMap { id -> manager.getAction(id)?.shortcutSet?.shortcuts?.toList().orEmpty() }.distinct()
            if (shortcuts.isNotEmpty()) action.registerCustomShortcutSet(CustomShortcutSet(*shortcuts.toTypedArray()), tree, this)
        }
    }

    private fun installMoving() {
        // Drag secrets onto a folder or environment of the same project
        DnDSupport.createBuilder(tree)
            .setBeanProvider { info ->
                if (!SecretsService.getInstance().writesAllowed(selectedNode()?.connection)) return@setBeanProvider null
                val path = tree.getPathForLocation(info.point.x, info.point.y) ?: return@setBeanProvider null
                if (path.lastPathComponent !is SecretNode) return@setBeanProvider null
                movable(selectedNodes())?.let { DnDDragStartBean(it) }
            }
            .setTargetChecker { event ->
                val dragged = (event.attachedObject as? List<*>)?.filterIsInstance<SecretNode>().orEmpty()
                val target = tree.getPathForLocation(event.point.x, event.point.y)?.lastPathComponent as? SecretsNode
                val destination = destinationOf(target)
                val possible = dragged.isNotEmpty() && destination != null && destination.projectId == sourceOf(dragged.first()).projectId &&
                    target?.connection?.id == dragged.first().connection.id && destination != sourceOf(dragged.first()).copy(recursive = false)
                event.isDropPossible = possible
                true
            }
            .setDropHandler { event ->
                val dragged = (event.attachedObject as? List<*>)?.filterIsInstance<SecretNode>().orEmpty()
                val target = tree.getPathForLocation(event.point.x, event.point.y)?.lastPathComponent as? SecretsNode
                val destination = destinationOf(target) ?: return@setDropHandler
                if (dragged.isNotEmpty()) ApplicationManager.getApplication().invokeLater { confirmMove(dragged, destination) }
            }
            .setDisposableParent(this)
            .install()
    }

    private inner class MoveSecretsAction : PanelAction(SecretsBundle.message("action.move.secrets"), AllIcons.Actions.Forward) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = movable(selectedNodes()) != null && writable(e)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val secrets = movable(selectedNodes()) ?: return
            confirmMove(secrets, sourceOf(secrets.first()))
        }
    }

    private inner class DeleteSecretsAction : PanelAction(SecretsBundle.message("action.delete.secrets"), AllIcons.General.Remove) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = movable(selectedNodes()) != null && writable(e)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val secrets = movable(selectedNodes()) ?: return
            val first = secrets.first()
            val source = sourceOf(first)
            DeleteSecretsDialog(project, first.connection, source, secrets.map { it.entry.key }) {
                cutSecrets = cutSecrets - secrets.toSet()
                reloadFolder(first.connection.id, source)
            }.show()
        }
    }

    private inner class CutSecretsAction : PanelAction(SecretsBundle.message("action.cut.secrets"), AllIcons.Actions.MenuCut) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = movable(selectedNodes()) != null && writable(e)
        }

        override fun actionPerformed(e: AnActionEvent) {
            cutSecrets = movable(selectedNodes()) ?: return
            copiedSecrets = emptyList()
            tree.repaint()
        }
    }

    /** Marks the selected secrets for Paste to copy them (their keys also go to the clipboard, nothing secret). */
    private inner class CopySecretsAction : PanelAction(SecretsBundle.message("action.copy.secrets"), AllIcons.Actions.Copy) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = movable(selectedNodes()) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            copiedSecrets = movable(selectedNodes()) ?: return
            cutSecrets = emptyList()
            selectedKeysText()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            tree.repaint()
        }
    }

    private inner class PasteSecretsAction : PanelAction(SecretsBundle.message("action.paste.secrets"), AllIcons.Actions.MenuPaste) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (cutSecrets.isNotEmpty() || copiedSecrets.isNotEmpty()) && destinationOf(selectedNode()) != null && writable(e)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val destination = destinationOf(selectedNode()) ?: return
            when {
                cutSecrets.isNotEmpty() -> confirmMove(cutSecrets, destination)
                copiedSecrets.isNotEmpty() -> confirmMove(copiedSecrets, destination, copy = true)
            }
        }
    }

    /** False (with the reason as the description) while *Read-only* is on, or for a provider that can't write. */
    private fun writable(e: AnActionEvent): Boolean {
        val allowed = SecretsService.getInstance().writesAllowed(selectedNode()?.connection)
        if (!allowed) e.presentation.description = SecretsBundle.message("action.read.only.disabled")
        return allowed
    }

    /** Changes the value and comment of the selected secret (only one defined in that folder: not imported, not personal). */
    private inner class EditSecretAction : PanelAction(SecretsBundle.message("action.edit.secret"), AllIcons.Actions.EditSource) {
        private fun editable(node: SecretNode?): Boolean =
            node != null && !node.entry.valueHidden && node.entry.origin == SecretOrigin.LOCAL

        override fun update(e: AnActionEvent) {
            val node = selectedSecret()
            e.presentation.isEnabled = editable(node) && writable(e)
            if (node != null && !editable(node)) e.presentation.description = SecretsBundle.message("edit.secret.not.editable")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = selectedSecret()?.takeIf(::editable) ?: return
            // The secret may live in a subfolder of a recursive listing: change it where it is defined
            val scope = SecretScope(node.scope.projectId, node.entry.environment.ifBlank { node.scope.environment }, node.entry.path)
            EditSecretDialog(project, node.connection, scope, node.entry) { outcome, key ->
                if (outcome == WriteOutcome.APPLIED) refreshAndSelect(SecretLocation(node.connection.id, scope.projectId, node.scope.environment, node.scope.path, key))
            }.show()
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
            e.presentation.isEnabled = context() != null && writable(e)
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
                if (outcome == WriteOutcome.APPLIED) refreshAndSelect(location)
            }.show()
        }
    }

    /** Creates an environment in the selected project. */
    private inner class NewEnvironmentAction : PanelAction(SecretsBundle.message("action.new.environment"), AllIcons.Nodes.ConfigFolder) {
        private fun projectNode(): ProjectNode? = TreeUtil.findObjectInPath(tree.selectionPath, ProjectNode::class.java)

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = projectNode() != null
            e.presentation.isEnabled = projectNode() != null && writable(e)
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

    /** The keys of the selected secrets, one per line (names only, nothing secret), or null when no secret is selected. */
    fun selectedKeysText(): String? =
        selectedNodes().filterIsInstance<SecretNode>().map { it.entry.key }.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")

    private inner class CopyKeyAction : PanelAction(SecretsBundle.message("action.copy.key"), AllIcons.Actions.Copy) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNodes().any { it is SecretNode }
        }

        // A key name isn't secret: the ordinary clipboard, without the auto-clear values get
        override fun actionPerformed(e: AnActionEvent) {
            selectedKeysText()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
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
        // Leave out what the project binding already says, as far as the provider's grammar allows
        val defaults = binding?.projectId?.let { SecretReference.Defaults(it, binding.environment) }
        return provider.referenceGrammar.build(provider.referenceScheme, scope, key, defaults)
            .copy(connectionId = if (binding != null) null else connection.name)
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
            // Only on a connection itself (the toolbar doesn't have it; Settings can sign out too)
            val connectionNode = tree.selectionPath?.lastPathComponent as? ConnectionNode
            e.presentation.isEnabledAndVisible = connectionNode != null
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
