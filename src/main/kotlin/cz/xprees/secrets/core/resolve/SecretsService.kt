package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.model.ProviderEnvironment

import cz.xprees.secrets.core.model.SecretValue

import cz.xprees.secrets.core.provider.CreateOutcome

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for reading secrets, with the memory-only cache used by browsing and editor features. Launches pass
 * `fresh = true` and always get current values. Nothing is ever written to disk. All calls block: use them off the EDT.
 */
@Service(Service.Level.APP)
class SecretsService {
    private class Cached<T>(val value: T, val at: Long)

    private val secretCache = ConcurrentHashMap<Pair<String, SecretScope>, Cached<List<SecretEntry>>>()
    private val projectCache = ConcurrentHashMap<String, Cached<List<ProviderProject>>>()

    fun provider(connection: SecretConnection): SecretProvider = SecretProvider.byId(connection.providerId)
        ?: throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "Unknown provider '${connection.providerId}'")

    fun client(connection: SecretConnection): SecretsClient = provider(connection).createClient(connection)

    fun secrets(connection: SecretConnection, scope: SecretScope, fresh: Boolean = false): List<SecretEntry> {
        val key = connection.id to scope
        if (!fresh) secretCache[key]?.takeIf { isFresh(it.at) }?.let { return it.value }
        return client(connection).listSecrets(scope).also { secretCache[key] = Cached(it, now()) }
    }

    fun projects(connection: SecretConnection, fresh: Boolean = false): List<ProviderProject> {
        if (!fresh) projectCache[connection.id]?.takeIf { isFresh(it.at) }?.let { return it.value }
        return client(connection).listProjects().also { projectCache[connection.id] = Cached(it, now()) }
    }

    fun folders(connection: SecretConnection, scope: SecretScope): List<SecretFolder> = client(connection).listFolders(scope)

    /** Creates a secret (see [SecretsClient.createSecret]) and forgets the connection's cached values so it shows up. Off the EDT. */
    fun createSecret(connection: SecretConnection, scope: SecretScope, key: String, value: SecretValue, comment: String = ""): CreateOutcome =
        client(connection).createSecret(scope, key, value, comment).also { invalidate(connection.id) }

    /** Creates an environment (see [SecretsClient.createEnvironment]) and forgets the connection's cached projects. Off the EDT. */
    fun createEnvironment(connection: SecretConnection, projectId: String, name: String, slug: String): ProviderEnvironment =
        client(connection).createEnvironment(projectId, name, slug).also { invalidate(connection.id) }

    /** Forgets cached values of one connection (after sign-in/out) or of all. */
    fun invalidate(connectionId: String? = null) {
        if (connectionId == null) {
            secretCache.clear()
            projectCache.clear()
        } else {
            secretCache.keys.removeIf { it.first == connectionId }
            projectCache.remove(connectionId)
        }
    }

    private fun isFresh(at: Long): Boolean = now() - at < SecretsAppSettings.getInstance().state.cacheTtlSeconds * 1000L

    private fun now() = System.currentTimeMillis()

    companion object {
        fun getInstance(): SecretsService = service()
    }
}
