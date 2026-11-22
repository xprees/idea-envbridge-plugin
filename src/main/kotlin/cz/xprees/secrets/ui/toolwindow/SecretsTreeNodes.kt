package cz.xprees.secrets.ui.toolwindow

import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretsClient
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

class FolderNode(
    override val connection: SecretConnection,
    val scope: SecretScope,
    val name: String,
    /** What the listing said about the folder (a 1Password item's tags), or null. */
    val metadata: SecretMetadata? = null,
) : LazyNode() {
    /** Details read on demand when the user hovers over it ([SecretsClient.secretDetails]). */
    val details = OnDemandDetails()
}

class SecretNode(override val connection: SecretConnection, val scope: SecretScope, val entry: SecretEntry) : SecretsNode() {
    @Volatile
    var revealed = false

    /** Details read on demand when the user hovers over it ([SecretsClient.secretDetails]). */
    val details = OnDemandDetails()

    override fun isLeaf(): Boolean = true
}

/** Details of a node read once, in the background, the first time its tooltip is asked for; then kept with the node. */
class OnDemandDetails {
    enum class State { NOT_READ, READING, READ, FAILED }

    @Volatile
    var state = State.NOT_READ

    /** What was read (null: nothing more to say). */
    @Volatile
    var metadata: SecretMetadata? = null

    /** Why reading failed (a provider's message, no values), when [state] is FAILED. */
    @Volatile
    var error: String? = null
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
