package cz.xprees.secrets.aws

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS credentials for signing. The secret access key and the session token never appear in [toString]; only a shortened
 * access key ID does (`AKIA…WXYZ`).
 */
class AwsKeys(
    val accessKeyId: String,
    val secretAccessKey: String,
    /** Session token of temporary credentials (STS, SSO, assumed roles), or null for long-term keys. */
    val sessionToken: String? = null,
    /** When temporary credentials expire, if known. */
    val expiresAt: Instant? = null,
) {
    /** `AKIA…WXYZ`: enough to recognize the key, not enough to use it. */
    val shortId: String get() = if (accessKeyId.length > 8) accessKeyId.take(4) + "…" + accessKeyId.takeLast(4) else "…"

    override fun toString(): String = "AwsKeys($shortId${if (sessionToken != null) ", temporary" else ""})"
}

/**
 * AWS Signature Version 4 (`AWS4-HMAC-SHA256`), as specified in the AWS General Reference ("Create a signed AWS API
 * request"). Written by hand so the plugin needs no AWS SDK; checked against the AWS SigV4 test suite (see `AwsSigV4Test`).
 *
 * The four steps:
 * 1. **Canonical request**: method, canonical URI, canonical query string, canonical headers, signed header names and the
 *    hex SHA-256 of the body, one per line.
 * 2. **String to sign**: the algorithm, the request time, the credential scope (`<yyyyMMdd>/<region>/<service>/aws4_request`)
 *    and the hex SHA-256 of the canonical request.
 * 3. **Signing key**: HMAC-SHA256 chain `"AWS4" + secret` → date → region → service → `"aws4_request"`.
 * 4. **Signature**: hex HMAC-SHA256 of the string to sign with the signing key, sent in the `Authorization` header.
 *
 * Only the signature leaves the IDE; the secret access key never does.
 */
object AwsSigV4 {
    const val ALGORITHM = "AWS4-HMAC-SHA256"

    private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** A signed request: the headers to add ([headers]) and the intermediate values (for tests and the mock server). */
    class Signed(
        /** Headers to send in addition to the signed ones: `X-Amz-Date`, `X-Amz-Security-Token` (if any) and `Authorization`. */
        val headers: Map<String, String>,
        val canonicalRequest: String,
        val stringToSign: String,
        val signature: String,
    ) {
        val authorization: String get() = headers.getValue("Authorization")

        override fun toString(): String = "Signed(signature=$signature)"
    }

    /**
     * Signs a request.
     *
     * @param path the request path as sent (already URI-encoded; empty means `/`)
     * @param query the raw query string as sent, without `?` (or null)
     * @param headers the headers to sign, `host` included (name -> value; names are compared ignoring case). `x-amz-date`
     *   and, for temporary credentials, `x-amz-security-token` are added here
     * @param payload the exact body bytes
     */
    fun sign(
        method: String,
        path: String,
        query: String?,
        headers: Map<String, String>,
        payload: ByteArray,
        keys: AwsKeys,
        region: String,
        service: String,
        time: Instant,
    ): Signed {
        val amzDate = AMZ_DATE.format(time)
        val date = amzDate.substring(0, 8)
        val all = sortedMapOf<String, String>()
        headers.forEach { (name, value) -> all[name.lowercase()] = canonicalHeaderValue(value) }
        all["x-amz-date"] = amzDate
        keys.sessionToken?.let { all["x-amz-security-token"] = it }
        val signedHeaders = all.keys.joinToString(";")

        // 1. Canonical request
        val canonicalRequest = listOf(
            method.uppercase(),
            path.ifEmpty { "/" },
            canonicalQuery(query),
            all.entries.joinToString("") { (name, value) -> "$name:$value\n" },
            signedHeaders,
            hex(sha256(payload)),
        ).joinToString("\n")

        // 2. String to sign
        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = listOf(ALGORITHM, amzDate, scope, hex(sha256(canonicalRequest.toByteArray(StandardCharsets.UTF_8)))).joinToString("\n")

        // 3. and 4. Signing key and signature
        val signature = hex(hmac(signingKey(keys.secretAccessKey, date, region, service), stringToSign))

        val added = linkedMapOf("X-Amz-Date" to amzDate)
        keys.sessionToken?.let { added["X-Amz-Security-Token"] = it }
        added["Authorization"] = "$ALGORITHM Credential=${keys.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return Signed(added, canonicalRequest, stringToSign, signature)
    }

    /** The signing key for one day, region and service (step 3). */
    fun signingKey(secretAccessKey: String, date: String, region: String, service: String): ByteArray {
        val kDate = hmac(("AWS4$secretAccessKey").toByteArray(StandardCharsets.UTF_8), date)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    /** Header values are trimmed and runs of spaces inside become one space. */
    private fun canonicalHeaderValue(value: String): String = value.trim().replace(Regex(" {2,}"), " ")

    /** Query parameters decoded, re-encoded the AWS way (RFC 3986 unreserved characters kept) and sorted by name, then value. */
    private fun canonicalQuery(query: String?): String {
        if (query.isNullOrEmpty()) return ""
        return query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val name = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            uriEncode(decode(name)) to uriEncode(decode(value))
        }.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun decode(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8)

    /** URI-encodes [text] as SigV4 requires: unreserved characters (`A-Z a-z 0-9 - _ . ~`) as they are, the rest `%XX`. */
    fun uriEncode(text: String): String = buildString {
        for (byte in text.toByteArray(StandardCharsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.~") append(c) else append('%').append("%02X".format(byte.toInt() and 0xff))
        }
    }

    /** SHA-256 of [data]. */
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(StandardCharsets.UTF_8))

    /** Lower-case hex of [bytes]. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
