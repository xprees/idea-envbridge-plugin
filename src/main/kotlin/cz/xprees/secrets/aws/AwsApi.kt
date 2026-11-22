package cz.xprees.secrets.aws

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import java.time.Instant
import java.util.UUID

/**
 * The AWS Secrets Manager actions the plugin uses. Every call takes the credentials explicitly; nothing here stores them.
 * Shapes follow the AWS Secrets Manager API reference; see `docs/DESIGN.md` ("Fifth provider: AWS Secrets Manager").
 */
class AwsApi(val http: AwsHttp) {

    /**
     * What ListSecrets (or DescribeSecret) says about one secret: no value, only its name and metadata. [currentVersion] is the
     * version that has `AWSCURRENT` (from `SecretVersionsToStages`), [lastChanged] AWS's `LastChangedDate`; either tells whether
     * the value changed since it was read.
     */
    data class Listed(
        val name: String,
        val description: String = "",
        val tags: Map<String, String> = emptyMap(),
        val rotationEnabled: Boolean = false,
        val lastRotated: Instant? = null,
        val nextRotation: Instant? = null,
        val lastChanged: Instant? = null,
        val currentVersion: String? = null,
    )

    /** One page of `ListSecrets`: the secrets and the token for the next page. */
    class Page(val secrets: List<Listed>, val nextToken: String?) {
        val names: List<String> get() = secrets.map { it.name }
    }

    /** What `GetSecretValue` returned. [toString] leaves the value out. */
    class Value(
        val name: String,
        /** The version that has `AWSCURRENT`, which a check-and-set write names. */
        val versionId: String,
        /** `SecretString`, or null when the secret holds a binary value. */
        val string: String?,
        val binary: Boolean,
    ) {
        override fun toString(): String = "Value($name, version=$versionId, binary=$binary)"
    }

    /** What `BatchGetSecretValue` returned: values by name, and the error of each secret that couldn't be read. */
    class Batch(val values: Map<String, Value>, val errors: Map<String, SecretProviderException>) {
        override fun toString(): String = "Batch(${values.keys}, errors=${errors.keys})"
    }

    /** Lists secrets, [max] per page, those starting with [prefix] when given (AWS's `name` filter). */
    fun listSecrets(keys: AwsKeys, prefix: String?, nextToken: String?, max: Int = PAGE_SIZE): Page {
        val body = JsonObject().apply {
            addProperty("MaxResults", max)
            nextToken?.let { addProperty("NextToken", it) }
            // A value starting with ! negates AWS's filter: such a prefix is only applied locally
            if (!prefix.isNullOrEmpty() && !prefix.startsWith("!")) {
                add("Filters", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("Key", "name")
                        add("Values", JsonArray().apply { add(prefix) })
                    })
                })
            }
        }
        val o = http.call("ListSecrets", body, keys, "Listing secrets")
        val secrets = o.getAsJsonArray("SecretList")?.mapNotNull { e ->
            e.takeIf { it.isJsonObject }?.asJsonObject?.let { listed(it, "SecretVersionsToStages") }
        }.orEmpty()
        return Page(secrets, o.str("NextToken")?.takeIf { it.isNotEmpty() })
    }

    /**
     * Every secret (starting with [prefix], checked here too since AWS's filter isn't an exact prefix match), at most
     * [MAX_PAGES] pages.
     */
    fun allSecrets(keys: AwsKeys, prefix: String?): List<Listed> {
        val secrets = mutableListOf<Listed>()
        var token: String? = null
        var pages = 0
        do {
            if (++pages > MAX_PAGES) {
                throw SecretProviderException(
                    Kind.CONFIGURATION,
                    "More than ${MAX_PAGES * PAGE_SIZE} secrets; set a secret name prefix in the connection's settings to list fewer",
                )
            }
            val page = listSecrets(keys, prefix, token)
            secrets += page.secrets
            token = page.nextToken
        } while (token != null)
        return secrets.filter { prefix.isNullOrEmpty() || it.name.startsWith(prefix) }.distinctBy { it.name }
    }

    /** Every secret name (see [allSecrets]). */
    fun allNames(keys: AwsKeys, prefix: String?): List<String> = allSecrets(keys, prefix).map { it.name }

    /** The metadata of [name] (`DescribeSecret`; no value). */
    fun describeSecret(keys: AwsKeys, name: String): Listed {
        val o = http.call("DescribeSecret", JsonObject().apply { addProperty("SecretId", name) }, keys, "Reading the details of $name")
        return listed(o, "VersionIdsToStages") ?: Listed(name)
    }

    /** The current value of [name]. */
    fun getSecretValue(keys: AwsKeys, name: String): Value {
        val o = http.call("GetSecretValue", JsonObject().apply { addProperty("SecretId", name) }, keys, "Reading $name")
        val string = o.str("SecretString")
        return Value(
            name = name,
            versionId = o.str("VersionId").orEmpty(),
            string = string,
            binary = string == null && o.has("SecretBinary"),
        )
    }

    /**
     * The current values of [names] (1 to [BATCH_SIZE]) in one `BatchGetSecretValue` request (following `NextToken` if AWS
     * splits the answer). A secret that can't be read is in [Batch.errors], with the exception a single read would throw.
     * Throws when the request itself fails (AccessDenied for the action: see `AwsClient`'s fallback to `GetSecretValue`).
     */
    fun batchGetSecretValue(keys: AwsKeys, names: List<String>): Batch {
        require(names.size in 1..BATCH_SIZE) { "BatchGetSecretValue takes 1 to $BATCH_SIZE secrets" }
        val values = linkedMapOf<String, Value>()
        val errors = linkedMapOf<String, SecretProviderException>()
        var token: String? = null
        var pages = 0
        do {
            val body = JsonObject().apply {
                add("SecretIdList", JsonArray().apply { names.forEach(::add) })
                token?.let { addProperty("NextToken", it) }
            }
            val o = http.call("BatchGetSecretValue", body, keys, "Reading ${names.size} secrets")
            o.getAsJsonArray("SecretValues")?.forEach { e ->
                val v = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val name = v.str("Name") ?: return@forEach
                val string = v.str("SecretString")
                values[name] = Value(name, v.str("VersionId").orEmpty(), string, binary = string == null && v.has("SecretBinary"))
            }
            o.getAsJsonArray("Errors")?.forEach { e ->
                val err = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val id = err.str("SecretId") ?: return@forEach
                // The ID as asked for, or an ARN AWS resolved it to (matched back by name)
                val name = names.firstOrNull { it == id } ?: names.firstOrNull { id.contains(":secret:$it-") } ?: id
                errors[name] = AwsHttp.failure(err.str("ErrorCode"), err.str("Message").orEmpty(), 400, "Reading $name")
            }
            token = o.str("NextToken")?.takeIf { it.isNotEmpty() }
        } while (token != null && ++pages < MAX_PAGES)
        return Batch(values, errors)
    }

    /** Creates the secret [name] holding [string]; fails (ResourceExistsException, CONFIGURATION) if it exists. */
    fun createSecret(keys: AwsKeys, name: String, string: String, description: String? = null) {
        val body = JsonObject().apply {
            addProperty("Name", name)
            addProperty("SecretString", string)
            addProperty("ClientRequestToken", UUID.randomUUID().toString())
            description?.takeIf { it.isNotBlank() }?.let { addProperty("Description", it) }
        }
        http.call("CreateSecret", body, keys, "Creating $name")
    }

    /**
     * Sets the description of [name]: `UpdateSecret` without `SecretString`, so AWS makes no new version. The
     * `ClientRequestToken` (an idempotency token the AWS SDKs always fill in) only names a version when a value is sent; LocalStack
     * refuses the call without it.
     */
    fun updateDescription(keys: AwsKeys, name: String, description: String) {
        val body = JsonObject().apply {
            addProperty("SecretId", name)
            addProperty("ClientRequestToken", UUID.randomUUID().toString())
            addProperty("Description", description)
        }
        http.call("UpdateSecret", body, keys, "Changing the description of $name")
    }

    /** Starts the rotation of [name] with its stored rotation settings (`RotateSecret`). */
    fun rotateSecret(keys: AwsKeys, name: String) {
        val body = JsonObject().apply {
            addProperty("SecretId", name)
            addProperty("ClientRequestToken", UUID.randomUUID().toString())
        }
        http.call("RotateSecret", body, keys, "Rotating $name")
    }

    /**
     * Stores [string] as a new version of [name] with the staging labels [stages] (not `AWSCURRENT`, so readers don't see it
     * yet) and the version ID [versionId]. Returns the new version's ID.
     */
    fun putSecretValue(keys: AwsKeys, name: String, string: String, versionId: String, stages: List<String>): String {
        val body = JsonObject().apply {
            addProperty("SecretId", name)
            addProperty("SecretString", string)
            addProperty("ClientRequestToken", versionId)
            add("VersionStages", JsonArray().apply { stages.forEach(::add) })
        }
        val o = http.call("PutSecretValue", body, keys, "Writing $name")
        return o.str("VersionId") ?: versionId
    }

    /**
     * Moves the staging label [stage] to [moveTo] and/or removes it from [removeFrom]. AWS refuses the call when [stage] is
     * attached to a version other than [removeFrom]: that is the check-and-set.
     */
    fun updateVersionStage(keys: AwsKeys, name: String, stage: String, moveTo: String?, removeFrom: String?): AwsHttp.Response {
        val body = JsonObject().apply {
            addProperty("SecretId", name)
            addProperty("VersionStage", stage)
            moveTo?.let { addProperty("MoveToVersionId", it) }
            removeFrom?.let { addProperty("RemoveFromVersionId", it) }
        }
        return http.send("UpdateSecretVersionStage", body.toString(), keys)
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100

        /** Secrets per `BatchGetSecretValue` request (AWS's maximum for `SecretIdList`). */
        const val BATCH_SIZE = 20
        const val CURRENT = "AWSCURRENT"

        /** The helper label a new version gets until it is made current (never `AWSPENDING`, which rotation uses). */
        const val PENDING = "ENVBRIDGE_PENDING"

        private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

        /** An AWS timestamp: epoch seconds (JSON 1.1) or ISO-8601 text; null when absent or unreadable. */
        fun timestamp(e: JsonElement?): Instant? {
            val p = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
            return runCatching { if (p.isNumber) Instant.ofEpochMilli((p.asDouble * 1000).toLong()) else Instant.parse(p.asString) }.getOrNull()
        }

        /** A ListSecrets entry or DescribeSecret answer; [versionsField] names its version-to-labels map. */
        private fun listed(o: JsonObject, versionsField: String): Listed? {
            val name = o.str("Name") ?: return null
            val tags = o.getAsJsonArray("Tags")?.mapNotNull { t ->
                val tag = t.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                (tag.str("Key") ?: return@mapNotNull null) to tag.str("Value").orEmpty()
            }?.toMap().orEmpty()
            val current = o.get(versionsField)?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                ?.firstOrNull { (_, stages) -> stages.isJsonArray && stages.asJsonArray.any { it.isJsonPrimitive && it.asString == CURRENT } }?.key
            return Listed(
                name = name,
                description = o.str("Description").orEmpty(),
                tags = tags,
                rotationEnabled = o.get("RotationEnabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                lastRotated = timestamp(o.get("LastRotatedDate")),
                nextRotation = timestamp(o.get("NextRotationDate")),
                lastChanged = timestamp(o.get("LastChangedDate")),
                currentVersion = current,
            )
        }
    }
}
