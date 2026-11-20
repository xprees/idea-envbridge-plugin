package cz.xprees.secrets.doppler

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind

/**
 * The Doppler API v3 calls the plugin uses. Every call takes the bearer token explicitly; nothing here stores it.
 *
 * Request and response shapes follow the public API reference (docs.doppler.com/reference) and the Doppler CLI as far as they
 * were known when this was written; see `docs/DESIGN.md` ("Fourth provider: Doppler") for which details are assumptions.
 */
class DopplerApi(val http: DopplerHttp) {

    /** What `GET /v3/me` says about a token. */
    data class Me(
        /** The token's name (CLI and personal tokens), or null. */
        val name: String?,
        /** `personal`, `cli`, `service`, `service_account`, … as Doppler reports it, or null. */
        val type: String?,
        val workplaceSlug: String?,
        val workplaceName: String?,
        val userEmail: String?,
        /** Project and config of a service token, when Doppler reports them. */
        val project: String?,
        val config: String?,
    ) {
        /** Short account text for the UI: the user's email or the token's name, and the workplace. */
        fun describe(fallback: String): String {
            val who = userEmail ?: name ?: fallback
            return if (workplaceName != null && workplaceName != who) "$who ($workplaceName)" else who
        }
    }

    data class Project(val slug: String, val name: String)

    data class Config(
        val name: String,
        val environment: String,
        /** Root config of its environment (`dev`), not a branch config (`dev_feature`). */
        val root: Boolean,
        val locked: Boolean,
    )

    /** One secret as listed: the computed value (Doppler references resolved), the raw value, and its note. */
    data class Secret(val name: String, val computed: String?, val raw: String?, val note: String, val hidden: Boolean)

    /** What `POST /v3/auth/cli/generate` returned: the code the user confirms, the code to poll with, and where to sign in. */
    class AuthCode(val code: String, val pollingCode: String, val authUrl: String, val pollIntervalMs: Long?) {
        override fun toString(): String = "AuthCode(code=$code, authUrl=$authUrl)"
    }

    /** One poll of the browser sign-in: still pending, or the token. [toString] hides the token. */
    sealed class Poll {
        data object Pending : Poll()

        class Authorized(val token: String, val name: String?, val workplaceSlug: String?, val workplaceName: String?) : Poll() {
            override fun toString(): String = "Authorized(name=$name, workplace=$workplaceSlug)"
        }
    }

    // ---- Account ---------------------------------------------------------------------------------------------------

    fun me(token: String): Me {
        val response = http.send("GET", "/v3/me", token)
        if (!response.isSuccess) throw DopplerHttp.failure(response, "Checking the Doppler token")
        val o = response.json().asJsonObject
        val workplace = o.obj("workplace")
        return Me(
            name = o.str("name"),
            type = o.str("type") ?: o.str("type_"),
            workplaceSlug = workplace?.str("slug") ?: workplace?.str("id"),
            workplaceName = workplace?.str("name"),
            userEmail = o.obj("user")?.str("email"),
            project = o.str("project") ?: o.obj("project")?.str("slug"),
            config = o.str("config") ?: o.obj("config")?.str("name"),
        )
    }

    // ---- Browser sign-in (the Doppler CLI's flow) --------------------------------------------------------------------

    /** Starts a browser sign-in. [hostname] names the token in the user's Doppler token list. */
    fun generateAuthCode(hostname: String, os: String, arch: String): AuthCode {
        val body = JsonObject().apply {
            addProperty("hostname", hostname)
            addProperty("version", "envbridge")
            addProperty("os", os)
            addProperty("arch", arch)
        }.toString()
        // The current CLI calls generate_2 (with a separate polling code); generate is the older form of the same call
        var response = http.send("POST", "/v3/auth/cli/generate_2", json = body)
        if (response.status == 404 || response.status == 405) response = http.send("POST", "/v3/auth/cli/generate", json = body)
        if (!response.isSuccess) throw DopplerHttp.failure(response, "Starting the Doppler sign-in")
        val o = response.json().asJsonObject
        val code = o.str("code") ?: throw SecretProviderException(Kind.SERVER, "Doppler's sign-in answer has no code")
        val url = o.str("auth_url") ?: throw SecretProviderException(Kind.SERVER, "Doppler's sign-in answer has no address")
        return AuthCode(
            code = code,
            pollingCode = o.str("polling_code") ?: code,
            authUrl = url,
            pollIntervalMs = o.get("poll_interval")?.takeIf { it.isJsonPrimitive }?.asDouble?.let { (it * 1000).toLong() },
        )
    }

    /**
     * Asks whether the browser sign-in of [pollingCode] is done. Pending while the user hasn't confirmed (Doppler answers
     * 409, or a success without a token); a refused, expired or unknown code fails with AUTH_REQUIRED.
     */
    fun pollAuthCode(pollingCode: String): Poll {
        val response = http.send("POST", "/v3/auth/cli/authorize", json = JsonObject().apply { addProperty("code", pollingCode) }.toString())
        if (response.status == 409 || response.status == 202 || response.status == 425) return Poll.Pending
        if (!response.isSuccess) {
            val messages = DopplerHttp.messages(response)
            if (response.status == 400 && PENDING_HINTS.any { messages.contains(it, ignoreCase = true) }) return Poll.Pending
            if (response.status == 429 || response.status >= 500) throw DopplerHttp.failure(response, "Waiting for the Doppler sign-in")
            throw SecretProviderException(
                Kind.AUTH_REQUIRED,
                "The Doppler sign-in was declined or the code expired" + if (messages.isEmpty()) "" else ": $messages",
            )
        }
        val o = response.json().asJsonObject
        val token = o.str("token") ?: return Poll.Pending
        val workplace = o.obj("workplace")
        return Poll.Authorized(token, o.str("name"), workplace?.str("slug") ?: workplace?.str("id"), workplace?.str("name"))
    }

    /** Revokes [token] on the server (sign-out of a browser session). */
    fun revoke(token: String) {
        val response = http.send("POST", "/v3/auth/revoke", token, json = JsonObject().apply { addProperty("token", token) }.toString())
        // Already revoked or expired: nothing left to do
        if (!response.isSuccess && response.status != 401) throw DopplerHttp.failure(response, "Revoking the Doppler token")
    }

    // ---- Projects and configs ----------------------------------------------------------------------------------------

    fun projects(token: String): List<Project> = pages("Listing Doppler projects") { page ->
        http.send("GET", "/v3/projects", token, mapOf("page" to page.toString(), "per_page" to PER_PAGE.toString()))
    }.flatMap { o ->
        o.array("projects").mapNotNull { p ->
            val project = p.asJsonObject
            val slug = project.str("slug") ?: project.str("id") ?: project.str("name") ?: return@mapNotNull null
            Project(slug, project.str("name") ?: slug)
        }
    }

    fun configs(token: String, project: String): List<Config> = pages("Listing configs of $project") { page ->
        http.send("GET", "/v3/configs", token, mapOf("project" to project, "page" to page.toString(), "per_page" to PER_PAGE.toString()))
    }.flatMap { o ->
        o.array("configs").mapNotNull { c ->
            val config = c.asJsonObject
            val name = config.str("name") ?: config.str("slug") ?: return@mapNotNull null
            Config(
                name = name,
                environment = config.str("environment") ?: name.substringBefore('_'),
                root = config.get("root")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: !name.contains('_'),
                locked = config.get("locked")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }

    /** Creates the branch config [name] of [environment]. */
    fun createConfig(token: String, project: String, environment: String, name: String): Config {
        val body = JsonObject().apply {
            addProperty("project", project)
            addProperty("environment", environment)
            addProperty("name", name)
        }.toString()
        val response = http.send("POST", "/v3/configs", token, json = body)
        if (!response.isSuccess) throw DopplerHttp.failure(response, "Creating config $name in $project")
        return Config(name = name, environment = environment, root = false, locked = false)
    }

    // ---- Secrets -----------------------------------------------------------------------------------------------------

    /**
     * Secrets of [config] in [project]. Null project and config: those of the token (a service token is bound to one config).
     * Values are Doppler's computed ones (references `${…}` resolved by the server); dynamic secrets are left out.
     */
    fun secrets(token: String, project: String?, config: String?): List<Secret> {
        val response = http.send(
            "GET",
            "/v3/configs/config/secrets",
            token,
            mapOf("project" to project, "config" to config, "include_dynamic_secrets" to "false"),
        )
        if (!response.isSuccess) throw DopplerHttp.failure(response, if (project != null) "Reading $project/$config" else "Reading secrets")
        val secrets = response.json().asJsonObject.obj("secrets") ?: return emptyList()
        return secrets.entrySet().map { (name, value) ->
            val o = value.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val computed = o.str("computed")
            Secret(
                name = name,
                computed = computed,
                raw = o.str("raw"),
                note = o.str("note").orEmpty(),
                hidden = computed == null || o.str("computedVisibility") == "restricted",
            )
        }
    }

    /** Sets [secrets] (name -> value) in [config], creating or changing each. */
    fun setSecrets(token: String, project: String, config: String, secrets: Map<String, String>) {
        val body = JsonObject().apply {
            addProperty("project", project)
            addProperty("config", config)
            add("secrets", JsonObject().apply { secrets.forEach { (k, v) -> addProperty(k, v) } })
        }.toString()
        val response = http.send("POST", "/v3/configs/config/secrets", token, json = body)
        if (!response.isSuccess) {
            throw DopplerHttp.failure(response, "Saving ${secrets.keys.joinToString()} in $project/$config")
        }
    }

    fun deleteSecret(token: String, project: String, config: String, name: String) {
        val response = http.send("DELETE", "/v3/configs/config/secret", token, mapOf("project" to project, "config" to config, "name" to name))
        if (!response.isSuccess) throw DopplerHttp.failure(response, "Deleting $name from $project/$config")
    }

    /** Sets the note of secret [name] (notes belong to the project: every config shows the same one). */
    fun setNote(token: String, project: String, name: String, note: String) {
        val body = JsonObject().apply {
            addProperty("project", project)
            addProperty("secret", name)
            addProperty("note", note)
        }.toString()
        val response = http.send("POST", "/v3/projects/project/note", token, mapOf("project" to project), json = body)
        if (!response.isSuccess) throw DopplerHttp.failure(response, "Saving the note of $name")
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    /** Every page of a paginated listing (a page with fewer than [PER_PAGE] entries is the last), at most [MAX_PAGES]. */
    private fun pages(what: String, fetch: (Int) -> DopplerHttp.Response): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        for (page in 1..MAX_PAGES) {
            val response = fetch(page)
            if (!response.isSuccess) throw DopplerHttp.failure(response, what)
            val o = response.json().asJsonObject
            result += o
            val size = o.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray?.size() ?: 0
            if (size < PER_PAGE) break
        }
        return result
    }

    companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 20

        private val PENDING_HINTS = listOf("pending", "not yet", "waiting")

        private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
    }
}
