package cz.xprees.secrets.aws

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import java.util.UUID

/**
 * The AWS Secrets Manager actions the plugin uses. Every call takes the credentials explicitly; nothing here stores them.
 * Shapes follow the AWS Secrets Manager API reference; see `docs/DESIGN.md` ("Fifth provider: AWS Secrets Manager").
 */
class AwsApi(val http: AwsHttp) {

    /** One page of `ListSecrets`: secret names and the token for the next page. */
    class Page(val names: List<String>, val nextToken: String?)

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

    /** Lists secret names, [max] per page, those starting with [prefix] when given (AWS's `name` filter). */
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
        val names = o.getAsJsonArray("SecretList")?.mapNotNull { e ->
            e.takeIf { it.isJsonObject }?.asJsonObject?.get("Name")?.takeIf { it.isJsonPrimitive }?.asString
        }.orEmpty()
        return Page(names, o.get("NextToken")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() })
    }

    /**
     * Every secret name (starting with [prefix], checked here too since AWS's filter isn't an exact prefix match), at most
     * [MAX_PAGES] pages.
     */
    fun allNames(keys: AwsKeys, prefix: String?): List<String> {
        val names = mutableListOf<String>()
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
            names += page.names
            token = page.nextToken
        } while (token != null)
        return names.filter { prefix.isNullOrEmpty() || it.startsWith(prefix) }.distinct()
    }

    /** The current value of [name]. */
    fun getSecretValue(keys: AwsKeys, name: String): Value {
        val o = http.call("GetSecretValue", JsonObject().apply { addProperty("SecretId", name) }, keys, "Reading $name")
        val string = o.get("SecretString")?.takeIf { it.isJsonPrimitive }?.asString
        return Value(
            name = name,
            versionId = o.get("VersionId")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            string = string,
            binary = string == null && o.has("SecretBinary"),
        )
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
        return o.get("VersionId")?.takeIf { it.isJsonPrimitive }?.asString ?: versionId
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
        const val CURRENT = "AWSCURRENT"

        /** The helper label a new version gets until it is made current (never `AWSPENDING`, which rotation uses). */
        const val PENDING = "ENVBRIDGE_PENDING"
    }
}
