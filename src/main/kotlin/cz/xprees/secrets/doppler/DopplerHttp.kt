package cz.xprees.secrets.doppler

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Minimal HTTP client for the Doppler API (`https://api.doppler.com`, or the base URL of the connection), on the JDK's
 * HttpClient set up like the rest of the plugin: the IDE's proxy and trusted certificates, no redirects, fixed timeouts,
 * HTTP/1.1 (an h2c upgrade over plain `http://`, used by the tests' loopback server, breaks request bodies).
 *
 * Security: requests only ever go to [baseUrl]; the token travels only in the `Authorization` header (never in a URL), the
 * sign-in polling code only in a request body; redirects are not followed; neither request nor response bodies are logged.
 */
class DopplerHttp(val baseUrl: String) {

    class Response(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap()) {
        val isSuccess: Boolean get() = status in 200..299

        fun json(): JsonElement = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            throw SecretProviderException(Kind.SERVER, "Doppler sent an unexpected response (not JSON)", e)
        }

        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .proxy(ProxySelector.getDefault() ?: ProxySelector.of(null))
            .apply { runCatching { com.intellij.util.net.ssl.CertificateManager.getInstance().sslContext }.getOrNull()?.let(::sslContext) }
            .build()
    }

    /** Sends [method] to [path] (`/v3/...`) with the bearer [token] (if any) and the JSON body [json] (if any). */
    fun send(method: String, path: String, token: String? = null, query: Map<String, String?> = emptyMap(), json: String? = null): Response {
        val target = url(path, query)
        val request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                if (json != null) header("Content-Type", "application/json")
            }
            .method(method, if (json != null) HttpRequest.BodyPublishers.ofString(json) else HttpRequest.BodyPublishers.noBody())
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            Response(response.statusCode(), response.body().orEmpty(), response.headers().map())
        } catch (e: IOException) {
            LOG.debug("Request to ${target.substringBefore('?')} failed: ${e.javaClass.simpleName}")
            throw SecretProviderException(Kind.NETWORK, "Cannot reach $baseUrl: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SecretProviderException(Kind.CANCELLED, "Cancelled", e)
        }
    }

    fun url(path: String, query: Map<String, String?> = emptyMap()): String = buildString {
        append(baseUrl.trimEnd('/'))
        append(if (path.startsWith('/')) path else "/$path")
        val params = query.filterValues { it != null }
        if (params.isNotEmpty()) append('?').append(params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v!!)}" })
    }

    companion object {
        private val LOG = logger<DopplerHttp>()
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 30_000L
        private const val USER_AGENT = "EnvBridge-IntelliJ"

        /** At most this much of the server's messages is shown. */
        private const val MAX_DETAIL = 300

        private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

        /** Doppler's readable error messages (`{"messages":[...],"success":false}`), joined; empty when there are none. */
        fun messages(response: Response): String = runCatching {
            JsonParser.parseString(response.body).asJsonObject.getAsJsonArray("messages")
                ?.mapNotNull { it.takeIf { m -> m.isJsonPrimitive }?.asString }
        }.getOrNull().orEmpty().joinToString("; ").let { if (it.length > MAX_DETAIL) it.take(MAX_DETAIL) + "…" else it }

        /**
         * Turns an error response into the matching [SecretProviderException]. Only Doppler's `messages` are shown (readable
         * reasons, never secret values); the rest of the body is not.
         */
        fun failure(response: Response, what: String): SecretProviderException {
            val messages = messages(response)
            val detail = if (messages.isEmpty()) "" else ": $messages"
            val locked = messages.contains("locked", ignoreCase = true)
            val kind = when {
                response.status == 401 -> Kind.AUTH_REQUIRED
                response.status == 403 && !locked -> Kind.FORBIDDEN
                response.status == 404 -> Kind.NOT_FOUND
                response.status == 429 -> Kind.SERVER
                response.status in 300..499 -> Kind.CONFIGURATION
                else -> Kind.SERVER
            }
            val text = when {
                response.status in 300..399 -> "$what: the server redirected the request; check the connection's API address"
                response.status == 401 -> "$what: not signed in, or the token was revoked or expired$detail"
                response.status == 429 -> {
                    val retry = response.header("Retry-After")?.trim()?.toLongOrNull()
                    "$what: Doppler rate limited the request; " + (retry?.let { "retry in $it s" } ?: "retry in a moment")
                }
                locked -> "$what: the config is locked in Doppler$detail"
                response.status == 403 -> "$what: access denied$detail"
                response.status == 404 -> "$what: not found$detail"
                else -> "$what failed (HTTP ${response.status})$detail"
            }
            return SecretProviderException(kind, text)
        }
    }
}
