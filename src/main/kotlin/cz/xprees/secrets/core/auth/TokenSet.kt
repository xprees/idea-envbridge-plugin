package cz.xprees.secrets.core.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A provider session as stored in [CredentialVault] (serialized to JSON inside the credential store entry).
 * [toString] hides the tokens.
 */
data class TokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch seconds, or null when unknown. */
    val expiresAt: Long? = null,
    /** Who signed in, for display (email, identity name). */
    val account: String? = null,
    /** Provider specific extra values (organization ID, session ID, …). */
    val extra: Map<String, String> = emptyMap(),
) {
    /** Whether the access token is expired or expires within [skewSeconds]. */
    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000, skewSeconds: Long = 60): Boolean =
        expiresAt != null && nowEpochSeconds + skewSeconds >= expiresAt

    fun toJson(): String = JsonObject().apply {
        addProperty("accessToken", accessToken)
        refreshToken?.let { addProperty("refreshToken", it) }
        expiresAt?.let { addProperty("expiresAt", it) }
        account?.let { addProperty("account", it) }
        if (extra.isNotEmpty()) add("extra", JsonObject().apply { extra.forEach { (k, v) -> addProperty(k, v) } })
    }.toString()

    override fun toString(): String = "TokenSet(account=$account, expiresAt=$expiresAt, refresh=${refreshToken != null})"

    companion object {
        fun fromJson(json: String): TokenSet? = runCatching {
            val o = JsonParser.parseString(json).asJsonObject
            TokenSet(
                accessToken = o.get("accessToken").asString,
                refreshToken = o.get("refreshToken")?.takeUnless { it.isJsonNull }?.asString,
                expiresAt = o.get("expiresAt")?.takeUnless { it.isJsonNull }?.asLong,
                account = o.get("account")?.takeUnless { it.isJsonNull }?.asString,
                extra = o.getAsJsonObject("extra")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty(),
            )
        }.getOrNull()
    }
}
