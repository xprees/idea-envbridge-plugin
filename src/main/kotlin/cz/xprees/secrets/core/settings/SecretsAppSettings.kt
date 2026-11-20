package cz.xprees.secrets.core.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import cz.xprees.secrets.core.model.SecretConnection

/**
 * Application-level settings: the configured connections and general preferences. Holds **no credentials** (those are in
 * `CredentialVault`), only names, instance URLs, auth method IDs and their non-secret options.
 */
@Service(Service.Level.APP)
// Storage names predate the rename to EnvBridge; kept so existing settings are read
@State(name = "SecretsForInfisical", storages = [Storage("secrets-for-infisical.xml")])
class SecretsAppSettings : SimplePersistentStateComponent<SecretsAppSettings.AppState>(AppState()) {

    class AppState : BaseState() {
        var connections by list<ConnectionState>()

        /** How long browsing and editor features may reuse fetched values (launches always fetch fresh). */
        var cacheTtlSeconds by property(DEFAULT_CACHE_TTL_SECONDS)

        /** How long a revealed value stays visible in the tool window. */
        var revealSeconds by property(DEFAULT_REVEAL_SECONDS)

        /** After how long a copied value is removed from the clipboard (if it is still there). 0 = never. */
        var clipboardClearSeconds by property(DEFAULT_CLIPBOARD_CLEAR_SECONDS)

        /** Environments that need a confirmation before a launch: comma-separated globs on the slug. Empty = off. */
        var prodGuardPattern by string(DEFAULT_PROD_GUARD_PATTERN)

        /** Whether secret values are masked in Run and Debug consoles. */
        var maskConsoleOutput by property(true)

        /** Never write to secret managers from this IDE: creating and changing secrets and environments is turned off. */
        var readOnly by property(false)
    }

    class ConnectionState : BaseState() {
        var id by string()
        var providerId by string()
        var name by string()
        var baseUrl by string()
        var authMethodId by string()
        var options by map<String, String>()
    }

    val connections: List<SecretConnection>
        get() = state.connections.mapNotNull { it.toConnection() }

    fun connection(id: String?): SecretConnection? = id?.let { wanted -> connections.firstOrNull { it.id == wanted } }

    /** A connection by ID or, failing that, by name (references may use either). */
    fun connectionByIdOrName(idOrName: String): SecretConnection? =
        connection(idOrName) ?: connections.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }

    fun connectionsOf(providerId: String): List<SecretConnection> = connections.filter { it.providerId == providerId }

    fun setConnections(connections: List<SecretConnection>) {
        // Assigning a BaseState property counts as a modification, so the settings get saved
        state.connections = connections.map { it.toState() }.toMutableList()
        fireChanged()
    }

    fun addOrUpdate(connection: SecretConnection) {
        val list = connections.toMutableList()
        val index = list.indexOfFirst { it.id == connection.id }
        if (index >= 0) list[index] = connection else list += connection
        setConnections(list)
    }

    fun remove(connectionId: String) = setConnections(connections.filterNot { it.id == connectionId })

    /** Tells listeners (tool window, settings) that connections or sign-in states changed. */
    fun fireChanged() {
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).connectionsChanged()
    }

    private fun ConnectionState.toConnection(): SecretConnection? {
        return SecretConnection(
            id = id ?: return null,
            providerId = providerId ?: return null,
            name = name ?: return null,
            baseUrl = baseUrl ?: return null,
            authMethodId = authMethodId ?: return null,
            options = options.toMap(),
        )
    }

    private fun SecretConnection.toState() = ConnectionState().also {
        it.id = id
        it.providerId = providerId
        it.name = name
        it.baseUrl = baseUrl
        it.authMethodId = authMethodId
        it.options = options.toMutableMap()
    }

    fun interface Listener {
        fun connectionsChanged()
    }

    companion object {
        const val DEFAULT_CACHE_TTL_SECONDS = 300
        const val DEFAULT_REVEAL_SECONDS = 10
        const val DEFAULT_CLIPBOARD_CLEAR_SECONDS = 30
        const val DEFAULT_PROD_GUARD_PATTERN = "prod*, production*, live*"

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("EnvBridge connections", Listener::class.java)

        fun getInstance(): SecretsAppSettings = service()
    }
}
