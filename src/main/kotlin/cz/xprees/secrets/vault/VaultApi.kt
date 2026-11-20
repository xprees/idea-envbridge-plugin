package cz.xprees.secrets.vault

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind

/** The Vault / OpenBao HTTP API calls the plugin uses (KV v2, token and auth methods). */
class VaultApi(val http: VaultHttp) {

    /** A KV v2 mount: its path without the trailing slash (`secret`, `kv/team`). */
    data class Mount(val path: String)

    /** What a LIST of a path returned: child folders (`app/`) and secrets (`db`), names without slashes. */
    data class Listing(val folders: List<String>, val secrets: List<String>)

    // ---- KV v2 -----------------------------------------------------------------------------------------------------

    /**
     * KV v2 mounts the token can see in [namespace], from the endpoint the Vault UI uses (it lists only mounts the token has
     * some access to). Null when the server doesn't offer it (it has no compatibility promise).
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
            val type = mount.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            // options is null for engines without any (cubbyhole, identity)
            val version = mount.get("options")?.takeIf { it.isJsonObject }?.asJsonObject?.get("version")?.takeIf { it.isJsonPrimitive }?.asString
            // Vault reports the KV v2 engine as type kv with version 2 (older servers: generic)
            Mount(path.trimEnd('/')).takeIf { (type == "kv" || type == "generic") && version == "2" }
        }.sortedBy { it.path }
    }

    /** Folders and secrets directly below [path] of [mount]; empty when there is nothing there. */
    fun list(token: String, namespace: String, mount: String, path: String): Listing {
        val response = http.send("GET", "${VaultHttp.path(mount)}/metadata/${VaultHttp.path(path)}", token, namespace, query = mapOf("list" to "true"))
        if (response.status == 404) return Listing(emptyList(), emptyList())
        if (!response.isSuccess) throw VaultHttp.failure(response, "Listing $mount${displayPath(path)}")
        val keys = response.json().asJsonObject.obj("data")?.get("keys")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
        return Listing(
            folders = keys.filter { it.endsWith('/') }.map { it.trimEnd('/') },
            secrets = keys.filterNot { it.endsWith('/') },
        )
    }

    /**
     * Fields of the secret at [path] of [mount] (latest version). Null when there is no secret there (a folder only, or the
     * latest version is deleted). Values that aren't strings come back as JSON.
     */
    fun read(token: String, namespace: String, mount: String, path: String): Map<String, String>? {
        if (VaultHttp.path(path).isEmpty()) return null
        val response = http.send("GET", "${VaultHttp.path(mount)}/data/${VaultHttp.path(path)}", token, namespace)
        if (response.status == 404) return null
        if (!response.isSuccess) throw VaultHttp.failure(response, "Reading $mount${displayPath(path)}")
        val data = response.json().asJsonObject.obj("data")?.get("data")
        if (data == null || !data.isJsonObject) return null
        return data.asJsonObject.entrySet().associate { (key, value) -> key to textOf(value) }
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
