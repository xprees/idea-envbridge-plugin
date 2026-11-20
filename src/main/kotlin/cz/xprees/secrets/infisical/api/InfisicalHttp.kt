package cz.xprees.secrets.infisical.api

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Minimal HTTP client for one Infisical instance, built on the platform's [HttpRequests] so that the IDE's proxy and
 * certificate settings (custom CAs of self-hosted instances) apply.
 *
 * Security: requests only ever go to [baseUrl]; redirects are not followed (they could carry the token to another host);
 * neither request nor response bodies are logged.
 */
class InfisicalHttp(val baseUrl: String) {

    class Response(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        val isSuccess: Boolean get() = status in 200..299

        fun json(): JsonElement = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            throw SecretProviderException(Kind.SERVER, "The instance sent an unexpected response (not JSON)", e)
        }

        /** `Set-Cookie` value of [name], if the response sets it. */
        fun cookie(name: String): String? = headers.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value?.firstNotNullOfOrNull { header ->
                header.split(';').first().trim().takeIf { it.startsWith("$name=") }?.substringAfter('=')
            }
    }

    fun get(path: String, query: Map<String, String?> = emptyMap(), bearer: String? = null): Response =
        send("GET", url(path, query), bearer = bearer)

    fun postJson(path: String, json: String, bearer: String? = null, cookies: Map<String, String> = emptyMap()): Response =
        send("POST", url(path), bearer = bearer, body = json, contentType = HttpRequests.JSON_CONTENT_TYPE, cookies = cookies)

    fun postForm(path: String, form: Map<String, String>, basic: Pair<String, String>? = null): Response = send(
        "POST",
        url(path),
        body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" },
        contentType = "application/x-www-form-urlencoded",
        authorization = basic?.let { (user, password) ->
            "Basic " + Base64.getEncoder().encodeToString("${enc(user)}:${enc(password)}".toByteArray(StandardCharsets.UTF_8))
        },
    )

    fun delete(path: String, bearer: String? = null): Response = send("DELETE", url(path), bearer = bearer)

    /**
     * `PATCH` with a JSON body. [HttpURLConnection] (under [HttpRequests]) can't send PATCH, so this one uses the JDK's
     * HttpClient, set up like the rest: the IDE's proxy (the default [java.net.ProxySelector] the IDE installs) and its
     * trusted certificates, no redirects, the same timeouts.
     */
    fun patchJson(path: String, json: String, bearer: String? = null): Response = sendJson("PATCH", path, json, bearer)

    /** A request with a JSON body for methods [HttpRequests] can't send with one (PATCH, DELETE); see [patchJson]. */
    fun sendJson(method: String, path: String, json: String, bearer: String? = null): Response {
        val target = url(path)
        val client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            // HTTP/1.1: over plain http:// the default (HTTP/2) sends an h2c upgrade with every request, which servers such
            // as Infisical's answer with 405 for requests with a body (PATCH, DELETE)
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofMillis(CONNECT_TIMEOUT_MS.toLong()))
            .proxy(java.net.ProxySelector.getDefault() ?: java.net.ProxySelector.of(null))
            .apply { runCatching { com.intellij.util.net.ssl.CertificateManager.getInstance().sslContext }.getOrNull()?.let(::sslContext) }
            .build()
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(target))
            .timeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
            .header("Accept", "application/json")
            .header("Content-Type", HttpRequests.JSON_CONTENT_TYPE)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(json))
            .build()
        return try {
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            Response(response.statusCode(), response.body().orEmpty(), response.headers().map())
        } catch (e: IOException) {
            LOG.debug("Request to ${redact(target)} failed: ${e.javaClass.simpleName}")
            throw SecretProviderException(Kind.NETWORK, cz.xprees.secrets.core.provider.NetworkErrors.describe(baseUrl, e), e)
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

    private fun send(
        method: String,
        url: String,
        bearer: String? = null,
        body: String? = null,
        contentType: String? = null,
        authorization: String? = bearer?.let { "Bearer $it" },
        cookies: Map<String, String> = emptyMap(),
    ): Response {
        val builder = when (method) {
            "GET" -> HttpRequests.request(url)
            "POST" -> HttpRequests.post(url, contentType)
            "DELETE" -> HttpRequests.delete(url)
            else -> error("Unsupported method $method")
        }
        return try {
            builder
                .accept("application/json")
                .productNameAsUserAgent()
                .followRedirects(false)
                .throwStatusCodeException(false)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .tuner { connection ->
                    authorization?.let { connection.setRequestProperty("Authorization", it) }
                    if (cookies.isNotEmpty()) {
                        connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" })
                    }
                }
                .connect { request ->
                    if (body != null) request.write(body)
                    val connection = request.connection as HttpURLConnection
                    val status = connection.responseCode
                    val text = if (status in 200..299) request.readString() else runCatching { request.readError() }.getOrNull().orEmpty()
                    Response(status, text, connection.headerFields.filterKeys { it != null })
                }
        } catch (e: IOException) {
            LOG.debug("Request to ${redact(url)} failed: ${e.javaClass.simpleName}")
            throw SecretProviderException(Kind.NETWORK, cz.xprees.secrets.core.provider.NetworkErrors.describe(baseUrl, e), e)
        }
    }

    companion object {
        private val LOG = logger<InfisicalHttp>()
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000

        private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun redact(url: String) = url.substringBefore('?')

        /**
         * Turns an error response into the matching [SecretProviderException]. The server's `message` field is shown (Infisical
         * puts a readable reason there, never secret values); the rest of the body is not.
         */
        fun failure(response: Response, what: String): SecretProviderException {
            val message = runCatching { JsonParser.parseString(response.body).asJsonObject.get("message")?.asString }.getOrNull()
                ?.take(300)
            val detail = message?.let { ": $it" }.orEmpty()
            val kind = when (response.status) {
                401 -> Kind.AUTH_REQUIRED
                403 -> Kind.FORBIDDEN
                404 -> Kind.NOT_FOUND
                in 300..399 -> Kind.CONFIGURATION
                in 400..499 -> Kind.CONFIGURATION
                else -> Kind.SERVER
            }
            val text = when {
                response.status in 300..399 -> "$what: the instance redirected the request; check the instance URL"
                response.status == 401 -> "$what: not signed in or the session expired$detail"
                response.status == 403 -> "$what: access denied$detail"
                else -> "$what failed (HTTP ${response.status})$detail"
            }
            return SecretProviderException(kind, text)
        }
    }
}
