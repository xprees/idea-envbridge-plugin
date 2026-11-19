package cz.xprees.secrets.ui.toolwindow

import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretScope
import javax.swing.tree.DefaultMutableTreeNode

/** Tree nodes of the Secrets tool window. Children of [LazyNode]s are loaded in the background when the node is expanded. */
sealed class SecretsNode : DefaultMutableTreeNode() {
    abstract val connection: SecretConnection?
}

abstract class LazyNode : SecretsNode() {
    @Volatile
    var loaded = false

    @Volatile
    var loading = false

    override fun isLeaf(): Boolean = false
}

class RootNode : SecretsNode() {
    override val connection: SecretConnection? get() = null
}

class ConnectionNode(override val connection: SecretConnection, var signedIn: Boolean, var account: String?) : LazyNode()

class ProjectNode(override val connection: SecretConnection, val project: ProviderProject, val bound: Boolean) : LazyNode()

class EnvironmentNode(
    override val connection: SecretConnection,
    val project: ProviderProject,
    val environment: ProviderEnvironment,
    val bound: Boolean,
) : LazyNode() {
    val scope: SecretScope get() = SecretScope(project.id, environment.slug, "/")
}

class FolderNode(override val connection: SecretConnection, val scope: SecretScope, val name: String) : LazyNode()

class SecretNode(override val connection: SecretConnection, val scope: SecretScope, val entry: SecretEntry) : SecretsNode() {
    @Volatile
    var revealed = false

    override fun isLeaf(): Boolean = true
}

/** A message in place of children: loading, an error, "sign in", "no secrets". */
class MessageNode(
    override val connection: SecretConnection?,
    val text: String,
    val kind: Kind = Kind.INFO,
) : SecretsNode() {
    enum class Kind { INFO, LOADING, ERROR, SIGN_IN }

    override fun isLeaf(): Boolean = true
}
