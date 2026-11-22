package cz.xprees.secrets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake IAM Identity Center for tests: the `sso-oidc` endpoints (`/client/register`, `/device_authorization`, `/token` with
 * the device code and refresh token grants, OAuth errors as `{"error": …}` with `x-amzn-ErrorType`) and the portal
 * (`/federation/credentials`, `/assignment/accounts`, `/assignment/roles`, `/logout`, bearer token in
 * `x-amz-sso_bearer_token`), shaped after botocore's `sso-oidc` / `sso` models. One server answers both; the role credentials
 * are the temporary key [MockAwsSecretsManager] accepts.
 */
class MockAwsSso : AutoCloseable {
    data class Request(val method: String, val path: String, val query: String?, val body: String, val bearer: String?)

    val requests = CopyOnWriteArrayList<Request>()

    /** `authorization_pending` answers before the device code is approved. */
    @Volatile
    var pendingPolls = 1

    /** Answer the first poll with `slow_down`. */
    @Volatile
    var slowDownFirst = false

    /** Answer polls with `access_denied` / `expired_token`. */
    @Volatile
    var pollError: String? = null

    /** Lifetime of issued access tokens (seconds). */
    @Volatile
    var tokenLifetime = 3600L

    /** Lifetime of role credentials (milliseconds from now). */
    @Volatile
    var credentialsLifetimeMs = 3_600_000L

    /** Whether refresh tokens are accepted. */
    @Volatile
    var refreshAccepted = true

    /** Accounts and roles per page of `ListAccounts` / `ListAccountRoles` (the rest follows with `nextToken`). */
    @Volatile
    var pageSize = 100

    val accounts = linkedMapOf("123456789012" to "Dev account")
    val roles = linkedMapOf("123456789012" to listOf("Developer"))

    val validTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val refreshTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val polls = AtomicInteger()
    val deviceCode = "device-code-${UUID.randomUUID()}"
    val clientSecret = "client-secret-${UUID.randomUUID()}"

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    fun requests(path: String) = requests.filter { it.path == path }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val path = exchange.requestURI.path
        val bearer = exchange.requestHeaders.getFirst("x-amz-sso_bearer_token")
        requests += Request(exchange.requestMethod, path, exchange.requestURI.rawQuery, body, bearer)
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrElse { JsonObject() }
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8) }
        when (path) {
            "/client/register" -> {
                if (json.get("clientType")?.asString != "public") return oauthError(exchange, "InvalidClientMetadataException", "invalid_client_metadata")
                respond(exchange, JsonObject().apply {
                    addProperty("clientId", "client-id-1")
                    addProperty("clientSecret", clientSecret)
                    addProperty("clientIdIssuedAt", System.currentTimeMillis() / 1000)
                    addProperty("clientSecretExpiresAt", System.currentTimeMillis() / 1000 + 90 * 86400)
                })
            }

            "/device_authorization" -> {
                if (json.get("clientSecret")?.asString != clientSecret) return oauthError(exchange, "InvalidClientException", "invalid_client")
                respond(exchange, JsonObject().apply {
                    addProperty("deviceCode", deviceCode)
                    addProperty("userCode", "ABCD-EFGH")
                    addProperty("verificationUri", "https://device.sso.example/")
                    addProperty("verificationUriComplete", "https://device.sso.example/?user_code=ABCD-EFGH")
                    addProperty("expiresIn", 600)
                    addProperty("interval", 1)
                })
            }

            "/token" -> {
                if (json.get("clientSecret")?.asString != clientSecret) return oauthError(exchange, "InvalidClientException", "invalid_client")
                when (json.get("grantType")?.asString) {
                    "urn:ietf:params:oauth:grant-type:device_code" -> {
                        if (json.get("deviceCode")?.asString != deviceCode) return oauthError(exchange, "InvalidGrantException", "invalid_grant")
                        pollError?.let { return oauthError(exchange, if (it == "access_denied") "AccessDeniedException" else "ExpiredTokenException", it) }
                        val n = polls.incrementAndGet()
                        if (slowDownFirst && n == 1) return oauthError(exchange, "SlowDownException", "slow_down")
                        if (n <= pendingPolls + (if (slowDownFirst) 1 else 0)) return oauthError(exchange, "AuthorizationPendingException", "authorization_pending")
                        issue(exchange)
                    }
                    "refresh_token" -> {
                        val refresh = json.get("refreshToken")?.asString
                        if (!refreshAccepted || refresh !in refreshTokens) return oauthError(exchange, "InvalidGrantException", "invalid_grant")
                        refreshTokens.remove(refresh)
                        issue(exchange)
                    }
                    else -> oauthError(exchange, "UnsupportedGrantTypeException", "unsupported_grant_type")
                }
            }

            "/federation/credentials" -> {
                if (bearer !in validTokens) return unauthorized(exchange)
                if (query["account_id"] !in accounts || query["role_name"] !in roles[query["account_id"]].orEmpty()) {
                    return portalError(exchange, 403, "ForbiddenException", "No access")
                }
                respond(exchange, JsonObject().apply {
                    add("roleCredentials", JsonObject().apply {
                        addProperty("accessKeyId", "ASIAMOCKTEMP00000001")
                        addProperty("secretAccessKey", "mock-temp-secret/2")
                        addProperty("sessionToken", "mock-session-token")
                        addProperty("expiration", System.currentTimeMillis() + credentialsLifetimeMs)
                    })
                })
            }

            "/assignment/accounts" -> {
                if (bearer !in validTokens) return unauthorized(exchange)
                val (page, next) = page(accounts.entries.toList(), query["next_token"])
                respond(exchange, JsonObject().apply {
                    add("accountList", JsonArray().apply {
                        page.forEach { (id, name) -> add(JsonObject().apply { addProperty("accountId", id); addProperty("accountName", name); addProperty("emailAddress", "a@example.test") }) }
                    })
                    next?.let { addProperty("nextToken", it) }
                })
            }

            "/assignment/roles" -> {
                if (bearer !in validTokens) return unauthorized(exchange)
                val account = query["account_id"]
                val (page, next) = page(roles[account].orEmpty(), query["next_token"])
                respond(exchange, JsonObject().apply {
                    add("roleList", JsonArray().apply { page.forEach { add(JsonObject().apply { addProperty("roleName", it); addProperty("accountId", account) }) } })
                    next?.let { addProperty("nextToken", it) }
                })
            }

            "/logout" -> {
                if (bearer !in validTokens) return unauthorized(exchange)
                validTokens.remove(bearer)
                send(exchange, 200, "")
            }

            else -> portalError(exchange, 404, "NotFoundException", "Unknown path $path")
        }
    }

    /** One page of [items] from the `next_token` [token] (an opaque `page-<offset>`) and the next page's token. */
    private fun <T> page(items: List<T>, token: String?): Pair<List<T>, String?> {
        val from = token?.removePrefix("page-")?.toIntOrNull() ?: 0
        val to = minOf(items.size, from + pageSize)
        return items.subList(from, to) to (if (to < items.size) "page-$to" else null)
    }

    /** Makes every issued access token invalid (as if it expired or was revoked). */
    fun revokeAccessTokens() = validTokens.clear()

    private fun issue(exchange: HttpExchange) {
        val access = "access-${UUID.randomUUID()}"
        val refresh = "refresh-${UUID.randomUUID()}"
        validTokens += access
        refreshTokens += refresh
        respond(exchange, JsonObject().apply {
            addProperty("accessToken", access)
            addProperty("tokenType", "Bearer")
            addProperty("expiresIn", tokenLifetime)
            addProperty("refreshToken", refresh)
        })
    }

    private fun oauthError(exchange: HttpExchange, type: String, code: String) {
        exchange.responseHeaders.set("x-amzn-ErrorType", "$type:http://internal.amazon.com/coral/com.amazonaws.swbup.oidc/")
        send(exchange, if (type == "InternalServerException") 500 else 400, JsonObject().apply { addProperty("error", code); addProperty("error_description", "mock $code") }.toString())
    }

    private fun unauthorized(exchange: HttpExchange) = portalError(exchange, 401, "UnauthorizedException", "Session token not found or invalid")

    private fun portalError(exchange: HttpExchange, status: Int, type: String, message: String) {
        exchange.responseHeaders.set("x-amzn-ErrorType", "$type:http://internal.amazon.com/coral/com.amazonaws.switchboard.portal/")
        send(exchange, status, JsonObject().apply { addProperty("message", message) }.toString())
    }

    private fun respond(exchange: HttpExchange, body: JsonObject) = send(exchange, 200, body.toString())

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        if (bytes.isEmpty()) exchange.sendResponseHeaders(status, -1) else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
