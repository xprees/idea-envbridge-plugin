package cz.xprees.secrets.core.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * Reads the claims of a JWT **without verifying it**. Only for display and expiry hints: a token is trusted because the
 * instance accepted it, never because of what it says about itself.
 */
object Jwt {
    fun claims(token: String): JsonObject? = runCatching {
        val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
        JsonParser.parseString(String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))).asJsonObject
    }.getOrNull()

    fun stringClaim(token: String, name: String): String? =
        claims(token)?.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** The `exp` claim (epoch seconds). */
    fun expiresAt(token: String): Long? = claims(token)?.get("exp")?.takeIf { it.isJsonPrimitive }?.asLong
}
