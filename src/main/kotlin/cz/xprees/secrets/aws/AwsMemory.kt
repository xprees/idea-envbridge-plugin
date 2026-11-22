package cz.xprees.secrets.aws

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What AWS connections keep **in memory only** between operations (clients are created per operation):
 *
 * - the last **listing** (ListSecrets: names, descriptions, tags, rotation, the current version and `LastChangedDate` of each
 *   secret; never values), for comments, the tool window's details and change detection;
 * - **values read** (by `GetSecretValue` or `BatchGetSecretValue`), each with the version it is, so a read whose secret the
 *   listing shows unchanged needs no request (see [AwsClient]). At most [MAX_VALUES] per connection and [MAX_VALUE_AGE_MS] old;
 *   dropped whenever `SecretsService` forgets its cache (after every write, sign-in, sign-out and Refresh);
 * - whether `BatchGetSecretValue` was refused, so it isn't tried again for [BATCH_RETRY_MS].
 *
 * Nothing here is ever written to disk or logged.
 */
object AwsMemory {
    /** One connection's last listing. [hidden] counts the names left out because they don't follow the name layout. */
    class Listing(val at: Long, val byName: Map<String, AwsApi.Listed>, val hidden: Int)

    /**
     * A value read at [fetchedAt]. [listedChange] is the `LastChangedDate` the listing showed for the secret when it was read
     * (for listings without version IDs). [readAhead]: read ahead for a launch and not used yet. [toString] leaves the value out.
     */
    class CachedValue(val value: AwsApi.Value, val fetchedAt: Long, val listedChange: Instant?, val readAhead: Boolean = false) {
        override fun toString(): String = "CachedValue(${value.name}, version=${value.versionId})"
    }

    const val MAX_VALUES = 500
    const val MAX_VALUE_AGE_MS = 60 * 60 * 1000L
    const val BATCH_RETRY_MS = 60 * 60 * 1000L

    /** The clock (tests move it). */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    private val listings = ConcurrentHashMap<String, Listing>()
    private val values = ConcurrentHashMap<String, LinkedHashMap<String, CachedValue>>()
    private val batchRefusedAt = ConcurrentHashMap<String, Long>()

    fun listing(connectionId: String): Listing? = listings[connectionId]

    fun setListing(connectionId: String, listing: Listing) {
        listings[connectionId] = listing
    }

    /** Replaces what the listing knows about one secret (after EnvBridge changed its description), if it is listed. */
    fun updateListed(connectionId: String, name: String, change: (AwsApi.Listed) -> AwsApi.Listed) {
        listings.computeIfPresent(connectionId) { _, l -> l.byName[name]?.let { Listing(l.at, l.byName + (name to change(it)), l.hidden) } ?: l }
    }

    fun value(connectionId: String, name: String): CachedValue? {
        val map = values[connectionId] ?: return null
        synchronized(map) {
            val cached = map[name] ?: return null
            if (clock() - cached.fetchedAt > MAX_VALUE_AGE_MS) {
                map.remove(name)
                return null
            }
            return cached
        }
    }

    fun putValue(connectionId: String, value: AwsApi.Value, listedChange: Instant?, readAhead: Boolean = false) {
        val map = values.computeIfAbsent(connectionId) { object : LinkedHashMap<String, CachedValue>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedValue>?) = size > MAX_VALUES
        } }
        synchronized(map) { map[value.name] = CachedValue(value, clock(), listedChange, readAhead) }
    }

    /** The value read ahead for [name] within the last [withinMs], marked as used so it serves one read only; else null. */
    fun takeReadAhead(connectionId: String, name: String, withinMs: Long): CachedValue? {
        val map = values[connectionId] ?: return null
        synchronized(map) {
            val cached = map[name]?.takeIf { it.readAhead && clock() - it.fetchedAt < withinMs } ?: return null
            map[name] = CachedValue(cached.value, cached.fetchedAt, cached.listedChange, readAhead = false)
            return cached
        }
    }

    fun removeValue(connectionId: String, name: String) {
        values[connectionId]?.let { synchronized(it) { it.remove(name) } }
    }

    /** Whether `BatchGetSecretValue` may be tried for the connection (not refused within the last [BATCH_RETRY_MS]). */
    fun batchAvailable(connectionId: String): Boolean = batchRefusedAt[connectionId]?.let { clock() - it > BATCH_RETRY_MS } ?: true

    fun markBatchRefused(connectionId: String) {
        batchRefusedAt[connectionId] = clock()
    }

    /** Forgets the values read (of one connection, or of all together with every listing). */
    fun forget(connectionId: String?) {
        if (connectionId == null) {
            values.clear()
            listings.clear()
        } else {
            values.remove(connectionId)
        }
    }

    /** Forgets everything about a connection (sign-out). */
    fun forgetConnection(connectionId: String) {
        values.remove(connectionId)
        listings.remove(connectionId)
        batchRefusedAt.remove(connectionId)
    }
}
