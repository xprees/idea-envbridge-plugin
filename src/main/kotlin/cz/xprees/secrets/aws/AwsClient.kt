package cz.xprees.secrets.aws

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.logger
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.RotationInfo
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.CopyResult
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.renameTarget
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.util.UUID

/**
 * [SecretsClient] for one AWS connection. Secret names map to projects and environments through the connection's
 * [AwsNameLayout]. Every write is a read-modify-write of one secret's JSON object made safe by a real check-and-set (see
 * [writeChecked]); AWS secrets themselves are never deleted.
 *
 * Reads try to make few billed requests (see `docs/DESIGN.md`, "Fewer requests"): values are kept in memory ([AwsMemory]) with
 * the version they are, a read the last listing shows unchanged needs no request (unless current values are asked for), and
 * secrets that have to be read are read up to 20 at a time with `BatchGetSecretValue`, falling back to `GetSecretValue` where
 * that action is refused.
 */
class AwsClient(private val connection: SecretConnection) : SecretsClient {
    private val api by lazy { AwsApi(AwsHttp(connection.baseUrl, AwsSecretsManagerProvider.region(connection))) }
    private val prefix: String? = connection.option(AwsAuthMethods.OPTION_NAME_PREFIX)?.trim()
    private val layout: AwsNameLayout by lazy { AwsNameLayout.of(connection) }
    private val auth = AwsAuth(connection) { keys -> api.listSecrets(keys, prefix, null, 1) }
    private val id: String get() = connection.id

    override fun signIn(interaction: SignInInteraction): String = auth.signIn(interaction)

    override fun signOut() {
        auth.signOut()
        AwsMemory.forgetConnection(id)
    }

    override fun hasCredentials(): Boolean = auth.hasCredentials()

    /**
     * Projects and environments from the secrets' names (per the name layout), each environment with the secret's description,
     * tags and rotation. Names that don't follow the layout are left out and counted ([AwsSecretsManagerProvider.listingNote]).
     */
    override fun listProjects(): List<ProviderProject> {
        val server = AwsNameLayout.serverPrefix(layout, prefix) ?: throw SecretProviderException(
            Kind.CONFIGURATION,
            "No secret name can start with both the name prefix '$prefix' and the name layout ${layout.template}; check the connection '${connection.name}'",
        )
        val secrets = auth.withKeys { api.allSecrets(it, server.ifEmpty { null }) }
            .filter { prefix.isNullOrEmpty() || it.name.startsWith(prefix) }
        val mapped = secrets.mapNotNull { listed -> layout.split(listed.name)?.let { (project, env) -> Triple(project, env, listed) } }
        AwsMemory.setListing(id, AwsMemory.Listing(AwsMemory.clock(), secrets.associateBy { it.name }, secrets.size - mapped.size))
        val byProject = mapped.groupBy { it.first }
        return byProject.keys.sortedWith(compareBy({ it == AwsSecretNames.NO_PREFIX }, { it.lowercase() })).map { project ->
            ProviderProject(
                id = project,
                name = if (project == AwsSecretNames.NO_PREFIX) SecretsBundle.message("aws.project.noPrefix") else project,
                slug = project,
                environments = byProject.getValue(project).distinctBy { it.second }.sortedBy { it.second.lowercase() }
                    .map { (_, env, listed) -> ProviderEnvironment(slug = env, name = env, metadata = metadata(listed)) },
            )
        }
    }

    /** AWS Secrets Manager has no folders. */
    override fun listFolders(scope: SecretScope): List<SecretFolder> = emptyList()

    /** The keys of the environment's secret, always current. */
    override fun listSecrets(scope: SecretScope): List<SecretEntry> = listSecrets(scope, fresh = true)

    /**
     * The keys of the environment's secret: its JSON object's fields, or one key for a plain-string secret. Each key's comment
     * is the secret's description. Unless [fresh], a value the last listing shows unchanged is answered from memory.
     */
    override fun listSecrets(scope: SecretScope, fresh: Boolean): List<SecretEntry> {
        checkNoFolder(scope)
        val name = name(scope)
        val value = cachedValue(name, fresh)?.value
            ?: auth.withKeys { keys -> fetch(keys, name, if (fresh) emptyList() else staleSiblings(name, scope.projectId)) }
        val current = parse(value)
        val comment = AwsMemory.listing(id)?.byName?.get(name)?.description.orEmpty()
        val fields = current.fields ?: return listOf(entry(scope, scope.environment, current.plain.orEmpty(), comment))
        return fields.entrySet().map { (key, value) -> entry(scope, key, textOf(value), comment) }
    }

    /** Reads the secrets of [scopes] ahead in `BatchGetSecretValue` requests of up to 20, for the reads that follow. */
    override fun prefetch(scopes: Collection<SecretScope>) {
        val names = scopes.filter { SecretScope.normalizePath(it.path) == "/" }.mapNotNull { runCatching { name(it) }.getOrNull() }.distinct()
        if (names.size < 2 || !AwsMemory.batchAvailable(id)) return
        try {
            auth.withKeys { keys ->
                for (chunk in names.chunked(AwsApi.BATCH_SIZE)) tryBatch(keys, chunk, readAhead = true) ?: break
            }
        } catch (e: SecretProviderException) {
            // The reads that follow report it
            LOG.debug("Reading ahead for '${connection.name}' failed: ${e.kind}")
        }
    }

    private fun entry(scope: SecretScope, key: String, value: String, comment: String) =
        SecretEntry(key = key, value = SecretValue(value), comment = comment, path = "/", environment = scope.environment, id = key)

    // ---- Reading with few requests ----------------------------------------------------------------------------------------

    /**
     * A value in memory usable for [name]: read ahead for this launch within the last [READ_AHEAD_MS] (used once), or, unless
     * [fresh], one whose version the last listing (no older than the cache time) still shows as current.
     */
    private fun cachedValue(name: String, fresh: Boolean): AwsMemory.CachedValue? {
        AwsMemory.takeReadAhead(id, name, READ_AHEAD_MS)?.let { return it }
        if (fresh) return null
        return listedUnchanged(name)
    }

    /** The value in memory for [name] when the last listing (no older than the cache time) shows it is still current. */
    private fun listedUnchanged(name: String): AwsMemory.CachedValue? {
        val cached = AwsMemory.value(id, name) ?: return null
        val listing = AwsMemory.listing(id)?.takeIf { AwsMemory.clock() - it.at <= cacheTtlMs() } ?: return null
        val listed = listing.byName[name] ?: return null
        return cached.takeIf { unchanged(listed, it) }
    }

    /** Whether the listing entry [listed] shows the same version as [cached] (by version ID, else by `LastChangedDate`). */
    private fun unchanged(listed: AwsApi.Listed, cached: AwsMemory.CachedValue): Boolean {
        listed.currentVersion?.let { return it == cached.value.versionId }
        return listed.lastChanged != null && listed.lastChanged == cached.listedChange
    }

    /** Other environments of [projectId] in the last listing whose values in memory are missing or out of date (at most 19). */
    private fun staleSiblings(name: String, projectId: String): List<String> {
        if (!AwsMemory.batchAvailable(id)) return emptyList()
        val listing = AwsMemory.listing(id)?.takeIf { AwsMemory.clock() - it.at <= cacheTtlMs() } ?: return emptyList()
        return listing.byName.keys.asSequence()
            .filter { it != name && layout.split(it)?.first == projectId && listedUnchanged(it) == null }
            .take(AwsApi.BATCH_SIZE - 1).toList()
    }

    /** Reads [name], together with [others] in one `BatchGetSecretValue` request when there are any and it is allowed. */
    private fun fetch(keys: AwsKeys, name: String, others: List<String>): AwsApi.Value {
        if (others.isNotEmpty() && AwsMemory.batchAvailable(id)) {
            val batch = tryBatch(keys, listOf(name) + others)
            if (batch != null) {
                batch.errors[name]?.let { throw it }
                batch.values[name]?.let { return it }
            }
        }
        return api.getSecretValue(keys, name).also { remember(it) }
    }

    /**
     * One `BatchGetSecretValue` request; its values are remembered. Null when the request failed in a way single reads may not
     * (denied, unsupported, throttled): single reads follow, and a denied or unsupported action isn't tried again for an hour.
     */
    private fun tryBatch(keys: AwsKeys, names: List<String>, readAhead: Boolean = false): AwsApi.Batch? = try {
        api.batchGetSecretValue(keys, names).also { batch -> batch.values.values.forEach { remember(it, readAhead) } }
    } catch (e: SecretProviderException) {
        if (e.kind == Kind.AUTH_REQUIRED || e.kind == Kind.NETWORK || e.kind == Kind.CANCELLED) throw e
        if (e.kind == Kind.FORBIDDEN || e.kind == Kind.CONFIGURATION || e.message.orEmpty().contains("HTTP 501")) {
            AwsMemory.markBatchRefused(id)
            LOG.info("BatchGetSecretValue isn't available for '${connection.name}' (${e.kind}); reading secrets one by one for an hour")
        }
        null
    }

    private fun remember(value: AwsApi.Value, readAhead: Boolean = false) {
        AwsMemory.putValue(id, value, AwsMemory.listing(id)?.byName?.get(value.name)?.lastChanged, readAhead)
    }

    private fun cacheTtlMs(): Long = runCatching { SecretsAppSettings.getInstance().state.cacheTtlSeconds * 1000L }.getOrDefault(DEFAULT_TTL_MS)

    // ---- Writes ------------------------------------------------------------------------------------------------------

    /**
     * Adds the key. A non-blank [comment] becomes the secret's description when this creates the secret, or when the secret has
     * no description yet (one description is shared by all keys of a secret, so an existing one is never replaced from here).
     */
    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        checkNoFolder(scope)
        checkKey(key)
        auth.withKeys { keys ->
            val current = readOrNull(keys, scope)
            if (current == null) {
                api.createSecret(keys, name(scope), JsonObject().apply { addProperty(key, value.reveal()) }.toString(), comment.trim())
                return@withKeys
            }
            val fields = current.fields ?: throw plainString(current.name, "add keys to it")
            if (fields.has(key)) throw SecretProviderException(Kind.CONFIGURATION, "${current.name} already has a key $key; edit it instead")
            fields.addProperty(key, value.reveal())
            writeChecked(keys, current, fields.toString())
            if (comment.isNotBlank() && api.describeSecret(keys, current.name).description.isBlank()) setDescription(keys, current.name, comment.trim())
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Changes the key's value (a value left as it was keeps its JSON type); with a [newKey], renames it in the same write,
     * keeping its place, refused before writing when [newKey] is taken. A [comment] is the secret's description, shared by all
     * its keys: it is set with `UpdateSecret` without a value, which makes no new version.
     */
    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?, newKey: String?): WriteOutcome {
        checkNoFolder(scope)
        val rename = renameTarget(key, newKey)
        auth.withKeys { keys ->
            val current = read(keys, scope)
            writeValue(keys, current, scope, key, value, rename)
            if (comment != null) setDescription(keys, current.name, comment.trim())
        }
        return WriteOutcome.APPLIED
    }

    private fun writeValue(keys: AwsKeys, current: Current, scope: SecretScope, key: String, value: SecretValue, rename: String?) {
        val fields = current.fields
        if (fields == null) {
            // A plain-string secret has one key, named after the secret: its value can change, its name can't
            if (key != scope.environment) throw missing(current.name, key)
            if (rename != null) {
                throw SecretProviderException(Kind.CONFIGURATION, "${current.name} holds a plain string: its only key is the secret's name and can't be renamed")
            }
            if (current.plain != value.reveal()) writeChecked(keys, current, value.reveal())
            return
        }
        val old = fields.get(key) ?: throw missing(current.name, key)
        val unchanged = textOf(old) == value.reveal()
        if (rename == null) {
            if (unchanged) return
            fields.addProperty(key, value.reveal())
            writeChecked(keys, current, fields.toString())
            return
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

    private fun setDescription(keys: AwsKeys, name: String, description: String) {
        api.updateDescription(keys, name, description)
        AwsMemory.updateListed(id, name) { it.copy(description = description) }
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
            if (to == null) api.createSecret(credentials, createName(destination), target.toString()) else writeChecked(credentials, to, target.toString())
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
                if (to == null) api.createSecret(credentials, createName(destination), target.toString()) else writeChecked(credentials, to, target.toString())
            }
            copyable
        }
        return CopyResult(copied, emptyList(), failed)
    }

    /** Creates the secret of [projectId] and [slug] (per the name layout) holding `{}`; [name] becomes its description when it says more than the slug. */
    override fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment {
        val environment = slug.trim()
        if (environment.isEmpty() || '/' in environment || !environment.all(AwsSecretNames::isNameChar)) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "'$slug' can't be the environment part of an AWS secret name: use letters, digits and ${AwsSecretNames.NAME_PUNCTUATION.replace("/", "")}, without /",
            )
        }
        val secretName = layout.joinForCreate(projectId, environment)
        auth.withKeys { api.createSecret(it, secretName, "{}", name.trim().takeIf { d -> d.isNotEmpty() && d != environment }) }
        return ProviderEnvironment(slug = environment, name = environment)
    }

    /**
     * Starts the secret's rotation now (`RotateSecret` with its stored rotation settings). Refused before calling AWS when the
     * last listing shows rotation isn't turned on.
     */
    override fun rotateSecret(scope: SecretScope) {
        checkNoFolder(scope)
        val name = name(scope)
        val listed = AwsMemory.listing(id)?.byName?.get(name)
        if (listed != null && !listed.rotationEnabled) {
            throw SecretProviderException(Kind.CONFIGURATION, "Rotation isn't turned on for $name; set it up in the AWS console first")
        }
        auth.withKeys { api.rotateSecret(it, name) }
        AwsMemory.removeValue(id, name)
    }

    // ---- Reading for writes and the check-and-set write -------------------------------------------------------------------

    /** A secret's current version: its JSON object, or (when it isn't a JSON object) its plain string. */
    private class Current(val name: String, val versionId: String, val fields: JsonObject?, val plain: String?) {
        override fun toString(): String = "Current($name, version=$versionId)"
    }

    private fun parse(value: AwsApi.Value): Current {
        if (value.string == null) {
            throw SecretProviderException(Kind.CONFIGURATION, "${value.name} holds a binary value (SecretBinary), which EnvBridge doesn't read")
        }
        val json = runCatching { JsonParser.parseString(value.string) }.getOrNull()
        return if (json != null && json.isJsonObject) Current(value.name, value.versionId, json.asJsonObject, null)
        else Current(value.name, value.versionId, null, value.string)
    }

    /** Always a request: a write must start from the version that is current now. */
    private fun read(keys: AwsKeys, scope: SecretScope): Current = parse(api.getSecretValue(keys, name(scope)))

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
     *    when `AWSCURRENT` has moved meanwhile (someone else wrote, or a rotation finished): then nothing changed and the user is
     *    told to refresh;
     * 3. the helper label is removed again, whatever happened (best effort; an unlabelled version is cleaned up by AWS).
     */
    private fun writeChecked(keys: AwsKeys, current: Current, string: String) {
        val version = UUID.randomUUID().toString()
        AwsMemory.removeValue(id, current.name)
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

    private fun name(scope: SecretScope): String = layout.join(scope.projectId, scope.environment)

    /** The name of a secret EnvBridge creates for [scope], checked to be read back as the same project and environment. */
    private fun createName(scope: SecretScope): String = layout.joinForCreate(scope.projectId, scope.environment)

    private fun missing(name: String, key: String) = SecretProviderException(Kind.NOT_FOUND, "$name has no key $key")

    private fun plainString(name: String, action: String) =
        SecretProviderException(Kind.CONFIGURATION, "$name holds a plain string, not key/value JSON, so EnvBridge doesn't $action")

    companion object {
        private val LOG = logger<AwsClient>()

        /** A value read ahead this recently (for a launch) serves one read as it is, even when current values are asked for. */
        const val READ_AHEAD_MS = 15_000L
        private const val DEFAULT_TTL_MS = 300_000L

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

        /** The tool window's details of a listed secret: description, tags and rotation (when it is or was on). */
        fun metadata(listed: AwsApi.Listed): SecretMetadata = SecretMetadata(
            description = listed.description,
            tags = listed.tags,
            rotation = if (listed.rotationEnabled || listed.lastRotated != null) RotationInfo(listed.rotationEnabled, listed.lastRotated, listed.nextRotation) else null,
        )

        /** A JSON value as text: strings as they are, `null` as empty, anything else (numbers, objects) as its JSON. */
        fun textOf(value: JsonElement?): String = when {
            value == null || value is JsonNull -> ""
            value is JsonPrimitive && value.isString -> value.asString
            else -> value.toString()
        }
    }
}
