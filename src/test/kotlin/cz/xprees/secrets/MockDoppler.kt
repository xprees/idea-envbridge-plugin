package cz.xprees.secrets

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny fake Doppler API (v3) for tests: `/v3/me`, projects, configs, secrets (computed values with `${KEY}` references),
 * writes, notes, the CLI's browser sign-in (generate, then authorize polled until approved) and token revocation.
 */
class MockDoppler : AutoCloseable {
    data class Request(val method: String, val path: String, val query: Map<String, String>, val authorization: String?, val body: String)

    data class Token(val type: String, val name: String, val email: String? = null, val project: String? = null, val config: String? = null)

    data class Config(val name: String, val environment: String, val root: Boolean)

    val requests = CopyOnWriteArrayList<Request>()

    val tokens = ConcurrentHashMap(
        mapOf(
            "dp.pt.personal" to Token("personal", "laptop", email = "alice@example.com"),
            "dp.sa.robot" to Token("service_account", "robot"),
            // a service token reads one config; /v3/me doesn't say which (the plugin finds out from the config's metadata)
            "dp.st.backend-prd" to Token("service_token", "ci", project = "backend", config = "prd"),
        ),
    )

    /** project -> configs. */
    val configs: MutableMap<String, MutableList<Config>> = ConcurrentHashMap(
        mapOf(
            "backend" to CopyOnWriteArrayList(
                listOf(
                    Config("dev", "dev", true),
                    Config("dev_personal", "dev", false),
                    Config("stg", "stg", true),
                    Config("prd", "prd", true),
                ),
            ),
            "web" to CopyOnWriteArrayList(listOf(Config("dev", "dev", true))),
            "secret-project" to CopyOnWriteArrayList(listOf(Config("dev", "dev", true))),
        ),
    )

    /** "project/config" -> raw secrets. */
    val secrets: MutableMap<String, MutableMap<String, String>> = ConcurrentHashMap(
        mapOf(
            "backend/dev" to ConcurrentHashMap(mapOf("DB_HOST" to "localhost", "DB_URL" to "postgres://\${DB_HOST}:5432", "DB_PASSWORD" to "dev-pass")),
            "backend/dev_personal" to ConcurrentHashMap(mapOf("DB_PASSWORD" to "my-pass")),
            "backend/stg" to ConcurrentHashMap(mapOf("DB_PASSWORD" to "stg-pass")),
            "backend/prd" to ConcurrentHashMap(mapOf("DB_PASSWORD" to "prd-pass", "API_KEY" to "prd-api")),
            "web/dev" to ConcurrentHashMap(mapOf("PUBLIC_URL" to "http://localhost:3000")),
            "secret-project/dev" to ConcurrentHashMap(mapOf("X" to "y")),
        ),
    )

    /** project -> secret -> note. */
    val notes: MutableMap<String, MutableMap<String, String>> = ConcurrentHashMap(mapOf("backend" to ConcurrentHashMap(mapOf("DB_PASSWORD" to "rotated monthly"))))

    /** Configs ("project/config") whose writes Doppler refuses as locked. */
    val lockedConfigs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Secrets ("project/config/KEY") whose deletion fails (to test partial moves). */
    val undeletable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Answer the next request with 429 and this Retry-After. */
    @Volatile
    var rateLimitRetryAfter: Int? = null

    /** The browser sign-in is approved after this many polls. */
    @Volatile
    var approveAfterPolls = 3

    /** The user declines the browser sign-in. */
    @Volatile
    var declineSignIn = false

    val polls = AtomicInteger()
    val revoked = CopyOnWriteArrayList<String>()

    /** The code shown to the user, of the last sign-in started. */
    @Volatile
    var lastCode: String? = null

    private val pending = ConcurrentHashMap<String, String>() // polling code -> code
    private val issued = AtomicInteger()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        requests += Request(method, path, query, authorization, body)
        val json = body.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()

        rateLimitRetryAfter?.let {
            rateLimitRetryAfter = null
            exchange.responseHeaders.set("Retry-After", it.toString())
            return respond(exchange, 429, error("rate limited"))
        }

        // ---- Sign-in (no token) ----
        when (path) {
            "/v3/auth/cli/generate/2" -> {
                // The CLI's call: GET with the machine's details as query parameters
                if (method != "GET" || query["hostname"].isNullOrBlank() || query["os"].isNullOrBlank()) {
                    return respond(exchange, 405, error("Use GET /v3/auth/cli/generate/2?hostname&version&os&arch"))
                }
                val code = "word-word-${issued.incrementAndGet()}"
                val polling = "polling-secret-${System.nanoTime()}"
                pending[polling] = code
                lastCode = code
                polls.set(0)
                return respond(exchange, 200, """{"code":"$code","polling_code":"$polling","auth_url":"$baseUrl/workplace/auth/cli?code=$code","success":true}""")
            }
            "/v3/auth/cli/authorize" -> {
                val polling = json.get("code")?.asString
                if (polling == null || !pending.containsKey(polling)) return respond(exchange, 400, error("Invalid or expired auth code"))
                if (declineSignIn) {
                    pending.remove(polling)
                    return respond(exchange, 400, error("Auth code was denied"))
                }
                if (polls.incrementAndGet() < approveAfterPolls) return respond(exchange, 409, error("Auth code has not been authorized yet"))
                pending.remove(polling)
                val token = "dp.ct.cli-${issued.incrementAndGet()}"
                tokens[token] = Token("cli", "EnvBridge", email = "alice@example.com")
                return respond(exchange, 200, """{"token":"$token","name":"EnvBridge","dashboard_url":"https://dashboard.doppler.com","success":true}""")
            }
            "/v3/auth/cli/revoke" -> {
                revoked += json.get("token")?.asString ?: ""
                tokens.remove(json.get("token")?.asString)
                return respond(exchange, 200, """{"success":true}""")
            }
        }

        val bearer = authorization?.removePrefix("Bearer ")
        val token = bearer?.let { tokens[it] } ?: return respond(exchange, 401, error("Invalid Auth token"))

        // A service token reads its own config only
        fun allowed(project: String?, config: String?): Boolean =
            token.type != "service_token" || ((project == null || project == token.project) && (config == null || config == token.config))

        when {
            path == "/v3/me" -> {
                val o = JsonObject().apply {
                    addProperty("name", token.name)
                    addProperty("type", token.type)
                    addProperty("token_preview", bearer.take(8) + "…")
                    add("workplace", JsonObject().apply { addProperty("slug", "wp-1"); addProperty("name", "Acme") })
                    token.email?.let { e -> add("user", JsonObject().apply { addProperty("email", e) }) }
                }
                respond(exchange, 200, o.toString())
            }

            path == "/v3/projects" -> {
                if (token.type == "service_token") return respond(exchange, 403, error("Service tokens can't list projects"))
                val list = configs.keys.sorted().joinToString(",") { """{"id":"$it","slug":"$it","name":"${it.replaceFirstChar(Char::uppercase)}"}""" }
                respond(exchange, 200, """{"page":1,"projects":[$list],"success":true}""")
            }

            path == "/v3/configs" && method == "GET" -> {
                val project = query["project"] ?: return respond(exchange, 400, error("Missing project"))
                if (project == "secret-project") return respond(exchange, 403, error("You don't have access"))
                val list = configs[project] ?: return respond(exchange, 404, error("Could not find requested project"))
                val items = list.joinToString(",") {
                    """{"name":"${it.name}","environment":"${it.environment}","project":"$project","root":${it.root},"locked":${it.root && it.name == "prd"}}"""
                }
                respond(exchange, 200, """{"page":1,"configs":[$items],"success":true}""")
            }

            path == "/v3/configs" && method == "POST" -> {
                val project = query["project"] ?: return respond(exchange, 400, error("Missing project"))
                val environment = json.get("environment").asString
                val name = json.get("name").asString
                val list = configs[project] ?: return respond(exchange, 404, error("Could not find requested project"))
                if (!name.startsWith(environment + "_")) return respond(exchange, 400, error("Config name must be prefixed with the environment"))
                list += Config(name, environment, false)
                secrets["$project/$name"] = ConcurrentHashMap()
                respond(exchange, 200, """{"config":{"name":"$name","environment":"$environment","root":false},"success":true}""")
            }

            path == "/v3/configs/config/secrets" && method == "GET" -> {
                val project = query["project"] ?: token.project
                val config = query["config"] ?: token.config
                if (!allowed(project, config)) return respond(exchange, 403, error("This token does not have access to requested config"))
                if (project == null || config == null) return respond(exchange, 400, error("Missing project or config"))
                val raw = secrets["$project/$config"] ?: return respond(exchange, 404, error("Could not find requested config"))
                val all = raw + mapOf("DOPPLER_PROJECT" to project, "DOPPLER_CONFIG" to config, "DOPPLER_ENVIRONMENT" to config.substringBefore('_'))
                val o = JsonObject()
                all.forEach { (k, v) ->
                    o.add(
                        k,
                        JsonObject().apply {
                            addProperty("raw", v)
                            addProperty("computed", v.replace(Regex("""\$\{([A-Z0-9_]+)}""")) { m -> all[m.groupValues[1]] ?: m.value })
                            addProperty("note", notes[project]?.get(k) ?: "")
                            addProperty("rawVisibility", "masked")
                            addProperty("computedVisibility", "masked")
                        },
                    )
                }
                respond(exchange, 200, """{"secrets":$o,"success":true}""")
            }

            path == "/v3/configs/config/secrets" && method == "POST" -> {
                val project = query["project"] ?: return respond(exchange, 400, error("Missing project"))
                val config = query["config"] ?: return respond(exchange, 400, error("Missing config"))
                if (!allowed(project, config)) return respond(exchange, 403, error("This token does not have access to requested config"))
                if ("$project/$config" in lockedConfigs) return respond(exchange, 400, error("This config is locked and cannot be modified"))
                val target = secrets["$project/$config"] ?: return respond(exchange, 404, error("Could not find requested config"))
                val changes = json.getAsJsonObject("secrets").entrySet()
                // A null value deletes the secret (what `doppler secrets delete` sends)
                changes.filter { it.value.isJsonNull }.forEach { (k, _) ->
                    if ("$project/$config/$k" in undeletable) return respond(exchange, 500, error("Internal error"))
                    if (!target.containsKey(k)) return respond(exchange, 404, error("Could not find requested secret"))
                }
                changes.forEach { (k, v) -> if (v.isJsonNull) target.remove(k) else target[k] = v.asString }
                respond(exchange, 200, """{"secrets":{},"success":true}""")
            }

            path == "/v3/projects/project/note" && method == "POST" -> {
                val project = query["project"] ?: json.get("project").asString
                notes.computeIfAbsent(project) { ConcurrentHashMap() }[json.get("secret").asString] = json.get("note").asString
                respond(exchange, 200, """{"secret":"${json.get("secret").asString}","note":${Gson().toJson(json.get("note").asString)}}""")
            }

            else -> respond(exchange, 404, error("Not found"))
        }
    }

    private fun error(message: String) = """{"messages":[${Gson().toJson(message)}],"success":false}"""

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
