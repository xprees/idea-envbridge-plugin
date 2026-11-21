package cz.xprees.secrets.core.provider

import java.io.IOException
import javax.net.ssl.SSLException

/** Readable reasons for failed connections, with the fix where the cause is clear. */
object NetworkErrors {
    /** "Cannot reach <baseUrl>: …" for [error], saying what to change when the cause is a known misconfiguration. */
    fun describe(baseUrl: String, error: IOException): String {
        val detail = readable(error)
        // https:// against a server that speaks plain HTTP (a local dev server): the TLS handshake gets an HTTP answer
        if (error is SSLException && detail.contains("plaintext", ignoreCase = true) && baseUrl.startsWith("https://")) {
            return "Cannot reach $baseUrl: the server speaks plain HTTP, not HTTPS. For a local server use " +
                "${baseUrl.replaceFirst("https://", "http://")} (plain http:// is allowed only for localhost)."
        }
        return "Cannot reach $baseUrl: $detail"
    }

    /** What went wrong, in words (Java's own messages are often empty, leaving only the exception's class name). */
    private fun readable(error: IOException): String = when (error) {
        is java.net.UnknownHostException -> "unknown host ${error.message.orEmpty()}".trim() + " (check the address, or the network/proxy)"
        is java.net.http.HttpConnectTimeoutException, is java.net.SocketTimeoutException, is java.net.http.HttpTimeoutException ->
            "the server didn't answer in time (check the address, or the network/proxy)"
        is java.net.ConnectException -> "the connection was refused or the server can't be reached (check the address, or the network/proxy)"
        is javax.net.ssl.SSLHandshakeException -> "the secure connection failed: ${error.message ?: "TLS handshake error"}"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
