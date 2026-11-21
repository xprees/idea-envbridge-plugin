package cz.xprees.secrets

import cz.xprees.secrets.core.provider.NetworkErrors
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException

class NetworkErrorsTest {
    @Test
    fun `failures read as words, not exception class names`() {
        val refused = NetworkErrors.describe("https://api.doppler.com", ConnectException())
        assertThat(refused).startsWith("Cannot reach https://api.doppler.com: ").doesNotContain("ConnectException").contains("can't be reached")
        assertThat(NetworkErrors.describe("https://x.example", UnknownHostException("x.example"))).contains("unknown host x.example")
        assertThat(NetworkErrors.describe("https://x.example", HttpConnectTimeoutException("HTTP connect timed out"))).contains("didn't answer in time")
        // a message of its own is kept
        assertThat(NetworkErrors.describe("https://x.example", java.io.IOException("stream closed"))).endsWith("stream closed")
    }
}
