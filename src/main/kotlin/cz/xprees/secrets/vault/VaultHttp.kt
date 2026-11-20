package cz.xprees.secrets.vault

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
 * Minimal HTTP client for one Vault / OpenBao server, on the JDK's HttpClient set up like the rest of the plugin: the IDE's
 * proxy (the default [ProxySelector] the IDE installs) and trusted certificates (custom CAs), no redirects, fixed timeouts.
 *
 * Security: requests only ever go to [baseUrl]; redirects are not followed (they could carry the token to another host);
 * neither request nor response bodies are logged.
 */
class VaultHttp(val baseUrl: String) {

    class Response(val status: Int, val body: String) {
        val isSuccess: Boolean get() = status in 200..299

        fun json(): JsonElement = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            throw SecretProviderException(Kind.SERVER, "The server sent an unexpected response (not JSON)", e)
        }
    }

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .proxy(ProxySelector.getDefault() ?: ProxySelector.of(null))
            .apply { runCatching { com.intellij.util.net.ssl.CertificateManager.getInstance().sslContext }.getOrNull()?.let(::sslContext) }
            .build()
    }

    /**
     * Sends [method] to `/v1/<path>` with the token and namespace headers. [json] is the request body (POST only).
     */
    fun send(method: String, path: String, token: String? = null, namespace: String? = null, json: String? = null, query: Map<String, String> = emptyMap()): Response {
        val target = url(path, query)
        val request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .header("Accept", "application/json")
            // Vault asks browsers' cross-site requests to prove they aren't one; harmless for the API
            .header("X-Vault-Request", "true")
            .apply {
                token?.let { header("X-Vault-Token", it) }
                namespace?.takeIf { it != VaultApi.ROOT_NAMESPACE }?.let { header("X-Vault-Namespace", it) }
                if (json != null) header("Content-Type", "application/json")
            }
            .method(method, if (json != null) HttpRequest.BodyPublishers.ofString(json) else HttpRequest.BodyPublishers.noBody())
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            Response(response.statusCode(), response.body().orEmpty())
        } catch (e: IOException) {
            LOG.debug("Request to ${target.substringBefore('?')} failed: ${e.javaClass.simpleName}")
            throw SecretProviderException(Kind.NETWORK, "Cannot reach $baseUrl: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SecretProviderException(Kind.CANCELLED, "Cancelled", e)
        }
    }

    fun url(path: String, query: Map<String, String> = emptyMap()): String = buildString {
        append(baseUrl.trimEnd('/')).append("/v1/").append(path.trimStart('/'))
        if (query.isNotEmpty()) append('?').append(query.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" })
    }

    companion object {
        private val LOG = logger<VaultHttp>()
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 30_000L

        private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

        /** One path segment for a URL (`app db` -> `app%20db`; slashes inside a segment are kept out of the path). */
        fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        /** A path below a mount, every segment encoded: `/app/my db` -> `app/my%20db`. */
        fun path(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { segment(it) }

        /**
         * Turns an error response into the matching [SecretProviderException]. Vault's `errors` are shown (readable reasons,
         * never secret values); the rest of the body is not.
         */
        fun failure(response: Response, what: String): SecretProviderException {
            val errors = runCatching {
                JsonParser.parseString(response.body).asJsonObject.getAsJsonArray("errors")?.map { it.asString }
            }.getOrNull().orEmpty()
            val detail = errors.joinToString("; ").take(300).let { if (it.isEmpty()) "" else ": $it" }
            // A bad or expired token is answered with 403 "permission denied" (and, on newer servers, "invalid token")
            val badToken = errors.any { it.contains("invalid token", ignoreCase = true) || it.contains("token expired", ignoreCase = true) }
            val kind = when {
                response.status == 401 || badToken -> Kind.AUTH_REQUIRED
                response.status == 403 -> Kind.FORBIDDEN
                response.status == 404 -> Kind.NOT_FOUND
                response.status in 300..499 -> Kind.CONFIGURATION
                else -> Kind.SERVER
            }
            val text = when {
                response.status in 300..399 -> "$what: the server redirected the request; check the server URL"
                kind == Kind.AUTH_REQUIRED -> "$what: not signed in or the token expired$detail"
                response.status == 403 -> "$what: permission denied$detail"
                response.status == 503 -> "$what: the server is sealed or unavailable$detail"
                else -> "$what failed (HTTP ${response.status})$detail"
            }
            return SecretProviderException(kind, text)
        }
    }
}
