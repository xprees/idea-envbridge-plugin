package cz.xprees.secrets.vault

import cz.xprees.secrets.core.model.SecretConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * The KV engine version (1 or 2) of each mount, per connection and namespace, as detected by [VaultClient] (the Vault CLI's
 * preflight request, or a probe). Kept in memory for [TTL_MS] so a mount upgraded to KV v2 (`vault kv enable-versioning`) is
 * noticed; a failed operation and sign-out forget it earlier. Holds no secrets.
 */
object VaultKvVersions {
    private class Entry(val version: Int, val storedAt: Long)

    private val versions = ConcurrentHashMap<String, Entry>()

    /** How long a detected version is trusted. */
    const val TTL_MS = 10 * 60_000L

    /** The cached version of [mount] in [namespace], or null when unknown or older than [TTL_MS]. */
    fun get(connection: SecretConnection, namespace: String, mount: String): Int? {
        val key = key(connection, namespace, mount)
        val entry = versions[key] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > TTL_MS) {
            versions.remove(key, entry)
            return null
        }
        return entry.version
    }

    fun put(connection: SecretConnection, namespace: String, mount: String, version: Int) {
        versions[key(connection, namespace, mount)] = Entry(version, System.currentTimeMillis())
    }

    fun forget(connection: SecretConnection, namespace: String, mount: String) {
        versions.remove(key(connection, namespace, mount))
    }

    /** Forgets every mount of [connection] (sign-out). */
    fun forgetAll(connection: SecretConnection) {
        val prefix = connection.id + SEPARATOR
        versions.keys.removeIf { it.startsWith(prefix) }
    }

    // The address is part of the key: a connection whose server address was changed starts over
    private fun key(connection: SecretConnection, namespace: String, mount: String) =
        listOf(connection.id, connection.baseUrl.trimEnd('/'), namespace, mount.trim('/')).joinToString(SEPARATOR)

    private const val SEPARATOR = "\u0000"
}
