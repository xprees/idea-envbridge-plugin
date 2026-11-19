package cz.xprees.secrets.core.model

/**
 * A configured account on a secret manager instance: where to connect and how to sign in. Holds no credentials; those live
 * in the IDE's credential store (see `CredentialVault`), keyed by [id].
 */
data class SecretConnection(
    val id: String,
    val providerId: String,
    val name: String,
    /** Instance base URL without a trailing slash, e.g. `https://eu.infisical.com`. */
    val baseUrl: String,
    /** One of the provider's `AuthMethod.id`s. */
    val authMethodId: String,
    /** Non-secret, method specific settings (OAuth client ID, machine identity client ID, organization slug, …). */
    val options: Map<String, String> = emptyMap(),
) {
    fun option(key: String): String? = options[key]?.takeIf { it.isNotBlank() }

    companion object {
        /**
         * Normalizes and checks a base URL: `https://` is required, plain `http://` is accepted only for loopback addresses
         * (a local self-hosted instance), because the token travels with every request.
         *
         * @return the URL without trailing slashes, or null when it is not acceptable
         */
        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            return when (uri.scheme?.lowercase()) {
                "https" -> trimmed
                "http" -> trimmed.takeIf { isLoopback(host) }
                else -> null
            }
        }

        fun isLoopback(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]").lowercase()
            return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || h.startsWith("127.")
        }
    }
}
