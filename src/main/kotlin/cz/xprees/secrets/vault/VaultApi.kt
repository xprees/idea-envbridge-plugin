package cz.xprees.secrets.vault

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind

/** The Vault / OpenBao HTTP API calls the plugin uses (KV version 1 and 2, token and auth methods). */
class VaultApi(val http: VaultHttp) {

    /** A KV mount: its path without the trailing slash (`secret`, `kv/team`) and its KV version (1 or 2). */
    data class Mount(val path: String, val version: Int)

    /** What a LIST of a path returned: child folders (`app/`) and secrets (`db`), names without slashes. */
    data class Listing(val folders: List<String>, val secrets: List<String>)

    // ---- KV (version 1 and 2) --------------------------------------------------------------------------------------

    /**
     * KV mounts (version 1 and 2) the token can see in [namespace], from the endpoint the Vault UI uses (it lists only mounts
     * the token has some access to). Null when the server doesn't offer it (it has no compatibility promise).
     */
    fun kvMounts(token: String, namespace: String): List<Mount>? {
        val response = http.send("GET", "sys/internal/ui/mounts", token, namespace)
        if (response.status == 404 || response.status == 405) return null
        if (!response.isSuccess) {
            val failure = VaultHttp.failure(response, "Listing secret engines")
            if (failure.kind == Kind.FORBIDDEN) return null
            throw failure
        }
        val secret = response.json().asJsonObject.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("secret")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        return secret.entrySet().mapNotNull { (path, value) ->
            val mount = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            kvVersionOf(mount)?.let { Mount(path.trimEnd('/'), it) }
        }.sortedBy { it.path }
    }

    /**
     * The KV version of [mount] as the Vault CLI finds it (`vault kv` preflight): `GET sys/internal/ui/mounts/<mount>`, then
     * `data.options.version` ("2" = 2; missing, empty or "1" = 1). Null when the server doesn't say (the endpoint is missing,
     * the token may not read it, or the mount doesn't exist: Vault answers 403 then); CONFIGURATION when the mount is another
     * kind of secrets engine.
     */
    fun kvVersion(token: String, namespace: String, mount: String): Int? {
        val response = http.send("GET", "sys/internal/ui/mounts/${VaultHttp.path(mount)}", token, namespace)
        if (!response.isSuccess) {
            val failure = VaultHttp.failure(response, "Checking the secrets engine at $mount")
            // A refused token is renewed by the caller; anything else only means the server doesn't tell
            if (failure.kind == Kind.AUTH_REQUIRED) throw failure
            return null
        }
        val data = runCatching { response.json().asJsonObject.obj("data") }.getOrNull() ?: return null
        val type = data.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return kvVersionOf(data) ?: throw SecretProviderException(
            Kind.CONFIGURATION,
            "$mount is a $type secrets engine; EnvBridge reads KV secrets engines (version 1 or 2)",
        )
    }

    /**
     * Probes the KV version of [mount] when [kvVersion] can't tell, by reading [path] (listing the mount's root for an empty
     * path or a name a KV v2 engine reserves) the way KV v1 addresses it: an answer means version 1, a 404 with KV v2's
     * "invalid path for a versioned K/V secrets engine" warning version 2, a plain 404 (no such secret) version 1. Null when
     * the probe says nothing (refused, or no such mount).
     */
    fun probeKvVersion(token: String, namespace: String, mount: String, path: String): Int? {
        val segments = VaultHttp.path(path)
        val probeRoot = segments.isEmpty() || segments.substringBefore('/') in KV2_RESERVED
        val response = if (probeRoot) {
            http.send("GET", "${VaultHttp.path(mount)}/", token, namespace, query = mapOf("list" to "true"))
        } else {
            http.send("GET", "${VaultHttp.path(mount)}/$segments", token, namespace)
        }
        return when {
            response.isSuccess -> 1
            response.status != 404 -> null
            isVersionedKvAnswer(response) -> 2
            // A 404 with an error ("no handler for route") means there is no such mount here
            errors(response).isEmpty() -> 1
            else -> null
        }
    }

    /** Folders and secrets directly below [path] of [mount] (KV version [kv]); empty when there is nothing there. */
    fun list(token: String, namespace: String, mount: String, path: String, kv: Int = 2): Listing {
        val response = http.send("GET", location(mount, path, kv, "metadata"), token, namespace, query = mapOf("list" to "true"))
        if (response.status == 404) {
            checkStillV1(response, kv, mount)
            return Listing(emptyList(), emptyList())
        }
        if (!response.isSuccess) throw VaultHttp.failure(response, "Listing $mount${displayPath(path)}")
        val keys = response.json().asJsonObject.obj("data")?.get("keys")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
        return Listing(
            folders = keys.filter { it.endsWith('/') }.map { it.trimEnd('/') },
            secrets = keys.filterNot { it.endsWith('/') },
        )
    }

    /**
     * Fields of the secret at [path] of [mount] (KV v2: latest version). Null when there is no secret there (a folder only, or
     * the latest version is deleted). Values that aren't strings come back as JSON.
     */
    fun read(token: String, namespace: String, mount: String, path: String, kv: Int = 2): Map<String, String>? {
        if (VaultHttp.path(path).isEmpty()) return null
        val response = http.send("GET", location(mount, path, kv, "data"), token, namespace)
        if (response.status == 404) {
            checkStillV1(response, kv, mount)
            return null
        }
        if (!response.isSuccess) throw VaultHttp.failure(response, "Reading $mount${displayPath(path)}")
        val data = fieldsOf(response, kv)
        if (data == null || !data.isJsonObject) return null
        return data.asJsonObject.entrySet().associate { (key, value) -> key to textOf(value) }
    }

    /**
     * A secret as stored: its fields as JSON (types kept) and its current version (KV v2; 0 when it never existed, and always
     * 0 on KV v1, which has no versions).
     */
    data class Versioned(val fields: JsonObject, val version: Int) {
        override fun toString() = "Versioned(fields=${fields.keySet()}, version=$version)"
    }

    /**
     * The secret at [path] with its fields as they are stored (KV v2: the latest version), for a write that keeps every other
     * field (and its type) as it is. A deleted latest version reads as no fields, with its version number (writes must name it).
     */
    fun readVersioned(token: String, namespace: String, mount: String, path: String, kv: Int = 2): Versioned {
        val response = http.send("GET", location(mount, path, kv, "data"), token, namespace)
        if (!response.isSuccess && response.status != 404) throw VaultHttp.failure(response, "Reading $mount${displayPath(path)}")
        if (response.status == 404) checkStillV1(response, kv, mount)
        if (kv == 1) {
            val fields = if (response.isSuccess) fieldsOf(response, kv)?.takeIf { it.isJsonObject }?.asJsonObject else null
            return Versioned((fields ?: JsonObject()).deepCopy(), 0)
        }
        val data = runCatching { response.json().asJsonObject.obj("data") }.getOrNull()
        val version = data?.obj("metadata")?.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val fields = data?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return Versioned(fields.deepCopy(), version)
    }

    /**
     * Writes [fields] as the secret at [path]. KV v2: a new version, only if the current version is still [expectedVersion]
     * (check-and-set; 0 = only if it doesn't exist), so a change made meanwhile by someone else is refused, not overwritten.
     * KV v1: the secret is replaced as a whole ([expectedVersion] is ignored; KV v1 has no versions and no check-and-set).
     */
    fun write(token: String, namespace: String, mount: String, path: String, fields: JsonObject, expectedVersion: Int, kv: Int = 2) {
        val body = if (kv == 1) fields else JsonObject().apply {
            add("options", JsonObject().apply { addProperty("cas", expectedVersion) })
            add("data", fields)
        }
        val response = http.send("POST", location(mount, path, kv, "data"), token, namespace, json = body.toString())
        if (response.isSuccess) return
        if (response.status == 404) checkStillV1(response, kv, mount)
        val failure = VaultHttp.failure(response, "Writing $mount${displayPath(path)}")
        if (kv == 2 && response.status == 400 && failure.message.orEmpty().contains("check-and-set", ignoreCase = true)) {
            throw SecretProviderException(Kind.CONFIGURATION, "$mount${displayPath(path)} was changed meanwhile; refresh and try again")
        }
        throw failure
    }

    /**
     * Deletes the secret at [path]. KV v2: its latest version (a soft delete: older versions stay and can be restored). KV v1:
     * the secret itself, for good (KV v1 keeps nothing to restore).
     */
    fun deleteLatest(token: String, namespace: String, mount: String, path: String, kv: Int = 2) {
        val response = http.send("DELETE", location(mount, path, kv, "data"), token, namespace)
        if (response.status == 404) checkStillV1(response, kv, mount)
        if (!response.isSuccess && response.status != 404) throw VaultHttp.failure(response, "Deleting $mount${displayPath(path)}")
    }

    /**
     * The `custom_metadata` of the KV v2 secret at [path] (`GET <mount>/metadata/<path>`): string keys and values. Null when
     * there is no secret there (a folder only). Needs `read` on `<mount>/metadata/<path>`; a refusal is FORBIDDEN with what
     * the policy lacks.
     */
    fun customMetadata(token: String, namespace: String, mount: String, path: String): Map<String, String>? {
        if (VaultHttp.path(path).isEmpty()) return null
        val response = http.send("GET", location(mount, path, 2, "metadata"), token, namespace)
        if (response.status == 404) return null
        if (!response.isSuccess) {
            val failure = VaultHttp.failure(response, "Reading the metadata of $mount${displayPath(path)}")
            throw if (failure.kind == Kind.FORBIDDEN) {
                SecretProviderException(
                    Kind.FORBIDDEN,
                    "Not allowed to read the metadata of $mount${displayPath(path)} (the policy needs read on $mount/metadata${displayPath(path)})",
                )
            } else {
                failure
            }
        }
        val custom = runCatching { response.json().asJsonObject.obj("data")?.get("custom_metadata") }.getOrNull()
        return custom?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (key, value) -> key to textOf(value) }.orEmpty()
    }

    /**
     * Replaces the `custom_metadata` of the KV v2 secret at [path] (`POST <mount>/metadata/<path>` with only `custom_metadata`,
     * so its other settings stay). Needs `create`/`update` on `<mount>/metadata/<path>`; a refusal is FORBIDDEN with what the
     * policy lacks.
     */
    fun writeCustomMetadata(token: String, namespace: String, mount: String, path: String, metadata: Map<String, String>) {
        val body = JsonObject().apply {
            add("custom_metadata", JsonObject().apply { metadata.forEach { (k, v) -> addProperty(k, v) } })
        }
        val response = http.send("POST", location(mount, path, 2, "metadata"), token, namespace, json = body.toString())
        if (response.isSuccess) return
        val failure = VaultHttp.failure(response, "Saving the metadata of $mount${displayPath(path)}")
        throw if (failure.kind == Kind.FORBIDDEN) {
            SecretProviderException(
                Kind.FORBIDDEN,
                "Not allowed to change the metadata of $mount${displayPath(path)} (the policy needs update on $mount/metadata${displayPath(path)})",
            )
        } else {
            failure
        }
    }

    /** `<mount>/<prefix>/<path>` on KV v2 (prefix `data` or `metadata`), `<mount>/<path>` on KV v1. */
    private fun location(mount: String, path: String, kv: Int, v2Prefix: String): String =
        if (kv == 1) "${VaultHttp.path(mount)}/${VaultHttp.path(path)}" else "${VaultHttp.path(mount)}/$v2Prefix/${VaultHttp.path(path)}"

    /** KV v2 nests the fields in `data.data`, KV v1 sends them as `data`. */
    private fun fieldsOf(response: VaultHttp.Response, kv: Int): JsonElement? {
        val data = response.json().asJsonObject.obj("data") ?: return null
        return if (kv == 1) data else data.get("data")
    }

    /**
     * A KV v1 request answered with KV v2's "invalid path" warning: the mount was upgraded to version 2 since its version was
     * detected. Refused (the caller forgets the version) instead of reading as "no such secret".
     */
    private fun checkStillV1(response: VaultHttp.Response, kv: Int, mount: String) {
        if (kv == 1 && isVersionedKvAnswer(response)) {
            throw SecretProviderException(Kind.CONFIGURATION, "$mount is a KV version 2 engine now; refresh and try again")
        }
    }

    // ---- Tokens ----------------------------------------------------------------------------------------------------

    /** Checks [token] and describes it: the session to store. */
    fun lookupSelf(token: String, namespace: String): TokenSet {
        val response = http.send("GET", "auth/token/lookup-self", token, namespace)
        if (!response.isSuccess) {
            val failure = VaultHttp.failure(response, "Checking the token")
            // Every valid token may look itself up (default policy): a refusal means the token is invalid, revoked or
            // expired. OpenBao says only "permission denied" then, without Vault's "invalid token".
            if (failure.kind == Kind.FORBIDDEN) throw SecretProviderException(Kind.AUTH_REQUIRED, "Checking the token: the token is not valid any more")
            throw failure
        }
        val data = response.json().asJsonObject.obj("data") ?: throw SecretProviderException(Kind.SERVER, "Checking the token: no data")
        val ttl = data.get("ttl")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0
        return TokenSet(
            accessToken = token,
            expiresAt = if (ttl > 0) now() + ttl else null,
            account = data.get("display_name")?.takeIf { it.isJsonPrimitive }?.asString,
            extra = mapOf(EXTRA_RENEWABLE to (data.get("renewable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false).toString()),
        )
    }

    /** Extends [session]'s token (its own TTL). */
    fun renewSelf(session: TokenSet, namespace: String): TokenSet {
        val response = http.send("POST", "auth/token/renew-self", session.accessToken, namespace, json = "{}")
        if (!response.isSuccess) throw VaultHttp.failure(response, "Renewing the token")
        val auth = response.json().asJsonObject.obj("auth") ?: throw SecretProviderException(Kind.SERVER, "Renewing the token: no auth")
        val ttl = auth.get("lease_duration")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0
        return session.copy(
            expiresAt = if (ttl > 0) now() + ttl else null,
            extra = session.extra + (EXTRA_RENEWABLE to (auth.get("renewable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false).toString()),
        )
    }

    /** Revokes [token] on the server (sign-out); failures are ignored. */
    fun revokeSelf(token: String, namespace: String) {
        runCatching { http.send("POST", "auth/token/revoke-self", token, namespace, json = "{}") }
    }

    // ---- Auth methods ----------------------------------------------------------------------------------------------

    /** `POST auth/<mount>/login` (AppRole) or `auth/<mount>/login/<username>` (userpass, LDAP): the new token. */
    fun login(namespace: String, mount: String, username: String?, body: JsonObject): String {
        val path = "auth/${VaultHttp.path(mount)}/login" + (username?.let { "/" + VaultHttp.segment(it) } ?: "")
        val response = http.send("POST", path, namespace = namespace, json = body.toString())
        if (!response.isSuccess) {
            val failure = VaultHttp.failure(response, "Signing in")
            // A wrong password or secret is answered with 400 or 403: say so instead of "not signed in"
            if (response.status == 400 || response.status == 403 || failure.kind == Kind.AUTH_REQUIRED) {
                throw SecretProviderException(Kind.AUTH_REQUIRED, failure.message ?: "Signing in failed")
            }
            throw failure
        }
        return clientToken(response, "Signing in")
    }

    /** `POST auth/<mount>/oidc/auth_url`: the address of the identity provider's sign-in page. */
    fun oidcAuthUrl(namespace: String, mount: String, role: String?, redirectUri: String, clientNonce: String): String {
        val body = JsonObject().apply {
            role?.let { addProperty("role", it) }
            addProperty("redirect_uri", redirectUri)
            addProperty("client_nonce", clientNonce)
        }
        val response = http.send("POST", "auth/${VaultHttp.path(mount)}/oidc/auth_url", namespace = namespace, json = body.toString())
        if (!response.isSuccess) throw VaultHttp.failure(response, "Starting the OIDC sign-in")
        val url = response.json().asJsonObject.obj("data")?.get("auth_url")?.takeIf { it.isJsonPrimitive }?.asString
        if (url.isNullOrBlank()) {
            // Vault answers an empty auth_url when the redirect URI isn't allowed for the role
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "The OIDC role doesn't allow the redirect URI $redirectUri; add it to the role's allowed_redirect_uris",
            )
        }
        return url
    }

    /** `GET auth/<mount>/oidc/callback`: exchanges the identity provider's code for a token. */
    fun oidcCallback(namespace: String, mount: String, state: String, code: String, clientNonce: String): String {
        val response = http.send(
            "GET",
            "auth/${VaultHttp.path(mount)}/oidc/callback",
            namespace = namespace,
            query = mapOf("state" to state, "code" to code, "client_nonce" to clientNonce),
        )
        if (!response.isSuccess) throw VaultHttp.failure(response, "Completing the OIDC sign-in")
        return clientToken(response, "Completing the OIDC sign-in")
    }

    private fun clientToken(response: VaultHttp.Response, what: String): String =
        response.json().asJsonObject.obj("auth")?.get("client_token")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw SecretProviderException(Kind.SERVER, "$what: the server sent no token")

    companion object {
        /** The environment slug for "no namespace" (open-source Vault, or the root namespace). */
        const val ROOT_NAMESPACE = "root"

        const val EXTRA_RENEWABLE = "renewable"

        private fun now() = System.currentTimeMillis() / 1000

        private fun displayPath(path: String) = "/" + path.trim('/')

        /** Names a KV v2 engine gives meaning to below its mount: a KV v1-style probe of them could be answered by KV v2. */
        private val KV2_RESERVED = setOf("data", "metadata", "config", "delete", "undelete", "destroy", "subkeys", "detailed-metadata")

        /**
         * The KV version of a mount described by Vault (`type` and `options`), as the Vault CLI reads it: type `kv` (older
         * servers: `generic`) with `options.version` "2" is version 2, any other (or no) version is 1; null for other engines.
         */
        private fun kvVersionOf(mount: JsonObject): Int? {
            val type = mount.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            if (type != "kv" && type != "generic") return null
            // options is null for a mount created without any (an old KV v1 mount)
            val version = mount.obj("options")?.get("version")?.takeIf { it.isJsonPrimitive }?.asString
            return if (version == "2") 2 else 1
        }

        /** Vault's `errors` of a response (empty when there are none or the body isn't JSON). */
        private fun errors(response: VaultHttp.Response): List<String> = runCatching {
            JsonParser.parseString(response.body).asJsonObject.getAsJsonArray("errors")?.map { it.asString }
        }.getOrNull().orEmpty()

        /**
         * Whether a 404 is KV v2's answer to a path without `data/` or `metadata/` (its warning "Invalid path for a versioned
         * K/V secrets engine"): the mount is version 2.
         */
        private fun isVersionedKvAnswer(response: VaultHttp.Response): Boolean = runCatching {
            JsonParser.parseString(response.body).asJsonObject.getAsJsonArray("warnings")?.any {
                it.asString.contains("versioned K/V", ignoreCase = true)
            }
        }.getOrNull() == true

        /** The member [name] when it is an object (Vault sends `null` for absent parts, e.g. `"auth": null` on reads). */
        private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

        /** A field's value as text: strings as they are, anything else as JSON (null as empty). */
        fun textOf(value: JsonElement?): String = when {
            value == null || value is JsonNull -> ""
            value is JsonPrimitive && value.isString -> value.asString
            else -> value.toString()
        }
    }
}
