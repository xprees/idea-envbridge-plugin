package cz.xprees.secrets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny fake Vault / OpenBao server for tests: KV v2 mounts with secrets per namespace, the token endpoints, and the
 * AppRole, userpass, LDAP and OIDC sign-ins.
 */
class MockVault : AutoCloseable {
    data class Request(val method: String, val path: String, val query: Map<String, String>, val token: String?, val namespace: String?, val body: String)

    private data class Token(val ttl: Long, val renewable: Boolean, val name: String)

    val requests = CopyOnWriteArrayList<Request>()

    /** namespace ("root" for none) -> "mount/path" -> fields. */
    val secrets: MutableMap<String, MutableMap<String, Map<String, Any?>>> = ConcurrentHashMap(
        mapOf(
            "root" to ConcurrentHashMap(
                mapOf(
                    "secret/app/db" to mapOf("password" to "root-db-pass", "user" to "app"),
                    "secret/app/api" to mapOf("key" to "root-api-key", "limits" to mapOf("rps" to 10)),
                    "secret/app" to mapOf("name" to "the app"),
                    "secret/other/x" to mapOf("y" to "z"),
                ),
            ),
            "team-a" to ConcurrentHashMap(mapOf("secret/app/db" to mapOf("password" to "team-a-db-pass"))),
        ),
    )

    /** Answer the UI mounts endpoint with 404 (an older server, or one that doesn't offer it). */
    @Volatile
    var mountsEndpoint = true

    /** Answer invalid tokens as OpenBao does: only "permission denied", without Vault's "invalid token". */
    @Volatile
    var openBaoErrors = false

    @Volatile
    var tokenTtl = 3600L

    @Volatile
    var renewable = true

    var roleId = "role-id"
    var secretId = "secret-id"
    var username = "alice"
    var password = "alice-pass"

    /** Static tokens the server accepts (the "Token" method). */
    val staticTokens = ConcurrentHashMap<String, String>()

    private var counter = 0
    private val tokens = ConcurrentHashMap<String, Token>()

    /** OIDC: state -> (redirect URI, client nonce) of auth URLs handed out. */
    private val oidcStates = ConcurrentHashMap<String, Pair<String, String>>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Makes every token issued so far invalid (simulates expired tokens). */
    fun expireTokens() = tokens.clear()

    fun issuedTokens(): Int = synchronized(this) { counter }

    override fun close() = server.stop(0)

    private fun issue(name: String): String = synchronized(this) {
        val token = "hvs.mock-${++counter}"
        tokens[token] = Token(tokenTtl, renewable, name)
        token
    }

    private fun handle(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
        val rawPath = exchange.requestURI.rawPath
        val path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8)
        val token = exchange.requestHeaders.getFirst("X-Vault-Token")
        val namespace = exchange.requestHeaders.getFirst("X-Vault-Namespace")
        val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        requests += Request(exchange.requestMethod, path, query, token, namespace, body)
        val ns = namespace ?: "root"
        val json = body.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()

        fun requireToken(): Token? {
            val known = token?.let { tokens[it] ?: staticTokens[it]?.let { name -> Token(0, false, name) } }
            if (known == null) respond(exchange, 403, if (openBaoErrors) """{"errors":["permission denied"]}""" else """{"errors":["permission denied","invalid token"]}""")
            return known
        }

        when {
            // ---- Auth ----
            path == "/v1/auth/approle/login" && exchange.requestMethod == "POST" ->
                if (json.get("role_id")?.asString == roleId && json.get("secret_id")?.asString == secretId) login(exchange, "approle-$roleId")
                else respond(exchange, 400, """{"errors":["invalid role or secret ID"]}""")

            (path == "/v1/auth/userpass/login/$username" || path == "/v1/auth/ldap/login/$username") && exchange.requestMethod == "POST" ->
                if (json.get("password")?.asString == password) login(exchange, "${path.split('/')[3]}-$username")
                else respond(exchange, 400, """{"errors":["invalid username or password"]}""")

            path == "/v1/auth/oidc/oidc/auth_url" && exchange.requestMethod == "POST" -> {
                val redirect = json.get("redirect_uri").asString
                val state = "st-${System.nanoTime()}"
                oidcStates[state] = redirect to json.get("client_nonce").asString
                val url = "$baseUrl/idp/authorize?state=${enc(state)}&redirect_uri=${enc(redirect)}&role=${enc(json.get("role")?.asString ?: "")}"
                respond(exchange, 200, """{"data":{"auth_url":"$url"}}""")
            }

            path == "/v1/auth/oidc/oidc/callback" && exchange.requestMethod == "GET" -> {
                val expected = oidcStates.remove(query["state"])
                if (expected == null || query["code"] != "idp-code" || query["client_nonce"] != expected.second) {
                    respond(exchange, 400, """{"errors":["invalid state, code or nonce"]}""")
                } else {
                    login(exchange, "oidc-alice")
                }
            }

            path == "/v1/auth/token/lookup-self" -> requireToken()?.let { t ->
                respond(exchange, 200, """{"data":{"display_name":"${t.name}","ttl":${t.ttl},"renewable":${t.renewable}}}""")
            }

            path == "/v1/auth/token/renew-self" -> requireToken()?.let { t ->
                respond(exchange, 200, """{"auth":{"client_token":"$token","lease_duration":${t.ttl},"renewable":${t.renewable}}}""")
            }

            path == "/v1/auth/token/revoke-self" -> {
                token?.let(tokens::remove)
                respond(exchange, 204, null)
            }

            // ---- Engines ----
            path == "/v1/sys/internal/ui/mounts" -> {
                if (!mountsEndpoint) return respond(exchange, 404, """{"errors":[]}""")
                requireToken() ?: return
                respond(
                    exchange,
                    200,
                    """{"data":{"secret":{"secret/":{"type":"kv","options":{"version":"2"}},"kv1/":{"type":"kv","options":{"version":"1"}},""" +
                        """"cubbyhole/":{"type":"cubbyhole","options":null}},"auth":{}}}""",
                )
            }

            path.contains("/metadata/") || path.endsWith("/metadata") -> {
                requireToken() ?: return
                if (query["list"] != "true") return respond(exchange, 405, """{"errors":[]}""")
                val mount = path.removePrefix("/v1/").substringBefore("/metadata")
                val prefix = path.substringAfter("/metadata").trim('/').let { if (it.isEmpty()) "$mount/" else "$mount/$it/" }
                val keys = secrets[ns].orEmpty().keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
                    .map { rest -> if ('/' in rest) rest.substringBefore('/') + "/" else rest }.distinct().sorted()
                if (keys.isEmpty()) respond(exchange, 404, """{"errors":[]}""")
                else respond(exchange, 200, """{"data":{"keys":[${keys.joinToString(",") { "\"$it\"" }}]}}""")
            }

            path.contains("/data/") -> {
                requireToken() ?: return
                val mount = path.removePrefix("/v1/").substringBefore("/data/")
                if (mount == "forbidden") return respond(exchange, 403, """{"errors":["1 error occurred:\n\t* permission denied\n\n"]}""")
                val fields = secrets[ns].orEmpty()["$mount/${path.substringAfter("/data/").trim('/')}"]
                    ?: return respond(exchange, 404, """{"errors":[]}""")
                val data = com.google.gson.Gson().toJson(fields)
                respond(exchange, 200, """{"data":{"data":$data,"metadata":{"version":1}}}""")
            }

            else -> respond(exchange, 404, """{"errors":["no handler for route"]}""")
        }
    }

    private fun login(exchange: HttpExchange, name: String) {
        val token = issue(name)
        respond(exchange, 200, """{"auth":{"client_token":"$token","lease_duration":$tokenTtl,"renewable":$renewable}}""")
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String?) {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
