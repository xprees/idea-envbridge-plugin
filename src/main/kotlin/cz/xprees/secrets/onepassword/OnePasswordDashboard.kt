package cz.xprees.secrets.onepassword

import com.google.gson.JsonObject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.onepassword.OnePasswordJson.str
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** The 1Password account a CLI connection uses, from `op whoami`: its sign-in address (`https://my.1password.com`) and UUID. */
data class OpAccount(val url: String, val uuid: String) {
    companion object {
        /** From `op whoami --format json` (`url`, `account_uuid`); null when either is missing or the address isn't `https://`. */
        fun fromWhoami(whoami: JsonObject?): OpAccount? {
            val url = whoami?.str("url")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val uuid = whoami.str("account_uuid")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
            return OpAccount("https://${uri.host}", uuid)
        }
    }
}

/**
 * "Open in dashboard" for 1Password connections that go through the CLI. [OnePasswordProvider.dashboardUrl] runs on the EDT,
 * where `op` can't be run, so it only uses what background work remembered here: the account (`op whoami` at sign-in or when
 * vaults are listed) and the IDs of the items a vault listing named. Memory only; no secrets (an account UUID and item IDs
 * aren't secret, and 1Password puts them in its own links).
 *
 * Connect connections have no account (a Connect server isn't tied to a signed-in user), so they get no link.
 */
object OnePasswordDashboard {
    private val accounts = ConcurrentHashMap<String, OpAccount>()

    /** connection key + vault ID -> item folder segment (title or ID) -> item ID. */
    private val itemIds = ConcurrentHashMap<String, Map<String, String>>()

    fun account(connection: SecretConnection): OpAccount? = accounts[key(connection)]

    fun rememberAccount(connection: SecretConnection, account: OpAccount) {
        accounts[key(connection)] = account
    }

    fun rememberItems(connection: SecretConnection, vaultId: String, segmentToId: Map<String, String>) {
        itemIds[key(connection) + SEPARATOR + vaultId] = segmentToId
    }

    /** Forgets everything remembered for [connection] (sign-out). */
    fun forget(connection: SecretConnection) {
        val key = key(connection)
        accounts.remove(key)
        itemIds.keys.removeIf { it == key || it.startsWith(key + SEPARATOR) }
    }

    /**
     * The address for [scope]: an item (any path below a vault) opens through 1Password's private link
     * `https://start.1password.com/open/i?a=<account>&v=<vault>&i=<item>&h=<sign-in host>`, the format of the apps' *Copy
     * Private Link*, which opens the item in the 1Password app or the web app; anything else (or an item whose ID isn't known)
     * opens the account's web app. Null for Connect, or before the account is known.
     */
    fun url(connection: SecretConnection, scope: SecretScope?): String? {
        if (connection.authMethodId == OnePasswordAuthMethods.CONNECT) return null
        val account = account(connection) ?: return null
        val home = account.url + "/"
        scope ?: return home
        val vaultId = scope.projectId.takeIf(OnePasswordConnect::looksLikeId) ?: return home
        val segment = SecretScope.normalizePath(scope.path).split('/').firstOrNull { it.isNotEmpty() } ?: return home
        val itemId = segment.takeIf(OnePasswordConnect::looksLikeId)
            ?: itemIds[key(connection) + SEPARATOR + vaultId]?.get(segment)
            ?: return home
        val host = URI(account.url).host
        return "https://start.1password.com/open/i?a=${enc(account.uuid)}&v=${enc(vaultId)}&i=${enc(itemId)}&h=${enc(host)}"
    }

    // The account option is part of the key: a connection switched to another account starts over
    private fun key(connection: SecretConnection) = listOf(
        connection.id,
        connection.authMethodId,
        connection.option(OnePasswordAuthMethods.OPTION_ACCOUNT).orEmpty(),
    ).joinToString(SEPARATOR)

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private const val SEPARATOR = "\u0000"
}
