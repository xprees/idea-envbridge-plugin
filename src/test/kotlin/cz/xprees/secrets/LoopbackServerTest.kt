package cz.xprees.secrets

import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.infisical.auth.BrowserSessionCallback
import cz.xprees.secrets.infisical.auth.LoopbackServer
import cz.xprees.secrets.infisical.auth.OAuthRedirectCallback
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The sign-in callback servers must only accept what the real flow sends. Requests are written by hand on a socket because
 * Java's HTTP clients don't allow setting `Origin` and `Host`.
 */
class LoopbackServerTest {
    private data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String)

    private fun send(port: Int, method: String, path: String, headers: Map<String, String>, body: String = ""): RawResponse {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            }
            socket.getOutputStream().apply { write(request.toByteArray(StandardCharsets.UTF_8)); write(bytes); flush() }
            val text = socket.getInputStream().readAllBytes().toString(StandardCharsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n").lines()
            val status = head.first().split(' ')[1].toInt()
            val responseHeaders = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return RawResponse(status, responseHeaders, text.substringAfter("\r\n\r\n", ""))
        }
    }

    private val origin = "https://eu.infisical.com"

    private fun browserServer(): Pair<BrowserSessionCallback, LoopbackServer> {
        val callback = BrowserSessionCallback("https://eu.infisical.com/")
        return callback to LoopbackServer.start(handler = callback::handle)
    }

    private fun headers(port: Int, origin: String? = this.origin, contentType: String? = "application/json") = buildMap {
        put("Host", "127.0.0.1:$port")
        origin?.let { put("Origin", it) }
        contentType?.let { put("Content-Type", it) }
    }

    @Test
    fun `browser callback accepts the instance origin with a json body`() {
        val (callback, server) = browserServer()
        server.use {
            val preflight = send(it.port, "OPTIONS", "/", headers(it.port, contentType = null) + ("Access-Control-Request-Method" to "POST"))
            assertThat(preflight.status).isEqualTo(204)
            assertThat(preflight.headers["access-control-allow-origin"]).isEqualTo(origin)
            assertThat(preflight.headers["access-control-allow-private-network"]).isEqualTo("true")

            val post = send(it.port, "POST", "/", headers(it.port), """{"email":"me@example.com","JTWToken":"jwt","RefreshToken":"r"}""")
            assertThat(post.status).isEqualTo(200)
            val session = callback.result.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(session.accessToken).isEqualTo("jwt")
            assertThat(session.refreshToken).isEqualTo("r")
            assertThat(session.toString()).doesNotContain("jwt")
        }
    }

    @Test
    fun `browser callback rejects other origins, simple requests and rebinding hosts`() {
        val (callback, server) = browserServer()
        server.use {
            val body = """{"JTWToken":"forged"}"""
            assertThat(send(it.port, "POST", "/", headers(it.port, origin = "https://evil.example"), body).status).isEqualTo(403)
            assertThat(send(it.port, "POST", "/", headers(it.port, origin = null), body).status).isEqualTo(403)
            // text/plain would skip the browser's CORS preflight: refused
            assertThat(send(it.port, "POST", "/", headers(it.port, contentType = "text/plain"), body).status).isEqualTo(415)
            assertThat(send(it.port, "POST", "/", headers(it.port) + ("Host" to "evil.example:${it.port}"), body).status).isEqualTo(403)
            assertThat(send(it.port, "POST", "/", headers(it.port), """{"nope":1}""").status).isEqualTo(400)
            assertThat(callback.result.isDone).isFalse()
        }
    }

    @Test
    fun `oauth callback checks state`() {
        val callback = OAuthRedirectCallback("expected-state")
        LoopbackServer.start(handler = callback::handle).use {
            val host = mapOf("Host" to "127.0.0.1:${it.port}")
            assertThat(send(it.port, "GET", "/callback?code=c&state=wrong", host).status).isEqualTo(400)
            assertThat(callback.result.isDone).isFalse()
            assertThat(send(it.port, "GET", "/other?code=c&state=expected-state", host).status).isEqualTo(404)
            val ok = send(it.port, "GET", "/callback?code=the-code&state=expected-state", host)
            assertThat(ok.status).isEqualTo(200)
            assertThat(ok.headers["cache-control"]).isEqualTo("no-store")
            assertThat(callback.result.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("the-code")
        }
    }

    @Test
    fun `oauth denial ends the attempt and cancellation stops waiting`() {
        val callback = OAuthRedirectCallback("s")
        LoopbackServer.start(handler = callback::handle).use {
            send(it.port, "GET", "/callback?error=access_denied&state=s", mapOf("Host" to "localhost:${it.port}"))
            assertThatThrownBy { LoopbackServer.await(callback.result, interaction(cancelled = false), 5) }
                .isInstanceOf(SecretProviderException::class.java)
        }
        val pending = OAuthRedirectCallback("s")
        assertThatThrownBy { LoopbackServer.await(pending.result, interaction(cancelled = true), 5) }
            .isInstanceOf(SecretProviderException::class.java)
            .extracting { (it as SecretProviderException).kind }.isEqualTo(SecretProviderException.Kind.CANCELLED)
    }

    @Test
    fun `origin normalization`() {
        assertThat(LoopbackServer.originOf("https://App.Infisical.com/")).isEqualTo("https://app.infisical.com")
        assertThat(LoopbackServer.originOf("https://x.example:443/infisical")).isEqualTo("https://x.example")
        assertThat(LoopbackServer.originOf("http://localhost:8080")).isEqualTo("http://localhost:8080")
    }

    private fun interaction(cancelled: Boolean) = object : SignInInteraction {
        override fun openBrowser(url: String) = Unit
        override fun isCancelled() = cancelled
    }
}
