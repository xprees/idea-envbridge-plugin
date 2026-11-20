package cz.xprees.secrets.onepassword

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.onepassword.OnePasswordJson.str
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Reads from a 1Password Connect server (`GET /v1/vaults`, `/v1/vaults/{id}/items`, `/v1/vaults/{id}/items/{id}`) and
 * writes items (`POST /v1/vaults/{id}/items`, `PUT /v1/vaults/{id}/items/{id}`) with the access token from the IDE's
 * password storage as a bearer token.
 *
 * Set up like the plugin's other HTTP clients: the IDE's proxy and trusted certificates, no redirects (they could carry
 * the token to another host), fixed timeouts, no bodies in logs. Vaults and items given by name are looked up by listing.
 */
class OnePasswordConnect(private val connection: SecretConnection) : OnePasswordBackend {
    private val baseUrl = connection.baseUrl.trimEnd('/')

    /** Vaults listed by this client (one per operation), for name lookups. */
    private var vaultCache: List<OpVault>? = null

    override fun signIn(interaction: SignInInteraction): String {
        interaction.status("Checking the Connect token…")
        vaults()
        return "1Password Connect (${baseUrl.substringAfter("://")})"
    }

    override fun vaults(): List<OpVault> = OnePasswordJson.vaults(get("v1/vaults", "Listing vaults")).also { vaultCache = it }

    override fun items(vault: String): List<OpItemSummary> {
        val id = vaultId(vault)
        return OnePasswordJson.items(get("v1/vaults/${segment(id)}/items", "Listing the items of vault $vault"))
    }

    override fun itemJson(vault: String, item: String): JsonObject {
        val vid = vaultId(vault)
        val what = "Reading item $item"
        if (looksLikeId(item)) {
            try {
                return obj(get("v1/vaults/${segment(vid)}/items/${segment(item)}", what), what)
            } catch (e: SecretProviderException) {
                if (e.kind != Kind.NOT_FOUND) throw e // else it may be a title that looks like an ID
            }
        }
        val itemId = pick(items(vid), item, { it.id }, { it.title }, "item", "vault $vault")
        return obj(get("v1/vaults/${segment(vid)}/items/${segment(itemId)}", what), what)
    }

    /** `POST /v1/vaults/{vaultId}/items` with the template (its `vault.id` set to the vault's ID). */
    override fun createItem(vault: String, template: JsonObject): JsonObject {
        val vid = vaultId(vault)
        val body = template.deepCopy().apply { add("vault", JsonObject().apply { addProperty("id", vid) }) }
        val what = "Creating item ${template.str("title").orEmpty()}"
        return obj(send("POST", "v1/vaults/${segment(vid)}/items", body, what), what)
    }

    /**
     * `PUT /v1/vaults/{vaultId}/items/{itemId}` with the whole item, including the `version` it was read with: a server
     * that sees a newer version answers 409, reported as "changed meanwhile".
     */
    override fun replaceItem(vault: String, item: JsonObject): JsonObject {
        val vid = item.getAsJsonObject("vault")?.str("id")?.takeIf { it.isNotBlank() } ?: vaultId(vault)
        val id = item.str("id")?.takeIf { it.isNotBlank() } ?: throw SecretProviderException(Kind.SERVER, "The item has no ID")
        val what = "Saving item ${item.str("title") ?: id}"
        return obj(send("PUT", "v1/vaults/${segment(vid)}/items/${segment(id)}", item, what), what)
    }

    private fun obj(json: JsonElement, what: String): JsonObject =
        json.takeIf { it.isJsonObject }?.asJsonObject ?: throw SecretProviderException(Kind.SERVER, "$what: the Connect server sent an unexpected response")

    private fun vaultId(vault: String): String {
        if (looksLikeId(vault) && vaultCache == null) return vault
        val vaults = vaultCache ?: vaults()
        return pick(vaults, vault, { it.id }, { it.name }, "vault", "this account")
    }

    /** The ID of the one entry whose ID is [name], else whose title is [name] (exactly, else ignoring case). */
    private fun <T> pick(entries: List<T>, name: String, id: (T) -> String, title: (T) -> String, what: String, where: String): String {
        entries.firstOrNull { id(it) == name }?.let { return id(it) }
        val exact = entries.filter { title(it) == name }
        val matches = exact.ifEmpty { entries.filter { title(it).equals(name, ignoreCase = true) } }
        return when (matches.size) {
            1 -> id(matches.single())
            0 -> throw SecretProviderException(Kind.NOT_FOUND, "\"$name\" isn't a $what in $where")
            else -> throw SecretProviderException(Kind.CONFIGURATION, "More than one $what is called \"$name\" in $where; name it by its ID")
        }
    }

    private fun token(): String =
        CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(OnePasswordAuthMethods.OPTION_CONNECT_TOKEN))?.takeIf { it.isNotBlank() }
            ?: throw SecretProviderException(Kind.AUTH_REQUIRED, "Connection '${connection.name}' has no Connect token; edit it in the settings")

    fun hasToken(): Boolean =
        CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(OnePasswordAuthMethods.OPTION_CONNECT_TOKEN))?.isNotBlank() == true

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            // HTTP/1.1: over plain http:// the default (HTTP/2) sends an h2c upgrade with every request, which breaks request
            // bodies on some servers (see InfisicalHttp); Connect servers are often plain http on localhost
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .proxy(ProxySelector.getDefault() ?: ProxySelector.of(null))
            .apply { runCatching { com.intellij.util.net.ssl.CertificateManager.getInstance().sslContext }.getOrNull()?.let(::sslContext) }
            .build()
    }

    private fun get(path: String, what: String): JsonElement = send("GET", path, null, what)

    /** Sends a request ([body] as JSON, never logged) and parses the answer. */
    private fun send(method: String, path: String, body: JsonObject?, what: String): JsonElement {
        val target = "$baseUrl/$path"
        val request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${token()}")
            .apply {
                if (body == null) method(method, HttpRequest.BodyPublishers.noBody())
                else header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            }
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            LOG.debug("Request to $baseUrl failed: ${e.javaClass.simpleName}")
            throw SecretProviderException(Kind.NETWORK, cz.xprees.secrets.core.provider.NetworkErrors.describe(baseUrl, e), e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SecretProviderException(Kind.CANCELLED, "Cancelled", e)
        }
        val text = response.body().orEmpty()
        if (response.statusCode() !in 200..299) throw failure(response.statusCode(), text, what)
        if (text.isBlank()) return com.google.gson.JsonNull.INSTANCE
        return try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw SecretProviderException(Kind.SERVER, "$what: the Connect server sent an unexpected response (not JSON)", e)
        }
    }

    companion object {
        private val LOG = logger<OnePasswordConnect>()
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 30_000L

        /** 1Password IDs: 26 lowercase letters and digits. */
        fun looksLikeId(value: String): Boolean = value.length == 26 && value.all { it in 'a'..'z' || it in '0'..'9' }

        private fun segment(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        /** Connect's errors are `{"status": 401, "message": "…"}`; the message is shown (shortened), never the rest. */
        fun failure(status: Int, body: String, what: String): SecretProviderException {
            val message = runCatching { JsonParser.parseString(body).asJsonObject.str("message") }.getOrNull()
                ?.replace(Regex("\\s+"), " ")?.take(300)
            val detail = message?.let { ": $it" }.orEmpty()
            val kind = when (status) {
                401 -> Kind.AUTH_REQUIRED
                403 -> Kind.FORBIDDEN
                404 -> Kind.NOT_FOUND
                in 300..499 -> Kind.CONFIGURATION
                else -> Kind.SERVER
            }
            val text = when {
                status in 300..399 -> "$what: the server redirected the request; check the Connect server URL"
                status == 401 -> "$what: the Connect token was refused$detail"
                status == 403 -> "$what: the Connect token may not do this$detail"
                status == 409 || status == 412 -> "$what: the item was changed meanwhile (in 1Password or by someone else); refresh and try again"
                else -> "$what failed (HTTP $status)$detail"
            }
            return SecretProviderException(kind, text)
        }
    }
}
