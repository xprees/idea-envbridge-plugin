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
 * A tiny fake Vault / OpenBao server for tests: KV v2 and KV v1 mounts with secrets per namespace (the same mounts in every
 * namespace), the mount preflight the Vault CLI uses, the token endpoints, and the AppRole, userpass, LDAP and OIDC sign-ins.
 * Paths of mounts not in [mounts] (`forbidden`, `ro`) are answered as KV v2, except by the preflight (403, as for a mount
 * that doesn't exist).
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
                    "kv1/app/db" to mapOf("password" to "v1-db-pass", "user" to "app1"),
                    "kv1/app/api" to mapOf("key" to "v1-api-key", "limits" to mapOf("rps" to 5)),
                    "kv1/top" to mapOf("t" to "top-level"),
                ),
            ),
            "team-a" to ConcurrentHashMap(
                mapOf(
                    "secret/app/db" to mapOf("password" to "team-a-db-pass"),
                    "kv1/app/db" to mapOf("password" to "team-a-v1-pass"),
                ),
            ),
        ),
    )

    /**
     * KV mounts and the `options.version` the server reports for each ("2", "1", or "" for a mount without options, as an
     * old KV v1 mount has). Listed by the UI mounts endpoint and answered by the preflight.
     */
    val mounts: MutableMap<String, String> = ConcurrentHashMap(mapOf("secret" to "2", "kv1" to "1"))

    /** Answer the mount preflight (`sys/internal/ui/mounts/<path>`); false answers 403, as for a token that may not read it. */
    @Volatile
    var mountPreflight = true

    /** Answer the UI mounts endpoint with 404 (an older server, or one that doesn't offer it). */
    @Volatile
    var mountsEndpoint = true

    /** Current version of each written secret ("namespace:mount/path"); seeded secrets are at version 1. */
    val versions = ConcurrentHashMap<String, Int>()
    private val deletedVersions = ConcurrentHashMap<String, Int>()

    /** Secrets written ("namespace:mount/path"), in order. */
    val writes = CopyOnWriteArrayList<String>()

    /** Mounts the token may read but not write. */
    val readOnlyMounts: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
                val kv = mounts.entries.sortedBy { it.key }.joinToString(",") { (mount, version) -> "\"$mount/\":${mountJson(mount, version)}" }
                respond(exchange, 200, """{"data":{"secret":{$kv,"cubbyhole/":{"type":"cubbyhole","options":null}},"auth":{}}}""")
            }

            path.startsWith("/v1/sys/internal/ui/mounts/") -> {
                requireToken() ?: return
                val target = path.removePrefix("/v1/sys/internal/ui/mounts/").trim('/')
                val mount = mountOf(target)
                if (!mountPreflight || mount == null) {
                    return respond(exchange, 403, """{"errors":["preflight capability check returned 403, please ensure client's policies grant access to path \"$target/\""]}""")
                }
                respond(exchange, 200, """{"data":${mountJson(mount, mounts.getValue(mount))}}""")
            }

            mountOf(path.removePrefix("/v1/"))?.let { mounts[it] != "2" } == true -> {
                requireToken() ?: return
                kv1(exchange, ns, mountOf(path.removePrefix("/v1/"))!!, path, query, json)
            }

            // A KV v2 mount's path without data/ or metadata/: KV v2's warning, with 404
            mountOf(path.removePrefix("/v1/")) != null && !path.contains("/data/") && !path.contains("/metadata") -> {
                requireToken() ?: return
                respond(
                    exchange,
                    404,
                    """{"request_id":"r","data":null,"warnings":["Invalid path for a versioned K/V secrets engine. See the API docs for the """ +
                        """appropriate API endpoints to use. If using the Vault CLI, use 'vault kv get' for this operation."]}""",
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
                val id = "$mount/${path.substringAfter("/data/").trim('/')}"
                val versionKey = "$ns:$id"
                val version = versions[versionKey] ?: if (secrets[ns].orEmpty().containsKey(id)) 1 else 0
                when (exchange.requestMethod) {
                    "POST" -> {
                        if (readOnlyMounts.contains(mount)) return respond(exchange, 403, """{"errors":["permission denied"]}""")
                        val cas = json.getAsJsonObject("options")?.get("cas")?.asInt
                        if (cas != null && cas != version) {
                            return respond(exchange, 400, """{"errors":["check-and-set parameter did not match the current version"]}""")
                        }
                        // Keep integers integers (the default would make 10 into 10.0)
                        val gson = com.google.gson.GsonBuilder().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create()
                        val fields = gson.fromJson<Map<String, Any?>>(json.getAsJsonObject("data"), Map::class.java)
                        secrets.getOrPut(ns) { ConcurrentHashMap() }[id] = fields
                        versions[versionKey] = version + 1
                        writes += "$ns:$id"
                        respond(exchange, 200, """{"data":{"version":${version + 1}}}""")
                    }

                    "DELETE" -> {
                        if (readOnlyMounts.contains(mount)) return respond(exchange, 403, """{"errors":["permission denied"]}""")
                        secrets[ns]?.remove(id)
                        deletedVersions[versionKey] = version
                        versions[versionKey] = version
                        respond(exchange, 204, null)
                    }

                    else -> {
                        val fields = secrets[ns].orEmpty()[id]
                        if (fields == null) {
                            // A deleted latest version still reports its metadata
                            return if (deletedVersions.containsKey(versionKey)) {
                                respond(exchange, 404, """{"data":{"data":null,"metadata":{"version":$version,"deletion_time":"2026-01-01T00:00:00Z"}}}""")
                            } else {
                                respond(exchange, 404, """{"errors":[]}""")
                            }
                        }
                        val data = com.google.gson.Gson().toJson(fields)
                        respond(exchange, 200, """{"data":{"data":$data,"metadata":{"version":$version}}}""")
                    }
                }
            }

            else -> respond(exchange, 404, """{"errors":["no handler for route"]}""")
        }
    }

    /** KV v1: `GET` (or `LIST` as `GET ?list=true`), `POST` / `PUT` of the whole secret, `DELETE` (for good). */
    private fun kv1(exchange: HttpExchange, ns: String, mount: String, path: String, query: Map<String, String>, json: JsonObject) {
        val rest = path.removePrefix("/v1/$mount").trim('/')
        val id = if (rest.isEmpty()) mount else "$mount/$rest"
        when (exchange.requestMethod) {
            "POST", "PUT" -> {
                if (readOnlyMounts.contains(mount)) return respond(exchange, 403, """{"errors":["permission denied"]}""")
                val gson = com.google.gson.GsonBuilder().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create()
                secrets.getOrPut(ns) { ConcurrentHashMap() }[id] = gson.fromJson<Map<String, Any?>>(json, Map::class.java)
                writes += "$ns:$id"
                respond(exchange, 204, null)
            }

            "DELETE" -> {
                if (readOnlyMounts.contains(mount)) return respond(exchange, 403, """{"errors":["permission denied"]}""")
                secrets[ns]?.remove(id)
                writes += "$ns:$id (deleted)"
                respond(exchange, 204, null)
            }

            else -> if (query["list"] == "true") {
                val prefix = "$id/"
                val keys = secrets[ns].orEmpty().keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
                    .map { r -> if ('/' in r) r.substringBefore('/') + "/" else r }.distinct().sorted()
                if (keys.isEmpty()) respond(exchange, 404, """{"errors":[]}""")
                else respond(exchange, 200, """{"data":{"keys":[${keys.joinToString(",") { "\"$it\"" }}]}}""")
            } else {
                val fields = secrets[ns].orEmpty()[id] ?: return respond(exchange, 404, """{"errors":[]}""")
                respond(exchange, 200, """{"request_id":"r","lease_duration":2764800,"data":${com.google.gson.Gson().toJson(fields)}}""")
            }
        }
    }

    /** The mount in [mounts] that [path] (without `/v1/`) is in, the longest first; null for none. */
    private fun mountOf(path: String): String? = mounts.keys.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }

    private fun mountJson(mount: String, version: String): String =
        """{"type":"kv","path":"$mount/","options":${if (version.isEmpty()) "null" else """{"version":"$version"}"""}}"""

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
