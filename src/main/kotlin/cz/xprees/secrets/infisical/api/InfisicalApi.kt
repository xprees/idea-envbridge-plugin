package cz.xprees.secrets.infisical.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cz.xprees.secrets.core.auth.Jwt
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.CreateOutcome
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind

/**
 * The Infisical REST endpoints the plugin uses (documented in the Infisical API reference), with response parsing.
 * Stateless: the caller passes the access token.
 */
class InfisicalApi(val http: InfisicalHttp) {

    // ---- Secrets ---------------------------------------------------------------------------------------------------

    /** `GET /api/v1/projects?type=secret-manager`: the Secrets Management projects the caller can access. */
    fun listProjects(token: String): List<ProviderProject> {
        val response = http.get("/api/v1/projects", mapOf("type" to "secret-manager"), bearer = token)
        if (!response.isSuccess) throw InfisicalHttp.failure(response, "Listing projects")
        return response.json().obj().array("projects").mapNotNull { it.asObjectOrNull()?.let(::parseProject) }
    }

    /** `GET /api/v2/folders`: the direct subfolders of the scope's folder. */
    fun listFolders(token: String, scope: SecretScope): List<SecretFolder> {
        val response = http.get(
            "/api/v2/folders",
            mapOf("projectId" to scope.projectId, "environment" to scope.environment, "path" to scope.path),
            bearer = token,
        )
        if (!response.isSuccess) throw InfisicalHttp.failure(response, "Listing folders of ${scope.environment}:${scope.path}")
        return response.json().obj().array("folders").mapNotNull { element ->
            val name = element.asObjectOrNull()?.string("name") ?: return@mapNotNull null
            SecretFolder(name, SecretScope.normalizePath("${scope.path}/$name"))
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * `GET /api/v4/secrets`: the secrets of a scope with values, references expanded, personal overrides applied and imports
     * merged the way the Infisical CLI merges them (secrets of the folder win; among imports, later ones win).
     */
    fun listSecrets(token: String, scope: SecretScope): List<SecretEntry> {
        val response = http.get(
            "/api/v4/secrets",
            mapOf(
                "projectId" to scope.projectId,
                "environment" to scope.environment,
                "secretPath" to scope.path,
                "recursive" to scope.recursive.toString(),
                "includeImports" to "true",
                "expandSecretReferences" to "true",
                "viewSecretValue" to "true",
                "includePersonalOverrides" to "true",
            ),
            bearer = token,
        )
        if (!response.isSuccess) throw InfisicalHttp.failure(response, "Reading secrets of ${scope.environment}:${scope.path}")
        return parseSecrets(response.json().obj(), scope)
    }

    /**
     * `POST /api/v4/secrets/{name}`: creates a shared secret in the folder of [scope]. With a change policy on the folder,
     * Infisical opens a change request instead (answered with `approval`).
     */
    fun createSecret(token: String, scope: SecretScope, key: String, value: SecretValue, comment: String): CreateOutcome {
        val body = JsonObject().apply {
            addProperty("projectId", scope.projectId)
            addProperty("environment", scope.environment)
            addProperty("secretPath", scope.path)
            addProperty("secretValue", value.reveal())
            if (comment.isNotBlank()) addProperty("secretComment", comment)
            addProperty("type", "shared")
        }
        val response = http.postJson("/api/v4/secrets/${encodePath(key)}", body.toString(), bearer = token)
        if (!response.isSuccess) {
            val failure = InfisicalHttp.failure(response, "Creating $key in ${scope.environment}:${scope.path}")
            throw if (failure.kind == Kind.FORBIDDEN) {
                SecretProviderException(Kind.FORBIDDEN, "Not allowed to create secrets in ${scope.environment}:${scope.path}")
            } else {
                failure
            }
        }
        val o = runCatching { response.json().obj() }.getOrNull()
        return if (o?.get("approval")?.isJsonObject == true && o.get("secret")?.isJsonObject != true) CreateOutcome.APPROVAL_REQUESTED else CreateOutcome.CREATED
    }

    // ---- Authentication --------------------------------------------------------------------------------------------

    /** Checks that [token] is a valid user session on this instance (`POST /api/v1/auth/checkAuth`). */
    fun checkUserSession(token: String) {
        val response = http.postJson("/api/v1/auth/checkAuth", "{}", bearer = token)
        if (!response.isSuccess) throw InfisicalHttp.failure(response, "Checking the session")
    }

    /**
     * Renews a browser (user) session with its refresh token (`POST /api/v1/auth/token`, refresh token in the `jid`
     * cookie). Returns the new session; the refresh token is replaced when the instance rotates it.
     */
    fun refreshUserSession(session: TokenSet): TokenSet {
        val refresh = session.refreshToken ?: throw SecretProviderException(Kind.AUTH_REQUIRED, "The session expired; sign in again")
        val response = http.postJson("/api/v1/auth/token", "{}", cookies = mapOf("jid" to refresh))
        if (!response.isSuccess) {
            throw SecretProviderException(Kind.AUTH_REQUIRED, "The session expired and could not be renewed; sign in again")
        }
        val token = response.json().obj().string("token")
            ?: throw SecretProviderException(Kind.SERVER, "Renewing the session: unexpected response")
        return session.copy(
            accessToken = token,
            refreshToken = response.cookie("jid") ?: refresh,
            expiresAt = Jwt.expiresAt(token),
        )
    }

    /** Ends a user session on the server (`DELETE /api/v2/users/me/sessions/{id}`). Best effort. */
    fun revokeUserSession(token: String) {
        val sessionId = Jwt.stringClaim(token, "tokenVersionId") ?: return
        runCatching { http.delete("/api/v2/users/me/sessions/${encodePath(sessionId)}", bearer = token) }
    }

    /** Machine identity sign-in (`POST /api/v1/auth/universal-auth/login`). */
    fun universalAuthLogin(clientId: String, clientSecret: String, organizationSlug: String?): TokenSet {
        val body = JsonObject().apply {
            addProperty("clientId", clientId)
            addProperty("clientSecret", clientSecret)
            organizationSlug?.let { addProperty("organizationSlug", it) }
        }
        val response = http.postJson("/api/v1/auth/universal-auth/login", body.toString())
        if (!response.isSuccess) {
            val failure = InfisicalHttp.failure(response, "Machine identity sign-in")
            // A wrong client ID or secret is answered with 401: that is a configuration problem, not an expired session
            throw if (failure.kind == Kind.AUTH_REQUIRED) SecretProviderException(Kind.CONFIGURATION, failure.message!!) else failure
        }
        val o = response.json().obj()
        val token = o.string("accessToken") ?: throw SecretProviderException(Kind.SERVER, "Machine identity sign-in: unexpected response")
        return TokenSet(
            accessToken = token,
            expiresAt = o.long("expiresIn")?.let { System.currentTimeMillis() / 1000 + it } ?: Jwt.expiresAt(token),
            account = "Machine identity $clientId",
        )
    }

    /**
     * OAuth 2.0 token endpoint (`POST /api/v1/oauth/token`), client authenticated with HTTP Basic.
     * [form] holds the grant (authorization_code + code + redirect_uri + code_verifier, or refresh_token).
     */
    fun oauthToken(clientId: String, clientSecret: String, form: Map<String, String>, previous: TokenSet? = null): TokenSet {
        val response = http.postForm("/api/v1/oauth/token", form, basic = clientId to clientSecret)
        if (!response.isSuccess) {
            val error = runCatching { response.json().obj().string("error") }.getOrNull()
            val kind = if (error == "invalid_grant" || response.status == 401) Kind.AUTH_REQUIRED else Kind.CONFIGURATION
            val detail = runCatching { response.json().obj().string("error_description") }.getOrNull()?.take(200)
            throw SecretProviderException(kind, "OAuth sign-in failed (${error ?: "HTTP ${response.status}"})${detail?.let { ": $it" }.orEmpty()}")
        }
        val o = response.json().obj()
        val token = o.string("access_token") ?: throw SecretProviderException(Kind.SERVER, "OAuth: no access token in the response")
        return TokenSet(
            accessToken = token,
            refreshToken = o.string("refresh_token") ?: previous?.refreshToken,
            expiresAt = o.long("expires_in")?.let { System.currentTimeMillis() / 1000 + it } ?: Jwt.expiresAt(token),
            account = previous?.account ?: Jwt.stringClaim(token, "email") ?: "OAuth ($clientId)",
        )
    }

    companion object {
        fun parseProject(o: JsonObject): ProviderProject? {
            val id = o.string("id") ?: o.string("_id") ?: return null
            val environments = o.array("environments").mapNotNull { element ->
                val env = element.asObjectOrNull() ?: return@mapNotNull null
                val slug = env.string("slug") ?: return@mapNotNull null
                ProviderEnvironment(slug = slug, name = env.string("name") ?: slug, id = env.string("id") ?: slug)
            }
            return ProviderProject(
                id = id,
                name = o.string("name") ?: id,
                slug = o.string("slug") ?: id,
                environments = environments,
                organizationId = o.string("orgId"),
            )
        }

        fun parseSecrets(o: JsonObject, scope: SecretScope): List<SecretEntry> {
            val result = LinkedHashMap<String, SecretEntry>()
            for (element in o.array("secrets")) {
                val entry = element.asObjectOrNull()?.let { parseSecret(it, scope, imported = false) } ?: continue
                val existing = result[entry.key]
                when {
                    existing == null -> result[entry.key] = entry
                    // A personal override wins over the shared secret of the same folder, like in the CLI
                    entry.origin == SecretOrigin.PERSONAL && existing.origin != SecretOrigin.PERSONAL && existing.path == entry.path ->
                        result[entry.key] = entry
                    // Recursive listings can have the same key in several folders: the first (shallowest) wins, like the CLI
                    else -> Unit
                }
            }
            for (import in o.array("imports").reversed()) {
                val importObject = import.asObjectOrNull() ?: continue
                val importScope = SecretScope(
                    projectId = scope.projectId,
                    environment = importObject.string("environment") ?: scope.environment,
                    path = SecretScope.normalizePath(importObject.string("secretPath")),
                )
                for (element in importObject.array("secrets")) {
                    val entry = element.asObjectOrNull()?.let { parseSecret(it, importScope, imported = true) } ?: continue
                    result.putIfAbsent(entry.key, entry)
                }
            }
            return result.values.toList()
        }

        private fun parseSecret(o: JsonObject, scope: SecretScope, imported: Boolean): SecretEntry? {
            val key = o.string("secretKey") ?: return null
            val hidden = o.bool("secretValueHidden") == true
            return SecretEntry(
                key = key,
                value = SecretValue(if (hidden) "" else o.string("secretValue").orEmpty()),
                comment = o.string("secretComment").orEmpty(),
                path = SecretScope.normalizePath(o.string("secretPath") ?: scope.path),
                environment = o.string("environment") ?: scope.environment,
                origin = when {
                    imported -> SecretOrigin.IMPORTED
                    o.string("type") == "personal" -> SecretOrigin.PERSONAL
                    else -> SecretOrigin.LOCAL
                },
                valueHidden = hidden,
            )
        }

        private fun encodePath(segment: String) = java.net.URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

        private fun JsonElement.obj(): JsonObject = asObjectOrNull()
            ?: throw SecretProviderException(Kind.SERVER, "The instance sent an unexpected response")

        private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

        private fun JsonObject.array(name: String): List<JsonElement> =
            get(name)?.takeIf { it.isJsonArray }?.let { (it as JsonArray).toList() }.orEmpty()

        private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

        private fun JsonObject.bool(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
    }
}
