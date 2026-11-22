package cz.xprees.secrets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import cz.xprees.secrets.aws.AwsKeys
import cz.xprees.secrets.aws.AwsSigV4
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny fake AWS Secrets Manager (JSON 1.1 protocol) for tests. **Every request's SigV4 signature is checked**: the
 * `Authorization` header must be well-formed, name a known access key, the region [region] and the service `secretsmanager`,
 * and its signature must equal the one computed from the request with that key's secret (a wrong one is
 * `InvalidSignatureException`). Implements `ListSecrets` (pages, name filter), `GetSecretValue`, `CreateSecret`,
 * `PutSecretValue` and `UpdateSecretVersionStage` with AWS's staging-label rules (the `RemoveFromVersionId` check included),
 * `BatchGetSecretValue` (values and per-secret `Errors`), `DescribeSecret`, `UpdateSecret` (description; a value makes a new
 * version) and `RotateSecret` (only for secrets with rotation on; it writes a new current version, as a rotation function would).
 * ListSecrets returns each secret's description, tags, rotation fields, `LastChangedDate` and `SecretVersionsToStages`.
 */
class MockAwsSecretsManager(val region: String = "eu-west-1") : AutoCloseable {
    data class Request(val action: String, val body: JsonObject, val authorization: String?, val securityToken: String?, val rawQuery: String?, val rawBody: String)

    class Version(val string: String?, val binary: ByteArray? = null, val stages: MutableSet<String> = ConcurrentHashMap.newKeySet())

    /** Versions of one secret, oldest first. */
    class Secret(val versions: MutableMap<String, Version> = java.util.Collections.synchronizedMap(linkedMapOf())) {
        @Volatile var description: String? = null
        @Volatile var tags: Map<String, String> = emptyMap()
        @Volatile var rotationEnabled = false
        @Volatile var lastRotated: Long? = null
        @Volatile var nextRotation: Long? = null

        /** Epoch seconds of the last change of any kind. */
        @Volatile var lastChanged: Long = System.currentTimeMillis() / 1000

        fun current(): Pair<String, Version> = synchronized(versions) { versions.entries.first { "AWSCURRENT" in it.value.stages }.toPair() }

        fun touch() {
            lastChanged = maxOf(lastChanged + 1, System.currentTimeMillis() / 1000)
        }
    }

    val requests = CopyOnWriteArrayList<Request>()

    /** Access key ID -> secret access key. */
    val keys = ConcurrentHashMap(mapOf("AKIAMOCKKEY000000001" to "mock-secret-key/1", "ASIAMOCKTEMP00000001" to "mock-temp-secret/2"))

    /** Access key IDs that must come with this session token. */
    val sessionTokens = ConcurrentHashMap(mapOf("ASIAMOCKTEMP00000001" to "mock-session-token"))

    val secrets: MutableMap<String, Secret> = ConcurrentHashMap()

    /** "Action" or "Action:secret name" refused with AccessDeniedException. */
    val denied: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether ListSecrets leaves out `SecretVersionsToStages` (change detection then uses `LastChangedDate`). */
    @Volatile
    var listWithoutVersions = false

    /** Answer the next request with ThrottlingException. */
    @Volatile
    var throttleNext = false

    /** Before the next `UpdateSecretVersionStage` of AWSCURRENT, someone else writes this value to this secret. */
    @Volatile
    var changeMeanwhile: Pair<String, String>? = null

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    /** Adds a secret with one current version. */
    fun put(name: String, string: String?, binary: ByteArray? = null) {
        secrets[name] = Secret().apply { versions[UUID.randomUUID().toString()] = Version(string, binary).apply { stages += "AWSCURRENT" } }
    }

    /** The current string of [name]. */
    fun current(name: String): String? = secrets[name]?.current()?.second?.string

    fun stagesOf(name: String): Map<String, Set<String>> = secrets.getValue(name).versions.mapValues { it.value.stages.toSet() }

    fun requests(action: String) = requests.filter { it.action == action }

    private fun handle(exchange: HttpExchange) {
        val rawBody = exchange.requestBody.readAllBytes()
        val bodyText = rawBody.toString(StandardCharsets.UTF_8)
        val target = exchange.requestHeaders.getFirst("X-Amz-Target").orEmpty()
        val action = target.substringAfter("secretsmanager.", "")
        val body = runCatching { JsonParser.parseString(bodyText).asJsonObject }.getOrElse { JsonObject() }
        requests += Request(action, body, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("X-Amz-Security-Token"), exchange.requestURI.rawQuery, bodyText)

        if (exchange.requestMethod != "POST" || !target.startsWith("secretsmanager.")) return error(exchange, "UnknownOperationException", "Unknown operation")
        if (exchange.requestHeaders.getFirst("Content-Type") != "application/x-amz-json-1.1") return error(exchange, "SerializationException", "Wrong content type")
        verifySignature(exchange, rawBody)?.let { (code, message) -> return error(exchange, code, message) }
        if (throttleNext) {
            throttleNext = false
            return error(exchange, "ThrottlingException", "Rate exceeded")
        }
        val name = body.get("SecretId")?.asString ?: body.get("Name")?.asString
        if (action in denied || "$action:$name" in denied) {
            return error(exchange, "AccessDeniedException", "User: arn:aws:iam::123456789012:user/dev is not authorized to perform: secretsmanager:$action on resource: $name")
        }

        when (action) {
            "ListSecrets" -> {
                val prefix = body.getAsJsonArray("Filters")?.firstOrNull { it.asJsonObject.get("Key").asString == "name" }
                    ?.asJsonObject?.getAsJsonArray("Values")?.first()?.asString
                val all = secrets.keys.filter { prefix == null || it.startsWith(prefix) }.sorted()
                val max = body.get("MaxResults")?.asInt ?: 100
                val start = body.get("NextToken")?.asString?.toInt() ?: 0
                val page = all.drop(start).take(max)
                val list = JsonArray().apply { page.forEach { add(listEntry(it, secrets.getValue(it), "SecretVersionsToStages")) } }
                respond(exchange, JsonObject().apply {
                    add("SecretList", list)
                    if (start + max < all.size) addProperty("NextToken", (start + max).toString())
                })
            }

            "GetSecretValue" -> {
                val secret = secrets[name] ?: return notFound(exchange)
                val (id, version) = secret.current()
                respond(exchange, JsonObject().apply {
                    addProperty("ARN", arn(name!!))
                    addProperty("Name", name)
                    addProperty("VersionId", id)
                    version.string?.let { addProperty("SecretString", it) }
                    version.binary?.let { addProperty("SecretBinary", Base64.getEncoder().encodeToString(it)) }
                    add("VersionStages", JsonArray().apply { version.stages.forEach(::add) })
                })
            }

            "BatchGetSecretValue" -> {
                if (body.has("Filters")) return error(exchange, "InvalidParameterException", "The mock only takes SecretIdList")
                val ids = body.getAsJsonArray("SecretIdList")?.map { it.asString }.orEmpty()
                if (ids.isEmpty() || ids.size > 20) return error(exchange, "InvalidParameterException", "SecretIdList must have 1 to 20 entries")
                val values = JsonArray()
                val errors = JsonArray()
                for (id in ids) {
                    val secret = secrets[id]
                    when {
                        "GetSecretValue:$id" in denied || "GetSecretValue" in denied -> errors.add(errorEntry(id, "AccessDeniedException", "not authorized to perform: secretsmanager:GetSecretValue on resource: $id"))
                        secret == null -> errors.add(errorEntry(id, "ResourceNotFoundException", "Secrets Manager can't find the specified secret."))
                        else -> values.add(valueEntry(id, secret))
                    }
                }
                respond(exchange, JsonObject().apply { add("SecretValues", values); add("Errors", errors) })
            }

            "DescribeSecret" -> {
                val secret = secrets[name] ?: return notFound(exchange)
                respond(exchange, listEntry(name!!, secret, "VersionIdsToStages"))
            }

            "UpdateSecret" -> {
                val secret = secrets[name] ?: return notFound(exchange)
                // As LocalStack: the idempotency token is required (the AWS SDKs always send one); it names a version only with a value
                if (!body.has("ClientRequestToken")) return error(exchange, "InvalidRequestException", "You must provide a ClientRequestToken value. We recommend a UUID-type value.")
                body.get("Description")?.let { secret.description = it.asString }
                body.get("SecretString")?.let { value ->
                    val token = body.get("ClientRequestToken")?.asString ?: UUID.randomUUID().toString()
                    synchronized(secret.versions) {
                        secret.versions[token] = Version(value.asString)
                        attach(secret, "AWSCURRENT", token)
                    }
                }
                secret.touch()
                respond(exchange, JsonObject().apply { addProperty("Name", name); addProperty("ARN", arn(name!!)) })
            }

            "RotateSecret" -> {
                val secret = secrets[name] ?: return notFound(exchange)
                if (!secret.rotationEnabled) return error(exchange, "InvalidRequestException", "A rotation function isn't configured for $name.")
                val token = body.get("ClientRequestToken")?.asString ?: UUID.randomUUID().toString()
                synchronized(secret.versions) {
                    secret.versions[token] = Version(rotatedValue ?: secret.current().second.string)
                    attach(secret, "AWSCURRENT", token)
                }
                secret.lastRotated = System.currentTimeMillis() / 1000
                secret.touch()
                respond(exchange, JsonObject().apply { addProperty("Name", name); addProperty("ARN", arn(name!!)); addProperty("VersionId", token) })
            }

            "CreateSecret" -> {
                val token = body.get("ClientRequestToken")?.asString ?: return error(exchange, "InvalidRequestException", "You must provide a ClientRequestToken value.")
                if (secrets.containsKey(name)) return error(exchange, "ResourceExistsException", "The operation failed because the secret $name already exists.")
                secrets[name!!] = Secret().apply {
                    versions[token] = Version(body.get("SecretString")?.asString).apply { stages += "AWSCURRENT" }
                    description = body.get("Description")?.asString
                }
                respond(exchange, JsonObject().apply { addProperty("Name", name); addProperty("VersionId", token); addProperty("ARN", arn(name)) })
            }

            "PutSecretValue" -> {
                val secret = secrets[name] ?: return notFound(exchange)
                val token = body.get("ClientRequestToken")?.asString ?: UUID.randomUUID().toString()
                val stages = body.getAsJsonArray("VersionStages")?.map { it.asString } ?: listOf("AWSCURRENT")
                synchronized(secret.versions) {
                    val version = Version(body.get("SecretString")?.asString)
                    secret.versions[token] = version
                    stages.forEach { attach(secret, it, token) }
                }
                secret.touch()
                respond(exchange, JsonObject().apply {
                    addProperty("Name", name)
                    addProperty("VersionId", token)
                    add("VersionStages", JsonArray().apply { stages.forEach(::add) })
                })
            }

            "UpdateSecretVersionStage" -> {
                val secret = secrets[name] ?: return notFound(exchange)
                val stage = body.get("VersionStage").asString
                val moveTo = body.get("MoveToVersionId")?.asString
                val removeFrom = body.get("RemoveFromVersionId")?.asString
                if (stage == "AWSCURRENT") changeMeanwhile?.takeIf { it.first == name }?.let { (_, value) ->
                    changeMeanwhile = null
                    synchronized(secret.versions) {
                        val other = UUID.randomUUID().toString()
                        secret.versions[other] = Version(value)
                        attach(secret, "AWSCURRENT", other)
                    }
                    secret.touch()
                }
                synchronized(secret.versions) {
                    val holder = secret.versions.entries.firstOrNull { stage in it.value.stages }?.key
                    if (moveTo != null && moveTo !in secret.versions) return error(exchange, "InvalidParameterException", "Not a valid version: $moveTo")
                    if (removeFrom != null && holder != removeFrom) {
                        return error(exchange, "InvalidParameterException", "Version stage $stage not found in version $removeFrom")
                    }
                    if (removeFrom == null && holder != null && holder != moveTo) {
                        return error(exchange, "InvalidParameterException", "Staging label $stage is attached to version $holder, so you must reference it in RemoveFromVersionId.")
                    }
                    removeFrom?.let { secret.versions[it]?.stages?.remove(stage) }
                    if (moveTo != null) attach(secret, stage, moveTo)
                    if (stage == "AWSCURRENT" && removeFrom != null && moveTo != null) {
                        secret.versions.values.forEach { it.stages.remove("AWSPREVIOUS") }
                        secret.versions[removeFrom]?.stages?.add("AWSPREVIOUS")
                    }
                    secret.touch()
                }
                respond(exchange, JsonObject().apply { addProperty("Name", name) })
            }

            // Never used by the plugin; answered so a test can tell it was called
            "DeleteSecret" -> error(exchange, "InvalidRequestException", "DeleteSecret is not expected from EnvBridge")

            else -> error(exchange, "UnknownOperationException", "Unknown operation $action")
        }
    }

    /** Attaches [stage] to [versionId] and removes it from every other version (AWSCURRENT leaves AWSPREVIOUS behind). */
    private fun attach(secret: Secret, stage: String, versionId: String) {
        val previous = secret.versions.entries.firstOrNull { stage in it.value.stages && it.key != versionId }?.key
        secret.versions.values.forEach { it.stages.remove(stage) }
        secret.versions.getValue(versionId).stages += stage
        if (stage == "AWSCURRENT" && previous != null) {
            secret.versions.values.forEach { it.stages.remove("AWSPREVIOUS") }
            secret.versions.getValue(previous).stages += "AWSPREVIOUS"
        }
    }

    /** Null when the request is correctly signed, else the AWS error (code, message). */
    private fun verifySignature(exchange: HttpExchange, body: ByteArray): Pair<String, String>? {
        val authorization = exchange.requestHeaders.getFirst("Authorization")
            ?: return "MissingAuthenticationTokenException" to "Missing Authentication Token"
        val match = AUTHORIZATION.matchEntire(authorization)
            ?: return "IncompleteSignatureException" to "Authorization header requires Credential, SignedHeaders and Signature"
        val (keyId, date, keyRegion, service, signedHeaders, signature) = match.destructured
        val secret = keys[keyId] ?: return "UnrecognizedClientException" to "The security token included in the request is invalid."
        if (sessionTokens[keyId] != null && exchange.requestHeaders.getFirst("X-Amz-Security-Token") != sessionTokens[keyId]) {
            return "UnrecognizedClientException" to "The security token included in the request is invalid."
        }
        if (service != "secretsmanager") return "InvalidSignatureException" to "Credential should be scoped to correct service: 'secretsmanager'."
        if (keyRegion != region) return "InvalidSignatureException" to "Credential should be scoped to a valid region."
        val amzDate = exchange.requestHeaders.getFirst("X-Amz-Date") ?: return "IncompleteSignatureException" to "X-Amz-Date is missing"
        if (!amzDate.startsWith(date)) return "InvalidSignatureException" to "Date in Credential scope does not match YYYYMMDD from ISO-8601 version of date from HTTP"
        val names = signedHeaders.split(';')
        for (required in listOf("host", "x-amz-date", "x-amz-target", "content-type")) {
            if (required !in names) return "InvalidSignatureException" to "'$required' must be a signed header"
        }
        val headers = names.filter { it != "x-amz-date" && it != "x-amz-security-token" }.associateWith { exchange.requestHeaders.getFirst(it).orEmpty() }
        val time = LocalDateTime.parse(amzDate, AMZ_DATE).toInstant(ZoneOffset.UTC)
        val expected = AwsSigV4.sign(
            exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestURI.rawQuery, headers, body,
            AwsKeys(keyId, secret, sessionTokens[keyId]), keyRegion, service, time,
        )
        if (expected.signature != signature) {
            return "InvalidSignatureException" to "The request signature we calculated does not match the signature you provided."
        }
        return null
    }

    /** The value a rotation writes (null: the current value again, as a new version). */
    @Volatile
    var rotatedValue: String? = null

    /** Changes [name]'s value "from elsewhere" (a new current version), as another user or a rotation would. */
    fun write(name: String, string: String) {
        val secret = secrets.getValue(name)
        synchronized(secret.versions) {
            val id = UUID.randomUUID().toString()
            secret.versions[id] = Version(string)
            attach(secret, "AWSCURRENT", id)
        }
        secret.touch()
    }

    private fun listEntry(name: String, secret: Secret, versionsField: String) = JsonObject().apply {
        addProperty("Name", name)
        addProperty("ARN", arn(name))
        secret.description?.let { addProperty("Description", it) }
        if (secret.tags.isNotEmpty()) add("Tags", JsonArray().apply { secret.tags.forEach { (k, v) -> add(JsonObject().apply { addProperty("Key", k); addProperty("Value", v) }) } })
        if (secret.rotationEnabled) addProperty("RotationEnabled", true)
        secret.lastRotated?.let { addProperty("LastRotatedDate", it) }
        secret.nextRotation?.let { addProperty("NextRotationDate", it) }
        addProperty("LastChangedDate", secret.lastChanged)
        if (!listWithoutVersions || versionsField != "SecretVersionsToStages") {
            add(versionsField, JsonObject().apply {
                synchronized(secret.versions) {
                    secret.versions.forEach { (id, v) -> if (v.stages.isNotEmpty()) add(id, JsonArray().apply { v.stages.forEach(::add) }) }
                }
            })
        }
    }

    private fun valueEntry(name: String, secret: Secret) = JsonObject().apply {
        val (id, version) = secret.current()
        addProperty("ARN", arn(name))
        addProperty("Name", name)
        addProperty("VersionId", id)
        version.string?.let { addProperty("SecretString", it) }
        version.binary?.let { addProperty("SecretBinary", Base64.getEncoder().encodeToString(it)) }
        add("VersionStages", JsonArray().apply { version.stages.forEach(::add) })
    }

    private fun errorEntry(id: String, code: String, message: String) = JsonObject().apply {
        addProperty("SecretId", id)
        addProperty("ErrorCode", code)
        addProperty("Message", message)
    }

    private fun arn(name: String) = "arn:aws:secretsmanager:$region:123456789012:secret:$name-AbCdEf"

    private fun notFound(exchange: HttpExchange) = error(exchange, "ResourceNotFoundException", "Secrets Manager can't find the specified secret.")

    private fun error(exchange: HttpExchange, code: String, message: String) {
        exchange.responseHeaders.set("x-amzn-ErrorType", "$code:http://internal.amazon.com/coral/com.amazonaws.secretsmanager/")
        send(exchange, 400, JsonObject().apply { addProperty("__type", "com.amazonaws.secretsmanager#$code"); addProperty("message", message) }.toString())
    }

    private fun respond(exchange: HttpExchange, body: JsonObject) = send(exchange, 200, body.toString())

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/x-amz-json-1.1")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private val AUTHORIZATION =
            Regex("""AWS4-HMAC-SHA256 Credential=([A-Z0-9]+)/(\d{8})/([a-z0-9-]+)/([a-z0-9]+)/aws4_request, SignedHeaders=([a-z0-9;-]+), Signature=([0-9a-f]{64})""")
        private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
