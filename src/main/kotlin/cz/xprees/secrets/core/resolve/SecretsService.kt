package cz.xprees.secrets.core.resolve

import cz.xprees.secrets.core.provider.CopyResult
import cz.xprees.secrets.core.provider.MoveResult

import cz.xprees.secrets.core.model.ProviderEnvironment

import cz.xprees.secrets.core.model.SecretValue

import cz.xprees.secrets.core.provider.WriteOutcome

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

    /**
     * Secrets of [scope]: cached ones if younger than the cache TTL (or than [maxAgeMs] when given, as the keys index asks),
     * else read now and cached. [fresh] always reads (launches).
     */
    fun secrets(connection: SecretConnection, scope: SecretScope, fresh: Boolean = false, maxAgeMs: Long? = null): List<SecretEntry> {
        val key = connection.id to scope
        if (!fresh) secretCache[key]?.takeIf { isFresh(it.at, maxAgeMs) }?.let { return it.value }
        return client(connection).listSecrets(scope, fresh).also { secretCache[key] = Cached(it, now()) }
    }

    /**
     * Tells [connection]'s provider that [scopes] are about to be read (see [SecretsClient.prefetch]); scopes the cache already
     * has are left out unless [fresh]. Never fails. Off the EDT.
     */
    fun prefetch(connection: SecretConnection, scopes: Collection<SecretScope>, fresh: Boolean) {
        val needed = scopes.distinct().filter { fresh || secretCache[connection.id to it]?.takeIf { c -> isFresh(c.at, null) } == null }
        if (needed.size < 2) return
        runCatching { client(connection).prefetch(needed) }
    }

    /** The connection's projects, cached like [secrets]. */
    fun projects(connection: SecretConnection, fresh: Boolean = false, maxAgeMs: Long? = null): List<ProviderProject> {
        if (!fresh) projectCache[connection.id]?.takeIf { isFresh(it.at, maxAgeMs) }?.let { return it.value }
        return client(connection).listProjects().also { projectCache[connection.id] = Cached(it, now()) }
    }

    fun folders(connection: SecretConnection, scope: SecretScope): List<SecretFolder> = client(connection).listFolders(scope)

    /** Every folder of an environment, root first, walking [maxDepth] levels down (at most [maxCount]). Off the EDT. */
    fun allFolders(connection: SecretConnection, projectId: String, environment: String, maxDepth: Int = 4, maxCount: Int = 200): List<String> {
        val found = mutableListOf("/")
        var level = listOf("/")
        repeat(maxDepth) {
            if (level.isEmpty() || found.size >= maxCount) return@repeat
            level = level.flatMap { parent -> folders(connection, SecretScope(projectId, environment, parent)).map { it.path } }
            found += level
        }
        return found.distinct().take(maxCount)
    }

    /** Creates a secret (see [SecretsClient.createSecret]) and forgets the connection's cached values so it shows up. Off the EDT. */
    fun createSecret(connection: SecretConnection, scope: SecretScope, key: String, value: SecretValue, comment: String = ""): WriteOutcome {
        checkWritesAllowed(connection)
        // Forgotten even after a failure: a write can be half done (the value saved, a note not)
        try {
            return client(connection).createSecret(scope, key, value, comment)
        } finally {
            invalidate(connection.id)
        }
    }

    /**
     * Changes a secret (see [SecretsClient.updateSecret]), renaming it to [newKey] when given, and forgets the connection's
     * cached values. Off the EDT.
     */
    fun updateSecret(connection: SecretConnection, scope: SecretScope, key: String, value: SecretValue, comment: String? = null, newKey: String? = null): WriteOutcome {
        checkWritesAllowed(connection)
        try {
            return client(connection).updateSecret(scope, key, value, comment, newKey)
        } finally {
            invalidate(connection.id)
        }
    }

    /** Moves secrets between folders or environments (see [SecretsClient.moveSecrets]); forgets cached values. Off the EDT. */
    fun moveSecrets(connection: SecretConnection, source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        checkWritesAllowed(connection)
        try {
            return client(connection).moveSecrets(source, secrets, destination, overwrite)
        } finally {
            invalidate(connection.id)
        }
    }

    /** Copies secrets to another folder or environment (see [SecretsClient.copySecrets]); forgets cached values. Off the EDT. */
    fun copySecrets(connection: SecretConnection, source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope): CopyResult {
        checkWritesAllowed(connection)
        try {
            return client(connection).copySecrets(source, secrets, destination)
        } finally {
            invalidate(connection.id)
        }
    }

    /**
     * Deletes secrets of one folder, one by one; returns what happened to each (an exception's message for those that
     * failed). Forgets cached values. Off the EDT.
     */
    fun deleteSecrets(connection: SecretConnection, scope: SecretScope, keys: List<String>): Map<String, Result<WriteOutcome>> {
        checkWritesAllowed(connection)
        val client = client(connection)
        try {
            return keys.associateWith { key -> runCatching { client.deleteSecret(scope, key) } }
        } finally {
            invalidate(connection.id)
        }
    }

    /** Whether writing to secret managers is allowed (not turned off with *Read-only* in the settings). */
    val writesAllowed: Boolean get() = !SecretsAppSettings.getInstance().state.readOnly

    /** [writesAllowed], and [connection]'s provider can write (null: any connection). */
    fun writesAllowed(connection: SecretConnection?): Boolean =
        writesAllowed && (connection == null || SecretProvider.byId(connection.providerId)?.supportsWrites != false)

    /** Whether the provider with [providerId] can write and *Read-only* is off. */
    fun writesAllowed(providerId: String): Boolean = writesAllowed && SecretProvider.byId(providerId)?.supportsWrites != false

    /** Every write passes here, so *Read-only* holds whatever UI asked for it. */
    private fun checkWritesAllowed(connection: SecretConnection) {
        if (!writesAllowed) {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "Read-only is on (Settings › Tools › EnvBridge): this IDE doesn't create, change, move or delete secrets and environments",
            )
        }
        val provider = SecretProvider.byId(connection.providerId)
        if (provider?.supportsWrites == false) {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "${provider.displayName} connections are read-only in this version of EnvBridge",
            )
        }
    }

    /** Starts the rotation of the secret behind [scope] (see [SecretsClient.rotateSecret]); forgets cached values. Off the EDT. */
    fun rotateSecret(connection: SecretConnection, scope: SecretScope) {
        checkWritesAllowed(connection)
        try {
            client(connection).rotateSecret(scope)
        } finally {
            invalidate(connection.id)
        }
    }

    /** Creates an environment (see [SecretsClient.createEnvironment]) and forgets the connection's cached projects. Off the EDT. */
    fun createEnvironment(connection: SecretConnection, projectId: String, name: String, slug: String): ProviderEnvironment {
        checkWritesAllowed(connection)
        return client(connection).createEnvironment(projectId, name, slug).also { invalidate(connection.id) }
    }

    /** Forgets cached values of one connection (after sign-in/out) or of all. */
    fun invalidate(connectionId: String? = null) {
        if (connectionId == null) {
            secretCache.clear()
            projectCache.clear()
        } else {
            secretCache.keys.removeIf { it.first == connectionId }
            projectCache.remove(connectionId)
        }
        SecretProvider.all().forEach { runCatching { it.forget(connectionId) } }
    }

    private fun isFresh(at: Long, maxAgeMs: Long?): Boolean = now() - at < (maxAgeMs ?: cacheTtlMs())

    /** How long browsing and editor features reuse fetched values (Settings › Tools › EnvBridge). */
    fun cacheTtlMs(): Long = SecretsAppSettings.getInstance().state.cacheTtlSeconds * 1000L

    private fun now() = System.currentTimeMillis()

    companion object {
        fun getInstance(): SecretsService = service()
    }
}
