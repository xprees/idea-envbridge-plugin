package cz.xprees.secrets

import com.google.gson.JsonParser
import cz.xprees.secrets.aws.AwsKeys
import cz.xprees.secrets.aws.AwsSigV4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * [AwsSigV4] against cases of the AWS SigV4 test suite, as published in awslabs/aws-c-auth
 * (`tests/aws-signing-test-suite/v4`), copied unchanged to `src/test/resources/aws-sigv4/<case>/`: for each case the request
 * (`request.txt`) and credentials (`context.json`) go in, and the canonical request, string to sign and signature must come out
 * exactly as the suite says.
 */
class AwsSigV4Test {
    private val cases = listOf(
        "get-vanilla",
        "get-vanilla-query-order-key-case",
        "get-vanilla-with-session-token",
        "post-vanilla",
        "post-vanilla-query",
        "post-header-key-sort",
        "post-x-www-form-urlencoded",
    )

    private fun resource(case: String, file: String): String =
        javaClass.getResourceAsStream("/aws-sigv4/$case/$file")!!.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }

    private class Request(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)

    /** `METHOD /path?query HTTP/1.1`, `Name:value` lines, a blank line, the body. */
    private fun parseRequest(text: String): Request {
        val lines = text.split("\n")
        val (method, target) = lines.first().split(" ").let { it[0] to it[1] }
        val headers = linkedMapOf<String, String>()
        var i = 1
        while (i < lines.size && lines[i].isNotEmpty()) {
            headers[lines[i].substringBefore(':')] = lines[i].substringAfter(':')
            i++
        }
        val body = lines.drop(i + 1).joinToString("\n")
        return Request(method, target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun signCase(case: String): AwsSigV4.Signed {
        val context = JsonParser.parseString(resource(case, "context.json")).asJsonObject
        val credentials = context.getAsJsonObject("credentials")
        val keys = AwsKeys(
            credentials.get("access_key_id").asString,
            credentials.get("secret_access_key").asString,
            credentials.get("token")?.asString,
        )
        val request = parseRequest(resource(case, "request.txt"))
        val headers = LinkedHashMap(request.headers)
        // The suite's "sign_body" cases also sign the body's hash as x-amz-content-sha256 (an S3-style header)
        if (context.get("sign_body").asBoolean) headers["x-amz-content-sha256"] = AwsSigV4.hex(AwsSigV4.sha256(request.body))
        return AwsSigV4.sign(
            request.method, request.path, request.query, headers, request.body, keys,
            context.get("region").asString, context.get("service").asString, Instant.parse(context.get("timestamp").asString),
        )
    }

    @Test
    fun `canonical request, string to sign and signature match the AWS test suite`() {
        for (case in cases) {
            val signed = signCase(case)
            assertThat(signed.canonicalRequest).describedAs(case).isEqualTo(resource(case, "header-canonical-request.txt").trimEnd('\n'))
            assertThat(signed.stringToSign).describedAs(case).isEqualTo(resource(case, "header-string-to-sign.txt").trimEnd('\n'))
            assertThat(signed.signature).describedAs(case).isEqualTo(resource(case, "header-signature.txt").trim())
        }
    }

    @Test
    fun `authorization header and added headers`() {
        val signed = signCase("get-vanilla")
        assertThat(signed.authorization).isEqualTo(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
        )
        assertThat(signed.headers).containsEntry("X-Amz-Date", "20150830T123600Z").doesNotContainKey("X-Amz-Security-Token")

        val temporary = signCase("get-vanilla-with-session-token")
        assertThat(temporary.headers).containsEntry("X-Amz-Security-Token", "6e86291e8372ff2a2260956d9b8aae1d763fbf315fa00fa31553b73ebf194267")
        assertThat(temporary.authorization).contains("SignedHeaders=host;x-amz-date;x-amz-security-token")
        // the secret access key is never part of what is sent
        assertThat(temporary.headers.values).noneMatch { it.contains("wJalrXUtnFEMI") }
        assertThat(temporary.toString()).doesNotContain("wJalrXUtnFEMI")
    }

    @Test
    fun `signing key derivation`() {
        // The derived key of the suite's credentials for 20150830/us-east-1/iam, as given in the AWS General Reference example
        val key = AwsSigV4.signingKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830", "us-east-1", "iam")
        assertThat(AwsSigV4.hex(key)).isEqualTo("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9")
    }

    @Test
    fun `keys hide their secrets`() {
        val keys = AwsKeys("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "session-token-value")
        assertThat(keys.shortId).isEqualTo("AKIA…MPLE")
        assertThat(keys.toString()).isEqualTo("AwsKeys(AKIA…MPLE, temporary)").doesNotContain("wJalr").doesNotContain("session-token")
    }

    @Test
    fun `uri encoding keeps only unreserved characters`() {
        assertThat(AwsSigV4.uriEncode("a-b_c.d~e")).isEqualTo("a-b_c.d~e")
        assertThat(AwsSigV4.uriEncode("a b/c+d=é")).isEqualTo("a%20b%2Fc%2Bd%3D%C3%A9")
    }
}
