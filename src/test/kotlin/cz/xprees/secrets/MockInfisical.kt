package cz.xprees.secrets

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny fake Infisical instance for tests: machine identity login, projects, folders and secrets. */
class MockInfisical : AutoCloseable {
    data class Request(val method: String, val path: String, val query: Map<String, String>, val authorization: String?, val body: String)

    val requests = CopyOnWriteArrayList<Request>()

    /** Access tokens the server accepts; the login hands out the next one. */
    private var tokenCounter = 0
    private val validTokens = mutableSetOf<String>()

    @Volatile
    var clientId = "client-id"
    @Volatile
    var clientSecret = "client-secret"

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Makes every token issued so far invalid (simulates an expired session). */
    fun expireTokens() = synchronized(validTokens) { validTokens.clear() }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
        val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        val request = Request(exchange.requestMethod, exchange.requestURI.path, query, authorization, body)
        requests += request

        if (request.path == "/api/v1/auth/universal-auth/login") {
            if (!body.contains("\"$clientId\"") || !body.contains("\"$clientSecret\"")) return respond(exchange, 401, """{"message":"Invalid credentials"}""")
            val token = synchronized(validTokens) { "token-${++tokenCounter}".also(validTokens::add) }
            return respond(exchange, 200, """{"accessToken":"$token","expiresIn":7200,"accessTokenMaxTTL":7200,"tokenType":"Bearer"}""")
        }
        val token = authorization?.removePrefix("Bearer ")
        if (token == null || synchronized(validTokens) { token !in validTokens }) return respond(exchange, 401, """{"message":"Token expired"}""")

        when (request.path) {
            "/api/v1/projects" -> respond(
                exchange, 200,
                """{"projects":[{"id":"p1","_id":"p1","name":"Shop","slug":"shop","type":"secret-manager","orgId":"o",
                   "environments":[{"name":"Development","slug":"dev","id":"e1"},{"name":"Production","slug":"prod","id":"e2"}]}]}""",
            )

            "/api/v2/folders" -> respond(exchange, 200, """{"folders":[{"id":"f1","name":"backend"}]}""")
            "/api/v4/secrets" -> {
                val env = query["environment"]
                val path = query["secretPath"]
                val secrets = when {
                    env == "dev" && path == "/" -> """{"secretKey":"DB_PASSWORD","secretValue":"dev-pass","type":"shared"},{"secretKey":"API_KEY","secretValue":"dev-key","type":"shared"},{"secretKey":"ConnectionStrings__Default","secretValue":"Server=db;Password=dev","type":"shared"}"""
                    env == "prod" && path == "/" -> """{"secretKey":"DB_PASSWORD","secretValue":"prod-pass","type":"shared"},{"secretKey":"PROD_ONLY","secretValue":"p","type":"shared"}"""
                    env == "dev" && path == "/backend" -> """{"secretKey":"BACKEND_ONLY","secretValue":"b","type":"shared"}"""
                    else -> return respond(exchange, 404, """{"message":"Folder not found"}""")
                }
                respond(exchange, 200, """{"secrets":[$secrets],"imports":[]}""")
            }

            else -> respond(exchange, 404, """{"message":"Not found"}""")
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
