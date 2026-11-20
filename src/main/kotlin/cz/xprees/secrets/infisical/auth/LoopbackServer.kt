package cz.xprees.secrets.infisical.auth

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A one-shot HTTP server on the loopback interface that receives the result of a browser sign-in. It is bound to
 * 127.0.0.1 only, accepts a single successful callback and is closed right after (or when the attempt is given up).
 */
class LoopbackServer private constructor(private val server: HttpServer) : AutoCloseable {
    val port: Int get() = server.address.port

    override fun close() = server.stop(0)

    companion object {
        /** Starts a server on [port] (0 = any free port) that passes every request to [handler]. */
        fun start(port: Int = 0, handler: (HttpExchange) -> Unit): LoopbackServer {
            val server = try {
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 4)
            } catch (e: java.io.IOException) {
                throw SecretProviderException(Kind.CONFIGURATION, "Cannot listen on 127.0.0.1:$port for the sign-in callback: ${e.message}", e)
            }
            server.createContext("/") { exchange ->
                exchange.use {
                    try {
                        // DNS rebinding: a page on a hostile domain resolving to 127.0.0.1 would send its own Host header
                        if (!isLoopbackHost(it.requestHeaders.getFirst("Host"), server.address.port)) {
                            respond(it, 403)
                            return@use
                        }
                        handler(it)
                    } catch (_: Exception) {
                        runCatching { it.sendResponseHeaders(500, -1) }
                    }
                }
            }
            server.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Secrets sign-in callback").apply { isDaemon = true } }
            server.start()
            return LoopbackServer(server)
        }

        /** Waits for [future] while the user may cancel; gives up after [timeoutSeconds]. */
        fun <T> await(future: CompletableFuture<T>, interaction: SignInInteraction, timeoutSeconds: Long = SIGN_IN_TIMEOUT_SECONDS): T {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                if (interaction.isCancelled()) throw SecretProviderException(Kind.CANCELLED, "Sign-in cancelled")
                if (System.nanoTime() > deadline) throw SecretProviderException(Kind.CANCELLED, "Sign-in timed out; try again")
                try {
                    return future.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // keep waiting
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw (e.cause as? SecretProviderException) ?: SecretProviderException(Kind.SERVER, e.cause?.message ?: "Sign-in failed", e.cause)
                }
            }
        }

        fun isLoopbackHost(host: String?, port: Int): Boolean {
            if (host == null) return false
            val name = host.lowercase().removeSuffix(":$port")
            return name == "127.0.0.1" || name == "localhost" || name == "[::1]"
        }

        const val SIGN_IN_TIMEOUT_SECONDS = 300L
        const val MAX_BODY_BYTES = 64 * 1024

        /** `https://Example.com:443/x` -> `https://example.com` (what browsers send in `Origin`). */
        fun originOf(baseUrl: String): String {
            val uri = URI(baseUrl)
            val scheme = uri.scheme.lowercase()
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
            return "$scheme://${uri.host.lowercase()}$port"
        }

        fun queryParameters(exchange: HttpExchange): Map<String, String> =
            exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }

        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

        fun respond(exchange: HttpExchange, status: Int, body: String? = null, contentType: String = "text/plain; charset=utf-8") {
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            if (body == null) {
                exchange.sendResponseHeaders(status, -1)
                return
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

/**
 * Receives the session the Infisical web app posts after a browser sign-in (the flow the Infisical CLI uses:
 * `<instance>/login?callback_port=<port>`).
 *
 * The web app sends a JSON `POST` from the instance's origin. To make a forged callback from any other web page fail, the
 * handler accepts only `application/json` bodies (which makes browsers send a CORS preflight first), answers the preflight
 * only for the instance's own origin, and checks `Origin` on the `POST` as well. The token is validated against the
 * instance afterwards, before anything is stored.
 */
class BrowserSessionCallback(baseUrl: String) {
    private val expectedOrigin = LoopbackServer.originOf(baseUrl)
    val result = CompletableFuture<BrowserSession>()

    data class BrowserSession(val accessToken: String, val refreshToken: String?, val email: String?) {
        override fun toString() = "BrowserSession(email=$email)"
    }

    fun handle(exchange: HttpExchange) {
        val origin = exchange.requestHeaders.getFirst("Origin")
        if (origin == null || !origin.equals(expectedOrigin, ignoreCase = true) || exchange.requestURI.path != "/") {
            LoopbackServer.respond(exchange, 403)
            return
        }
        exchange.responseHeaders.set("Access-Control-Allow-Origin", origin)
        exchange.responseHeaders.set("Access-Control-Allow-Credentials", "true")
        exchange.responseHeaders.set("Vary", "Origin")
        when (exchange.requestMethod) {
            "OPTIONS" -> {
                exchange.responseHeaders.set("Access-Control-Allow-Methods", "POST, OPTIONS")
                exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type")
                // Chrome's Private Network Access: a public site calling a loopback address must be allowed explicitly
                exchange.responseHeaders.set("Access-Control-Allow-Private-Network", "true")
                exchange.responseHeaders.set("Access-Control-Max-Age", "60")
                LoopbackServer.respond(exchange, 204)
            }

            "POST" -> {
                val contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                if (!contentType.lowercase().startsWith("application/json")) {
                    LoopbackServer.respond(exchange, 415)
                    return
                }
                val bytes = exchange.requestBody.readNBytes(LoopbackServer.MAX_BODY_BYTES + 1)
                if (bytes.size > LoopbackServer.MAX_BODY_BYTES) {
                    LoopbackServer.respond(exchange, 413)
                    return
                }
                val session = runCatching {
                    val o = JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
                    val token = o.get("JTWToken")?.asString ?: o.get("JWTToken")?.asString
                    BrowserSession(
                        accessToken = token!!.also { require(it.isNotBlank()) },
                        refreshToken = o.get("RefreshToken")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() },
                        email = o.get("email")?.takeUnless { it.isJsonNull }?.asString,
                    )
                }.getOrNull()
                if (session == null) {
                    LoopbackServer.respond(exchange, 400)
                    return
                }
                // Record the session before answering, so it is known by the time the browser sees the response
                result.complete(session)
                LoopbackServer.respond(exchange, 200, "{}", "application/json")
            }

            else -> LoopbackServer.respond(exchange, 405)
        }
    }
}

/**
 * Receives the redirect of an OAuth 2.0 authorization code flow: `GET /callback?code=…&state=…`. Only the expected
 * [state] is accepted (constant-time comparison); errors from the authorization server end the attempt.
 */
class OAuthRedirectCallback(private val state: String, private val path: String = PATH) {
    val result = CompletableFuture<String>()

    fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET" || exchange.requestURI.path != path) {
            LoopbackServer.respond(exchange, 404)
            return
        }
        val params = LoopbackServer.queryParameters(exchange)
        val receivedState = params["state"].orEmpty()
        if (!LoopbackServer.constantTimeEquals(receivedState, state)) {
            LoopbackServer.respond(exchange, 400, page("This sign-in link is not valid any more. Start the sign-in again from the IDE."), HTML)
            return
        }
        val error = params["error"]
        val code = params["code"]
        when {
            error != null -> {
                LoopbackServer.respond(exchange, 200, page("Sign-in was not completed ($error). You can close this tab."), HTML)
                result.completeExceptionally(
                    SecretProviderException(if (error == "access_denied") Kind.CANCELLED else Kind.CONFIGURATION, "OAuth sign-in failed: $error"),
                )
            }

            code.isNullOrBlank() -> LoopbackServer.respond(exchange, 400, page("No authorization code received."), HTML)
            else -> {
                result.complete(code)
                LoopbackServer.respond(exchange, 200, page("Signed in. You can close this tab and return to the IDE."), HTML)
            }
        }
    }

    companion object {
        const val PATH = "/callback"
        private const val HTML = "text/html; charset=utf-8"

        private fun page(message: String) =
            """<!doctype html><html><head><meta charset="utf-8"><title>EnvBridge</title></head>""" +
                """<body style="font-family:sans-serif;margin:3em"><p>${message.replace("<", "&lt;")}</p></body></html>"""
    }
}
