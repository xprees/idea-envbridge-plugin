package cz.xprees.secrets.core.provider

import java.io.IOException
import javax.net.ssl.SSLException

/** Readable reasons for failed connections, with the fix where the cause is clear. */
object NetworkErrors {
    /** "Cannot reach <baseUrl>: …" for [error], saying what to change when the cause is a known misconfiguration. */
    fun describe(baseUrl: String, error: IOException): String {
        val detail = error.message ?: error.javaClass.simpleName
        // https:// against a server that speaks plain HTTP (a local dev server): the TLS handshake gets an HTTP answer
        if (error is SSLException && detail.contains("plaintext", ignoreCase = true) && baseUrl.startsWith("https://")) {
            return "Cannot reach $baseUrl: the server speaks plain HTTP, not HTTPS. For a local server use " +
                "${baseUrl.replaceFirst("https://", "http://")} (plain http:// is allowed only for localhost)."
        }
        return "Cannot reach $baseUrl: $detail"
    }
}
