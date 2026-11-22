package cz.xprees.secrets.aws

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import cz.xprees.secrets.core.provider.NetworkErrors
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration

/**
 * Minimal client for the AWS Secrets Manager JSON 1.1 protocol: every action is a signed `POST` to the endpoint with
 * `X-Amz-Target: secretsmanager.<Action>` and a JSON body. Set up like the other providers' HTTP clients: the JDK HttpClient
 * with the IDE's proxy and trusted certificates, no redirects, fixed timeouts, HTTP/1.1.
 *
 * Security: requests only ever go to [endpoint]; credentials are used only to compute the SigV4 `Authorization` header (the
 * session token is sent as `X-Amz-Security-Token`); nothing goes into the URL; neither request nor response bodies are logged.
 */
class AwsHttp(val endpoint: String, val region: String, private val clock: Clock = Clock.systemUTC()) {

    /** A raw response: status, body and headers. */
    class Response(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap()) {
        val isSuccess: Boolean get() = status in 200..299

        /** The first value of the header [name] (any case), or null. */
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private val uri: URI = URI.create(endpoint.trimEnd('/') + "/")

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .proxy(ProxySelector.getDefault() ?: ProxySelector.of(null))
            .apply { runCatching { com.intellij.util.net.ssl.CertificateManager.getInstance().sslContext }.getOrNull()?.let(::sslContext) }
            .build()
    }

    /**
     * Calls [action] with [body], signed with [keys]. Returns the response's JSON object, or throws the matching
     * [SecretProviderException] ([what] describes the operation in the message).
     */
    fun call(action: String, body: JsonObject, keys: AwsKeys, what: String): JsonObject {
        val response = send(action, body.toString(), keys)
        if (!response.isSuccess) throw failure(response, what)
        return json(response, what)
    }

    /** The JSON object of a successful [response]. */
    fun json(response: Response, what: String): JsonObject {
        if (response.body.isBlank()) return JsonObject()
        return try {
            JsonParser.parseString(response.body).asJsonObject
        } catch (e: Exception) {
            throw SecretProviderException(Kind.SERVER, "$what: AWS sent an unexpected response (not a JSON object)", e)
        }
    }

    /** Sends one signed request (no error mapping). */
    fun send(action: String, json: String, keys: AwsKeys): Response {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        val signedHeaders = mapOf(
            "Content-Type" to CONTENT_TYPE,
            "Host" to hostHeader(uri),
            "X-Amz-Target" to "$TARGET_PREFIX.$action",
        )
        val signed = AwsSigV4.sign("POST", uri.rawPath, null, signedHeaders, payload, keys, region, SERVICE, clock.instant())
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", CONTENT_TYPE)
            .header("X-Amz-Target", "$TARGET_PREFIX.$action")
            .apply { signed.headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            Response(response.statusCode(), response.body().orEmpty(), response.headers().map())
        } catch (e: IOException) {
            LOG.debug("$action to $endpoint failed: ${e.javaClass.simpleName}")
            throw SecretProviderException(Kind.NETWORK, NetworkErrors.describe(endpoint, e), e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SecretProviderException(Kind.CANCELLED, "Cancelled", e)
        }
    }

    companion object {
        private val LOG = logger<AwsHttp>()
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 30_000L
        const val SERVICE = "secretsmanager"
        const val TARGET_PREFIX = "secretsmanager"
        const val CONTENT_TYPE = "application/x-amz-json-1.1"
        private const val USER_AGENT = "EnvBridge-IntelliJ"

        /** At most this much of AWS's message is shown. */
        private const val MAX_DETAIL = 300

        private val AUTH_ERRORS = setOf(
            "UnrecognizedClientException", "InvalidSignatureException", "SignatureDoesNotMatch", "ExpiredTokenException",
            "ExpiredToken", "InvalidClientTokenId", "IncompleteSignature", "IncompleteSignatureException",
            "MissingAuthenticationToken", "MissingAuthenticationTokenException", "AuthFailure",
        )
        private val THROTTLING_ERRORS = setOf("ThrottlingException", "Throttling", "TooManyRequestsException", "RequestLimitExceeded")

        /**
         * The `Host` header as the JDK HttpClient sends it (and as it must be signed): the host, plus the port when it isn't the
         * scheme's default.
         */
        fun hostHeader(uri: URI): String {
            val port = uri.port
            val default = port == -1 || (uri.scheme.equals("https", true) && port == 443) || (uri.scheme.equals("http", true) && port == 80)
            return if (default) uri.host else "${uri.host}:$port"
        }

        /** The error code of an AWS error response (`__type` without its namespace, else `x-amzn-ErrorType`), or null. */
        fun errorCode(response: Response): String? {
            val type = runCatching { JsonParser.parseString(response.body).asJsonObject.get("__type")?.asString }.getOrNull()
                ?: response.header("x-amzn-ErrorType")
            return type?.substringAfterLast('#')?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** AWS's readable message (`message` or `Message`), capped; empty when there is none. Never contains secret values. */
        fun errorMessage(response: Response): String = runCatching {
            val o = JsonParser.parseString(response.body).asJsonObject
            (o.get("message") ?: o.get("Message"))?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull().orEmpty().trim().let { if (it.length > MAX_DETAIL) it.take(MAX_DETAIL) + "…" else it }

        /** Turns an error response into the matching [SecretProviderException]; only AWS's error code and message are shown. */
        fun failure(response: Response, what: String): SecretProviderException =
            failure(errorCode(response), errorMessage(response), response.status, what)

        /**
         * The [SecretProviderException] for AWS's error [code] and [message] with HTTP [status] (also used for the per-secret
         * errors of `BatchGetSecretValue`).
         */
        fun failure(code: String?, rawMessage: String, status: Int, what: String): SecretProviderException {
            val message = rawMessage.trim().let { if (it.length > MAX_DETAIL) it.take(MAX_DETAIL) + "…" else it }
            val response = Response(status, "")
            val detail = listOfNotNull(code, message.ifEmpty { null }).joinToString(": ").let { if (it.isEmpty()) "" else " ($it)" }
            return when {
                code in AUTH_ERRORS -> {
                    val clock = if (message.contains("expired", ignoreCase = true) && code != "ExpiredTokenException" && code != "ExpiredToken") {
                        "; check the computer's clock"
                    } else ""
                    SecretProviderException(Kind.AUTH_REQUIRED, "$what: AWS didn't accept the credentials$detail$clock")
                }
                code == "AccessDeniedException" || code == "AccessDenied" || response.status == 403 ->
                    SecretProviderException(Kind.FORBIDDEN, "$what: access denied$detail")
                code == "DecryptionFailure" ->
                    SecretProviderException(Kind.FORBIDDEN, "$what: the secret can't be decrypted with its KMS key$detail")
                code == "ResourceNotFoundException" -> SecretProviderException(Kind.NOT_FOUND, "$what: not found$detail")
                code in THROTTLING_ERRORS || response.status == 429 ->
                    SecretProviderException(Kind.SERVER, "$what: AWS throttled the request; retry in a moment$detail")
                response.status in 300..399 ->
                    SecretProviderException(Kind.CONFIGURATION, "$what: the server redirected the request; check the connection's endpoint")
                response.status in 400..499 -> SecretProviderException(Kind.CONFIGURATION, "$what failed$detail")
                else -> SecretProviderException(Kind.SERVER, "$what failed (HTTP ${response.status})$detail")
            }
        }
    }
}
