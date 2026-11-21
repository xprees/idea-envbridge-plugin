package cz.xprees.secrets.aws

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.CopyResult
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.SetupLink
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.renameTarget
import cz.xprees.secrets.core.reference.ReferenceGrammar
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import javax.swing.Icon

/**
 * AWS Secrets Manager (unofficial integration). A connection is an AWS account in one region. A secret named `<prefix>/<env>`
 * holding a JSON object is the environment `<env>` of the project `<prefix>`, and its top-level fields are the keys (see
 * [AwsSecretNames]). No folders. Reading and writing; requests are signed with [AwsSigV4] (no AWS SDK).
 */
class AwsSecretsManagerProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "AWS Secrets Manager"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    override val referenceScheme: String = "aws-sm"
    override val referenceGrammar: ReferenceGrammar get() = AwsGrammar

    /** The regional endpoints of the common regions; any other region or a custom endpoint (LocalStack, VPC) can be typed. */
    override val instancePresets: List<Pair<String, String>> = REGIONS.map { (code, place) -> "$code ($place)" to endpoint(code) }

    override val authMethods: List<AuthMethod> = AwsAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String = "dev"

    override fun createClient(connection: SecretConnection): SecretsClient = AwsClient(connection)

    /**
     * The secret in the AWS console of its region (`…/secretsmanager/secret?name=…&region=…`), or the region's secret list.
     * None for endpoints outside `amazonaws.com` (LocalStack has no console).
     */
    override fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String?, key: String?): String? {
        val host = runCatching { URI(connection.baseUrl).host }.getOrNull()?.lowercase() ?: return null
        if (!host.endsWith(".amazonaws.com")) return null
        val region = runCatching { region(connection) }.getOrNull() ?: return null
        val console = "https://$region.console.aws.amazon.com/secretsmanager"
        if (scope == null) return "$console/listsecrets?region=$region"
        val name = URLEncoder.encode(AwsSecretNames.join(scope.projectId, scope.environment), Charsets.UTF_8).replace("+", "%20")
        return "$console/secret?name=$name&region=$region"
    }

    companion object {
        const val ID = "aws-secrets-manager"

        /** Regions offered as presets (code to place), the most used first. */
        val REGIONS = listOf(
            "us-east-1" to "N. Virginia",
            "us-east-2" to "Ohio",
            "us-west-1" to "N. California",
            "us-west-2" to "Oregon",
            "eu-west-1" to "Ireland",
            "eu-west-2" to "London",
            "eu-west-3" to "Paris",
            "eu-central-1" to "Frankfurt",
            "eu-north-1" to "Stockholm",
            "ap-south-1" to "Mumbai",
            "ap-northeast-1" to "Tokyo",
            "ap-southeast-1" to "Singapore",
            "ap-southeast-2" to "Sydney",
            "ca-central-1" to "Canada",
            "sa-east-1" to "São Paulo",
        )

        /** The regional Secrets Manager endpoint of [region]. */
        fun endpoint(region: String) = "https://secretsmanager.$region.amazonaws.com"

        private val REGIONAL_HOST = Regex("""^secretsmanager(?:-fips)?\.([a-z0-9-]+)\.amazonaws\.com(?:\.cn)?$""")
        private val VPC_HOST = Regex("""\.secretsmanager\.([a-z0-9-]+)\.vpce\.amazonaws\.com(?:\.cn)?$""")
        private val REGION = Regex("[a-z]{2}(?:-[a-z]+)+-\\d+")

        /** The connection's region: its Region option, else read from the endpoint's host, else CONFIGURATION. */
        fun region(connection: SecretConnection): String {
            connection.option(AwsAuthMethods.OPTION_REGION)?.trim()?.lowercase()?.let { region ->
                if (!REGION.matches(region)) {
                    throw SecretProviderException(Kind.CONFIGURATION, "'$region' isn't an AWS region (for example eu-west-1); check the connection '${connection.name}'")
                }
                return region
            }
            val host = runCatching { URI(connection.baseUrl).host }.getOrNull()?.lowercase().orEmpty()
            return (REGIONAL_HOST.find(host) ?: VPC_HOST.find(host))?.groupValues?.get(1) ?: throw SecretProviderException(
                Kind.CONFIGURATION,
                "The region of '${connection.name}' can't be read from its endpoint ${connection.baseUrl}; set the Region in its settings",
            )
        }
    }
}

/** The sign-in methods of AWS connections and their option keys. */
object AwsAuthMethods {
    const val ACCESS_KEY = "access-key"
    const val PROFILE = "profile"
    const val ENVIRONMENT = "environment"

    const val OPTION_REGION = "region"
    const val OPTION_NAME_PREFIX = "namePrefix"
    const val OPTION_PROFILE = "profile"
    const val OPTION_CLI_PATH = "awsCliPath"
    const val OPTION_ACCESS_KEY_ID = "accessKeyId"
    const val OPTION_SECRET_ACCESS_KEY = "secretAccessKey"
    const val OPTION_SESSION_TOKEN = "sessionToken"

    /** Every secret option (all of the access key method's). */
    val SECRET_KEYS = listOf(OPTION_ACCESS_KEY_ID, OPTION_SECRET_ACCESS_KEY, OPTION_SESSION_TOKEN)

    private val COMMON = listOf(
        AuthOption(OPTION_REGION, SecretsBundle.message("auth.aws.option.region.label"), required = false, hint = SecretsBundle.message("auth.aws.option.region.hint")),
        AuthOption(OPTION_NAME_PREFIX, SecretsBundle.message("auth.aws.option.namePrefix.label"), required = false, hint = SecretsBundle.message("auth.aws.option.namePrefix.hint")),
    )

    val ALL = listOf(
        AuthMethod(
            id = ACCESS_KEY,
            displayName = SecretsBundle.message("auth.aws.accessKey.name"),
            description = SecretsBundle.message("auth.aws.accessKey.description"),
            options = COMMON,
            secretOptions = listOf(
                AuthOption(OPTION_ACCESS_KEY_ID, SecretsBundle.message("auth.aws.option.accessKeyId.label"), hint = "AKIA…"),
                AuthOption(OPTION_SECRET_ACCESS_KEY, SecretsBundle.message("auth.aws.option.secretAccessKey.label")),
                AuthOption(OPTION_SESSION_TOKEN, SecretsBundle.message("auth.aws.option.sessionToken.label"), required = false, hint = SecretsBundle.message("auth.aws.option.sessionToken.hint")),
            ),
            setupLinks = listOf(
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.console"), "https://console.aws.amazon.com/iam/home#/security_credentials"),
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.accessKeys"), "https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys.html"),
            ),
        ),
        AuthMethod(
            id = PROFILE,
            displayName = SecretsBundle.message("auth.aws.profile.name"),
            description = SecretsBundle.message("auth.aws.profile.description"),
            options = listOf(
                AuthOption(OPTION_PROFILE, SecretsBundle.message("auth.aws.option.profile.label"), required = false, hint = AwsAuth.DEFAULT_PROFILE),
                AuthOption(OPTION_CLI_PATH, SecretsBundle.message("auth.aws.option.cliPath.label"), required = false, hint = SecretsBundle.message("auth.aws.option.cliPath.hint")),
            ) + COMMON,
            setupLinks = listOf(
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.profiles"), "https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-files.html"),
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.sso"), "https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html"),
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.console"), "https://console.aws.amazon.com/iam/home#/security_credentials"),
            ),
        ),
        AuthMethod(
            id = ENVIRONMENT,
            displayName = SecretsBundle.message("auth.aws.environment.name"),
            description = SecretsBundle.message("auth.aws.environment.description"),
            options = COMMON,
            setupLinks = listOf(
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.environment"), "https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-envvars.html"),
            ),
        ),
    )
}

/**
 * [SecretsClient] for one AWS connection. Every write is a read-modify-write of one secret's JSON object made safe by a real
 * check-and-set (see [writeChecked]); AWS secrets themselves are never deleted.
 */
class AwsClient(private val connection: SecretConnection) : SecretsClient {
    private val api by lazy { AwsApi(AwsHttp(connection.baseUrl, AwsSecretsManagerProvider.region(connection))) }
    private val prefix: String? = connection.option(AwsAuthMethods.OPTION_NAME_PREFIX)?.trim()
    private val auth = AwsAuth(connection) { keys -> api.listSecrets(keys, prefix, null, 1) }

    override fun signIn(interaction: SignInInteraction): String = auth.signIn(interaction)

    override fun signOut() = auth.signOut()

    override fun hasCredentials(): Boolean = auth.hasCredentials()

    /** Name prefixes as projects, each with the last segments of its secrets' names as environments. */
    override fun listProjects(): List<ProviderProject> {
        val names = auth.withKeys { api.allNames(it, prefix) }
        val byProject = names.mapNotNull(AwsSecretNames::split).groupBy({ it.first }, { it.second })
        return byProject.keys.sortedWith(compareBy({ it == AwsSecretNames.NO_PREFIX }, { it.lowercase() })).map { project ->
            ProviderProject(
                id = project,
                name = if (project == AwsSecretNames.NO_PREFIX) SecretsBundle.message("aws.project.noPrefix") else project,
                slug = project,
                environments = byProject.getValue(project).distinct().sortedBy { it.lowercase() }.map { ProviderEnvironment(slug = it, name = it) },
            )
        }
    }

    /** AWS Secrets Manager has no folders. */
    override fun listFolders(scope: SecretScope): List<SecretFolder> = emptyList()

    /** The keys of the environment's secret: its JSON object's fields, or one key for a plain-string secret. */
    override fun listSecrets(scope: SecretScope): List<SecretEntry> {
        checkNoFolder(scope)
        val current = auth.withKeys { read(it, scope) }
        val fields = current.fields
            ?: return listOf(entry(scope, scope.environment, current.plain.orEmpty()))
        return fields.entrySet().map { (key, value) -> entry(scope, key, textOf(value)) }
    }

    private fun entry(scope: SecretScope, key: String, value: String) =
        SecretEntry(key = key, value = SecretValue(value), path = "/", environment = scope.environment, id = key)

    // ---- Writes ------------------------------------------------------------------------------------------------------

    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        checkNoFolder(scope)
        checkKey(key)
        auth.withKeys { keys ->
            val current = readOrNull(keys, scope)
            if (current == null) {
                api.createSecret(keys, name(scope), JsonObject().apply { addProperty(key, value.reveal()) }.toString())
                return@withKeys
            }
            val fields = current.fields ?: throw plainString(current.name, "add keys to it")
            if (fields.has(key)) throw SecretProviderException(Kind.CONFIGURATION, "${current.name} already has a key $key; edit it instead")
            fields.addProperty(key, value.reveal())
            writeChecked(keys, current, fields.toString())
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Changes the key's value (a value left as it was keeps its JSON type); with a [newKey], renames it in the same write,
     * keeping its place, refused before writing when [newKey] is taken. Comments don't exist in Secrets Manager and are ignored.
     */
    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?, newKey: String?): WriteOutcome {
        checkNoFolder(scope)
        val rename = renameTarget(key, newKey)
        auth.withKeys { keys ->
            val current = read(keys, scope)
            val fields = current.fields
            if (fields == null) {
                // A plain-string secret has one key, named after the secret: its value can change, its name can't
                if (key != scope.environment) throw missing(current.name, key)
                if (rename != null) {
                    throw SecretProviderException(Kind.CONFIGURATION, "${current.name} holds a plain string: its only key is the secret's name and can't be renamed")
                }
                if (current.plain != value.reveal()) writeChecked(keys, current, value.reveal())
                return@withKeys
            }
            val old = fields.get(key) ?: throw missing(current.name, key)
            val unchanged = textOf(old) == value.reveal()
            if (rename == null) {
                if (unchanged) return@withKeys
                fields.addProperty(key, value.reveal())
                writeChecked(keys, current, fields.toString())
                return@withKeys
            }
            if (fields.has(rename)) {
                throw SecretProviderException(Kind.CONFIGURATION, "${current.name} already has a key $rename; choose another name or delete that one first")
            }
            val renamed = JsonObject()
            for ((name, element) in fields.entrySet()) {
                if (name == key) renamed.add(rename, if (unchanged) element.deepCopy() else JsonPrimitive(value.reveal())) else renamed.add(name, element.deepCopy())
            }
            writeChecked(keys, current, renamed.toString())
        }
        return WriteOutcome.APPLIED
    }

    /** Removes the key from the secret's JSON object (the last one leaves `{}`); the AWS secret itself is never deleted. */
    override fun deleteSecret(scope: SecretScope, key: String): WriteOutcome {
        checkNoFolder(scope)
        auth.withKeys { keys ->
            val current = read(keys, scope)
            val fields = current.fields
                ?: throw SecretProviderException(Kind.CONFIGURATION, "${current.name} holds a plain string, not keys; EnvBridge never deletes AWS secrets")
            if (!fields.has(key)) throw missing(current.name, key)
            fields.remove(key)
            writeChecked(keys, current, fields.toString())
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Copies the keys to the destination secret in one check-and-set write (values with their JSON types; the secret is
     * created if missing), then removes them from the source secret in another. A key already at the destination stops the
     * move before anything is written unless [overwrite]; a failed removal is reported, never a lost key.
     */
    override fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        checkNoFolder(source)
        checkNoFolder(destination)
        if (name(source) == name(destination)) {
            throw SecretProviderException(Kind.CONFIGURATION, "The keys are already in ${name(source)}; nothing was moved")
        }
        val keys = secrets.map { it.key }.distinct()
        if (keys.isEmpty()) return MoveResult(emptyList())
        auth.withKeys { credentials ->
            val from = read(credentials, source)
            val fromFields = from.fields ?: throw plainString(from.name, "move keys out of it")
            val to = readOrNull(credentials, destination)
            val toFields = to?.let { it.fields ?: throw plainString(it.name, "move keys into it") }
            // Check everything before writing anything, so a refused move changes nothing
            val missing = keys.filter { !fromFields.has(it) }
            if (missing.isNotEmpty()) {
                throw SecretProviderException(Kind.NOT_FOUND, "${from.name} has no ${missing.joinToString { "\"$it\"" }} any more; nothing was moved. Refresh and try again")
            }
            val conflicts = keys.filter { toFields?.has(it) == true }
            if (conflicts.isNotEmpty() && !overwrite) {
                throw SecretProviderException(
                    Kind.CONFIGURATION,
                    "${name(destination)} already has ${conflicts.joinToString { "\"$it\"" }}; nothing was moved. Tick Overwrite to replace their values",
                )
            }
            val target = toFields ?: JsonObject()
            keys.forEach { target.add(it, fromFields.get(it).deepCopy()) }
            if (to == null) api.createSecret(credentials, name(destination), target.toString()) else writeChecked(credentials, to, target.toString())
        }
        return try {
            auth.withKeys { credentials ->
                // Read again: the copy is safe at the destination, the source may have changed since
                val from = read(credentials, source)
                val fields = from.fields ?: throw plainString(from.name, "remove keys from it")
                keys.forEach { fields.remove(it) }
                writeChecked(credentials, from, fields.toString())
            }
            MoveResult(moved = keys)
        } catch (e: SecretProviderException) {
            MoveResult(moved = emptyList(), notRemoved = keys.associateWith { "copied, but not removed from ${name(source)}: ${e.message}" })
        }
    }

    /** Copies the keys (values as stored, JSON types kept) in one write; keys already at the destination fail one by one. */
    override fun copySecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope): CopyResult {
        checkNoFolder(source)
        checkNoFolder(destination)
        val keys = secrets.map { it.key }.distinct()
        val failed = linkedMapOf<String, String>()
        val copied = auth.withKeys { credentials ->
            val from = read(credentials, source)
            val fromFields = from.fields ?: throw plainString(from.name, "copy keys out of it")
            val to = readOrNull(credentials, destination)
            val toFields = to?.let { it.fields ?: throw plainString(it.name, "copy keys into it") }
            val copyable = keys.filter { key ->
                val reason = when {
                    !fromFields.has(key) -> "not in ${from.name} any more"
                    toFields?.has(key) == true -> "already in ${name(destination)}"
                    else -> null
                }
                reason?.let { failed[key] = it }
                reason == null
            }
            if (copyable.isNotEmpty()) {
                val target = toFields ?: JsonObject()
                copyable.forEach { target.add(it, fromFields.get(it).deepCopy()) }
                if (to == null) api.createSecret(credentials, name(destination), target.toString()) else writeChecked(credentials, to, target.toString())
            }
            copyable
        }
        return CopyResult(copied, emptyList(), failed)
    }

    /** Creates the secret `<project>/<slug>` holding `{}`; [name] becomes its description when it says more than the slug. */
    override fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment {
        val environment = slug.trim()
        if (environment.isEmpty() || '/' in environment || !environment.all(AwsSecretNames::isNameChar)) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "'$slug' can't be the last segment of an AWS secret name: use letters, digits and ${AwsSecretNames.NAME_PUNCTUATION.replace("/", "")}, without /",
            )
        }
        val secretName = AwsSecretNames.join(projectId, environment)
        auth.withKeys { api.createSecret(it, secretName, "{}", name.trim().takeIf { d -> d.isNotEmpty() && d != environment }) }
        return ProviderEnvironment(slug = environment, name = environment)
    }

    // ---- Reading and the check-and-set write -----------------------------------------------------------------------------

    /** A secret's current version: its JSON object, or (when it isn't a JSON object) its plain string. */
    private class Current(val name: String, val versionId: String, val fields: JsonObject?, val plain: String?) {
        override fun toString(): String = "Current($name, version=$versionId)"
    }

    private fun read(keys: AwsKeys, scope: SecretScope): Current {
        val value = api.getSecretValue(keys, name(scope))
        if (value.string == null) {
            throw SecretProviderException(Kind.CONFIGURATION, "${value.name} holds a binary value (SecretBinary), which EnvBridge doesn't read")
        }
        val json = runCatching { JsonParser.parseString(value.string) }.getOrNull()
        return if (json != null && json.isJsonObject) Current(value.name, value.versionId, json.asJsonObject, null)
        else Current(value.name, value.versionId, null, value.string)
    }

    private fun readOrNull(keys: AwsKeys, scope: SecretScope): Current? = try {
        read(keys, scope)
    } catch (e: SecretProviderException) {
        if (e.kind != Kind.NOT_FOUND) throw e
        null
    }

    /**
     * Makes [string] the secret's current value **only if [current] is still current**:
     * 1. `PutSecretValue` stores it as a new version labelled only [AwsApi.PENDING] (readers still get the old one);
     * 2. `UpdateSecretVersionStage` moves `AWSCURRENT` to it with `RemoveFromVersionId` = the version read, which AWS refuses
     *    when `AWSCURRENT` has moved meanwhile (someone else wrote): then nothing changed and the user is told to refresh;
     * 3. the helper label is removed again, whatever happened (best effort; an unlabelled version is cleaned up by AWS).
     */
    private fun writeChecked(keys: AwsKeys, current: Current, string: String) {
        val version = UUID.randomUUID().toString()
        api.putSecretValue(keys, current.name, string, version, listOf(AwsApi.PENDING))
        try {
            val moved = api.updateVersionStage(keys, current.name, AwsApi.CURRENT, moveTo = version, removeFrom = current.versionId)
            if (moved.isSuccess) return
            val failure = AwsHttp.failure(moved, "Saving ${current.name}")
            throw when {
                AwsHttp.errorCode(moved) == "InvalidParameterException" -> SecretProviderException(
                    Kind.CONFIGURATION,
                    "${current.name} was changed meanwhile, so nothing was changed. Refresh and try again (${AwsHttp.errorMessage(moved)})",
                )
                failure.kind == Kind.FORBIDDEN -> SecretProviderException(
                    Kind.FORBIDDEN,
                    "${failure.message}. Nothing was changed: EnvBridge needs secretsmanager:UpdateSecretVersionStage to write safely (check-and-set)",
                )
                else -> failure
            }
        } finally {
            runCatching { api.updateVersionStage(keys, current.name, AwsApi.PENDING, moveTo = null, removeFrom = version) }
        }
    }

    private fun name(scope: SecretScope): String = AwsSecretNames.join(scope.projectId, scope.environment)

    private fun missing(name: String, key: String) = SecretProviderException(Kind.NOT_FOUND, "$name has no key $key")

    private fun plainString(name: String, action: String) =
        SecretProviderException(Kind.CONFIGURATION, "$name holds a plain string, not key/value JSON, so EnvBridge doesn't $action")

    companion object {
        private fun checkNoFolder(scope: SecretScope) {
            if (SecretScope.normalizePath(scope.path) != "/") {
                throw SecretProviderException(Kind.NOT_FOUND, "AWS Secrets Manager has no folders (${scope.path}); keys live directly in a secret")
            }
        }

        private fun checkKey(key: String) {
            if (key.isBlank() || key != key.trim()) {
                throw SecretProviderException(Kind.CONFIGURATION, "'$key' isn't a usable key: it is empty or starts or ends with a blank")
            }
        }

        /** A JSON value as text: strings as they are, `null` as empty, anything else (numbers, objects) as its JSON. */
        fun textOf(value: JsonElement?): String = when {
            value == null || value is JsonNull -> ""
            value is JsonPrimitive && value.isString -> value.asString
            else -> value.toString()
        }
    }
}
