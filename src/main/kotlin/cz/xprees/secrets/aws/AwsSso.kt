package cz.xprees.secrets.aws

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.NetworkErrors
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Sign-in with **IAM Identity Center** (AWS SSO) inside the IDE, without the AWS CLI, as the AWS CLI's `aws sso login` does it
 * (the OAuth 2.0 device authorization grant):
 *
 * 1. `sso-oidc` `RegisterClient` (a public client with the scope `sso:account:access`, which makes AWS issue refresh tokens);
 * 2. `StartDeviceAuthorization` with the start URL; the IDE opens `verificationUriComplete` in the system browser and shows the
 *    user code, to be compared with the one the browser shows;
 * 3. `CreateToken` with the `device_code` grant, polled every `interval` seconds (five more after `slow_down`) until the user
 *    allows access, the code expires (`expired_token`) or access is denied;
 * 4. `sso` `GetRoleCredentials` (account ID and role name from the connection's options; when they aren't set,
 *    `ListAccounts` / `ListAccountRoles` take the only one or let the user pick, see [AwsSsoAssignments], and the pick is
 *    saved in the options) for temporary role credentials, kept **in memory** until five minutes before they expire and then fetched again with the
 *    stored access token, renewed with the refresh token (`refresh_token` grant) when it is about to expire.
 *
 * The access token, refresh token and the client registration (client ID and secret) are kept in the IDE's password storage
 * (the connection's session slot) only. When they can no longer be renewed, calls fail with AUTH_REQUIRED: sign in again.
 * Sign-out calls the portal's `Logout` and forgets everything. None of these endpoints is SigV4-signed: the OIDC calls are
 * unauthenticated JSON, the portal takes the access token in the `x-amz-sso_bearer_token` header. Tokens never go into URLs,
 * logs or exception messages. Shapes follow botocore's `sso-oidc` and `sso` service models (2019-06-10).
 */
class AwsSso(private val connection: SecretConnection, private val check: (AwsKeys) -> Unit) {
    private val startUrl: String get() = connection.option(AwsAuthMethods.OPTION_SSO_START_URL)?.trim().orEmpty()
    private val ssoRegion: String get() = connection.option(AwsAuthMethods.OPTION_SSO_REGION)?.trim()?.lowercase().orEmpty()
    private val api by lazy { Api(ssoRegion) }
    private val optionAccount: String? get() = connection.option(AwsAuthMethods.OPTION_SSO_ACCOUNT_ID)?.trim()?.takeIf { it.isNotEmpty() }
    private val optionRole: String? get() = connection.option(AwsAuthMethods.OPTION_SSO_ROLE_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    /** The role credentials in memory, if they are still valid and for the account and role the connection names now. */
    private fun cachedKeys(): AwsKeys? = cache[connection.id]?.takeIf { cached ->
        cached.valid() && (optionAccount == null || optionAccount == cached.account) && (optionRole == null || optionRole == cached.role)
    }?.keys

    /** Whether a session (tokens) is stored. */
    fun hasSession(): Boolean = loadSession() != null

    /** The role credentials: from memory, else fetched with the stored token (renewed when needed); AUTH_REQUIRED when signed out. */
    fun keys(): AwsKeys {
        val session = loadSession() ?: throw SecretProviderException(Kind.AUTH_REQUIRED, "Not signed in to '${connection.name}' (IAM Identity Center)")
        cachedKeys()?.let { return it }
        return roleCredentials(session)
    }

    /** Forgets [keys] from memory when they are the cached ones (AWS no longer accepted them). Returns whether they were. */
    fun drop(keys: AwsKeys): Boolean = cache[connection.id]?.takeIf { it.keys === keys }?.let { cache.remove(connection.id, it) } == true

    /**
     * The device authorization flow (see the class comment); returns a description of the account and role. When the
     * connection names no account or role and there are several, the user picks them ([SignInInteraction.choose]) and the
     * choice is saved in the connection's (non-secret) options, so later sign-ins don't ask.
     */
    fun signIn(interaction: SignInInteraction): String {
        checkSettings()
        val (registration, token) = deviceFlow(interaction)

        interaction.status("Getting the role credentials…")
        val choice = assignments(token.accessToken).choose(interaction, optionAccount, optionRole)
        val session = newSession(registration, token, choice.account, choice.role)
        val keys = api.getRoleCredentials(session.accessToken, choice.account, choice.role)
        check(keys)
        CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, session.toJson())
        cache[connection.id] = Cached(keys, choice.account, choice.role)
        if (choice.asked) rememberChoice(choice.account, choice.role)
        return "${keys.shortId} (IAM Identity Center, ${choice.role} @ ${choice.account}, ${AwsSecretsManagerProvider.region(connection)})"
    }

    /**
     * For the connection dialog's "Choose…" button: lists the accounts and then the chosen account's roles and lets the user
     * pick (the only one is taken without asking). Uses the stored token when it is still good (renewed with the refresh token
     * if needed), else signs in in the browser and stores the new session as [signIn] does. Returns the account ID and role
     * name options, or null when the user cancelled. Saves nothing in the settings: the dialog fills its fields.
     */
    fun chooseForSettings(interaction: SignInInteraction): Map<String, String>? {
        checkSettings()
        fun choose(token: String) = assignments(token).choose(interaction, null, null, currentAccount = optionAccount, currentRole = optionRole)
        val choice = try {
            val stored = storedToken()
            try {
                stored?.let(::choose)
            } catch (e: SecretProviderException) {
                // Revoked or expired early: sign in again below
                if (e.kind != Kind.AUTH_REQUIRED) throw e
                null
            } ?: run {
                val (registration, token) = deviceFlow(interaction)
                interaction.status("Listing your AWS accounts and roles…")
                choose(token.accessToken).also {
                    CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, newSession(registration, token, it.account, it.role).toJson())
                }
            }
        } catch (e: SecretProviderException) {
            if (e.kind == Kind.CANCELLED) return null
            throw e
        }
        return mapOf(AwsAuthMethods.OPTION_SSO_ACCOUNT_ID to choice.account, AwsAuthMethods.OPTION_SSO_ROLE_NAME to choice.role)
    }

    /** Registers a client and runs the device authorization grant until the user allows access in the browser. */
    private fun deviceFlow(interaction: SignInInteraction): Pair<Registration, Token> {
        interaction.status("Starting the IAM Identity Center sign-in…")
        val registration = api.registerClient()
        val device = api.startDeviceAuthorization(registration, startUrl)
        val verification = device.verificationUriComplete ?: device.verificationUri
        if (SecretConnection.normalizeBaseUrl(verification.substringBefore('?').substringBefore('#')) == null) {
            throw SecretProviderException(Kind.SERVER, "AWS sent a sign-in address that isn't https")
        }
        interaction.status("In your browser, check that AWS shows the code ${device.userCode}, then allow access")
        interaction.openBrowser(verification)

        var interval = pollIntervalOverrideMs ?: (device.intervalSeconds.coerceIn(1, 60) * 1000L)
        val deadline = System.currentTimeMillis() + device.expiresInSeconds.coerceIn(60, 3600) * 1000L
        var failures = 0
        while (true) {
            if (interaction.isCancelled()) throw cancelled()
            val result = try {
                api.createToken(registration, deviceCode = device.deviceCode).also { failures = 0 }
            } catch (e: SecretProviderException) {
                if (e.kind != Kind.NETWORK && e.kind != Kind.SERVER) throw e
                if (++failures >= MAX_POLL_FAILURES) throw e
                null
            }
            when (result) {
                is TokenResult.Issued -> return registration to result.token
                is TokenResult.Failed -> when (result.code) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += pollIntervalOverrideMs ?: SLOW_DOWN_MS
                    "expired_token" -> throw SecretProviderException(Kind.AUTH_REQUIRED, "The sign-in code expired before access was allowed; sign in again")
                    "access_denied" -> throw SecretProviderException(Kind.AUTH_REQUIRED, "Access was denied in the browser; sign in again to retry")
                    else -> throw SecretProviderException(Kind.AUTH_REQUIRED, "IAM Identity Center refused the sign-in (${result.code}${result.message.let { if (it.isEmpty()) "" else ": $it" }})")
                }
                null -> Unit
            }
            if (System.currentTimeMillis() >= deadline) throw SecretProviderException(Kind.AUTH_REQUIRED, "Access wasn't allowed in time; sign in again")
            sleep(interval, interaction)
        }
    }

    private fun newSession(registration: Registration, token: Token, account: String, role: String) = TokenSet(
        accessToken = token.accessToken,
        refreshToken = token.refreshToken,
        expiresAt = token.expiresAt,
        account = "$role @ $account",
        extra = mapOf(
            EXTRA_CLIENT_ID to registration.clientId,
            EXTRA_CLIENT_SECRET to registration.clientSecret,
            EXTRA_CLIENT_EXPIRES to registration.expiresAt.toString(),
            EXTRA_START_URL to startUrl,
            EXTRA_SSO_REGION to ssoRegion,
            EXTRA_ACCOUNT to account,
            EXTRA_ROLE to role,
        ),
    )

    /** The stored access token when it belongs to these settings and is still good (renewed when needed); else null. */
    private fun storedToken(): String? {
        var session = loadSession() ?: return null
        if (session.extra[EXTRA_START_URL] != startUrl || session.extra[EXTRA_SSO_REGION] != ssoRegion) return null
        if (session.isExpired(skewSeconds = 60)) {
            session = try {
                refresh(session)
            } catch (e: SecretProviderException) {
                null
            } ?: return null
        }
        return session.accessToken
    }

    private fun assignments(token: String) = AwsSsoAssignments { path, query -> api.page(path, query, token) }

    /** Saves the picked account and role in the connection's options (settings: they aren't secret). */
    private fun rememberChoice(account: String, role: String) {
        val save = Runnable {
            val settings = SecretsAppSettings.getInstance()
            val current = settings.connection(connection.id) ?: return@Runnable
            settings.addOrUpdate(current.copy(options = current.options + mapOf(AwsAuthMethods.OPTION_SSO_ACCOUNT_ID to account, AwsAuthMethods.OPTION_SSO_ROLE_NAME to role)))
        }
        val app = ApplicationManager.getApplication() ?: return
        if (app.isDispatchThread) save.run() else app.invokeAndWait(save, ModalityState.any())
    }

    /** Logs out of the AWS access portal (best effort) and forgets the tokens, the registration and the role credentials. */
    fun signOut() {
        val session = loadSession()
        cache.remove(connection.id)
        CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION)
        if (session != null && !session.isExpired()) {
            try {
                Api(session.extra[EXTRA_SSO_REGION] ?: ssoRegion).logout(session.accessToken)
            } catch (e: SecretProviderException) {
                LOG.info("Logging out of IAM Identity Center for '${connection.name}' failed: ${e.kind}")
            }
        }
    }

    // ---- Tokens and role credentials ------------------------------------------------------------------------------------

    private fun roleCredentials(stored: TokenSet): AwsKeys = synchronized(lock(connection.id)) {
        cachedKeys()?.let { return it }
        if (stored.extra[EXTRA_START_URL] != startUrl || stored.extra[EXTRA_SSO_REGION] != ssoRegion) {
            throw SecretProviderException(Kind.AUTH_REQUIRED, "The IAM Identity Center settings of '${connection.name}' changed; sign in again")
        }
        var session = stored
        if (session.isExpired(skewSeconds = REFRESH_WINDOW_S)) {
            session = refresh(session) ?: if (session.isExpired()) throw expired() else session
        }
        // The connection's options win (they may have been changed since the sign-in); the session's are the fallback
        val account = optionAccount ?: session.extra[EXTRA_ACCOUNT].orEmpty()
        val role = optionRole ?: session.extra[EXTRA_ROLE].orEmpty()
        if (account.isEmpty() || role.isEmpty()) {
            throw SecretProviderException(Kind.AUTH_REQUIRED, "Choose the AWS account and role of '${connection.name}': sign in again, or use Choose… in its settings")
        }
        val keys = try {
            api.getRoleCredentials(session.accessToken, account, role)
        } catch (e: SecretProviderException) {
            if (e.kind != Kind.AUTH_REQUIRED) throw e
            // The token was revoked or expired early: renew it once
            val renewed = refresh(session) ?: throw expired()
            api.getRoleCredentials(renewed.accessToken, account, role)
        }
        cache[connection.id] = Cached(keys, account, role)
        keys
    }

    /** A new access token from the refresh token, stored; null when there is none or AWS no longer accepts it. */
    private fun refresh(session: TokenSet): TokenSet? {
        val refreshToken = session.refreshToken ?: return null
        val clientId = session.extra[EXTRA_CLIENT_ID] ?: return null
        val clientSecret = session.extra[EXTRA_CLIENT_SECRET] ?: return null
        val clientExpires = session.extra[EXTRA_CLIENT_EXPIRES]?.toLongOrNull() ?: 0
        if (clientExpires <= System.currentTimeMillis() / 1000 + 60) return null
        val result = try {
            api.createToken(Registration(clientId, clientSecret, clientExpires), refreshToken = refreshToken)
        } catch (e: SecretProviderException) {
            if (!session.isExpired() && (e.kind == Kind.NETWORK || e.kind == Kind.SERVER)) return null
            throw e
        }
        val token = (result as? TokenResult.Issued)?.token ?: return null
        val renewed = session.copy(accessToken = token.accessToken, refreshToken = token.refreshToken ?: refreshToken, expiresAt = token.expiresAt)
        CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, renewed.toJson())
        return renewed
    }

    private fun checkSettings() {
        if (!isStartUrl(startUrl)) throw SecretProviderException(Kind.CONFIGURATION, "Set the SSO start URL (https://…) of '${connection.name}'")
        if (!AwsSecretsManagerProvider.isRegion(ssoRegion)) throw SecretProviderException(Kind.CONFIGURATION, "Set the SSO region (for example eu-west-1) of '${connection.name}'")
    }

    private fun loadSession(): TokenSet? = CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)?.let(TokenSet::fromJson)

    private fun expired() = SecretProviderException(Kind.AUTH_REQUIRED, "The IAM Identity Center session of '${connection.name}' expired; sign in again")

    private fun cancelled() = SecretProviderException(Kind.CANCELLED, "Sign-in cancelled")

    private fun sleep(ms: Long, interaction: SignInInteraction) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (interaction.isCancelled()) throw cancelled()
            try {
                Thread.sleep(minOf(100L, until - System.currentTimeMillis()).coerceAtLeast(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SecretProviderException(Kind.CANCELLED, "Sign-in cancelled", e)
            }
        }
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------------

    /** A registered OIDC client. [expiresAt] is in epoch seconds. [toString] hides the secret. */
    class Registration(val clientId: String, val clientSecret: String, val expiresAt: Long) {
        override fun toString(): String = "Registration($clientId)"
    }

    /** A started device authorization. [toString] hides the device code. */
    class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    ) {
        override fun toString(): String = "DeviceAuthorization($userCode)"
    }

    /** An access token (expiry in epoch seconds). [toString] hides it. */
    class Token(val accessToken: String, val refreshToken: String?, val expiresAt: Long) {
        override fun toString(): String = "Token(expiresAt=$expiresAt, refresh=${refreshToken != null})"
    }

    /** What `CreateToken` answered: a token, or the OAuth error code (`authorization_pending`, `slow_down`, …). */
    sealed interface TokenResult {
        class Issued(val token: Token) : TokenResult

        class Failed(val code: String, val message: String) : TokenResult
    }

    /** The `sso-oidc` and `sso` (portal) endpoints of one region. */
    private class Api(region: String) {
        private val oidc = endpoint("oidc", region)
        private val portal = endpoint("portal", region)

        fun registerClient(): Registration {
            val body = JsonObject().apply {
                addProperty("clientName", CLIENT_NAME)
                addProperty("clientType", "public")
                add("scopes", JsonArray().apply { add(SCOPE) })
            }
            val o = json(post("$oidc/client/register", body, null), "Registering EnvBridge with IAM Identity Center")
            return Registration(
                o.str("clientId") ?: throw unexpected("RegisterClient"),
                o.str("clientSecret") ?: throw unexpected("RegisterClient"),
                o.get("clientSecretExpiresAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
            )
        }

        fun startDeviceAuthorization(registration: Registration, startUrl: String): DeviceAuthorization {
            val body = JsonObject().apply {
                addProperty("clientId", registration.clientId)
                addProperty("clientSecret", registration.clientSecret)
                addProperty("startUrl", startUrl)
            }
            val o = json(post("$oidc/device_authorization", body, null), "Starting the IAM Identity Center sign-in")
            return DeviceAuthorization(
                deviceCode = o.str("deviceCode") ?: throw unexpected("StartDeviceAuthorization"),
                userCode = o.str("userCode") ?: throw unexpected("StartDeviceAuthorization"),
                verificationUri = o.str("verificationUri") ?: throw unexpected("StartDeviceAuthorization"),
                verificationUriComplete = o.str("verificationUriComplete"),
                expiresInSeconds = o.get("expiresIn")?.takeIf { it.isJsonPrimitive }?.asLong ?: 600,
                intervalSeconds = o.get("interval")?.takeIf { it.isJsonPrimitive }?.asLong ?: 5,
            )
        }

        /** `CreateToken` with the device code grant ([deviceCode]) or the refresh token grant ([refreshToken]). */
        fun createToken(registration: Registration, deviceCode: String? = null, refreshToken: String? = null): TokenResult {
            val body = JsonObject().apply {
                addProperty("clientId", registration.clientId)
                addProperty("clientSecret", registration.clientSecret)
                if (deviceCode != null) {
                    addProperty("grantType", DEVICE_GRANT)
                    addProperty("deviceCode", deviceCode)
                } else {
                    addProperty("grantType", "refresh_token")
                    addProperty("refreshToken", refreshToken)
                }
            }
            val response = post("$oidc/token", body, null)
            if (response.statusCode() in 200..299) {
                val o = json(response, "Signing in to IAM Identity Center")
                val access = o.str("accessToken") ?: throw unexpected("CreateToken")
                val expiresIn = o.get("expiresIn")?.takeIf { it.isJsonPrimitive }?.asLong ?: 3600
                return TokenResult.Issued(Token(access, o.str("refreshToken"), System.currentTimeMillis() / 1000 + expiresIn))
            }
            val code = oauthError(response) ?: throw failure(response, "Signing in to IAM Identity Center")
            return TokenResult.Failed(code, message(response))
        }

        fun getRoleCredentials(token: String, account: String, role: String): AwsKeys {
            val url = "$portal/federation/credentials?account_id=${enc(account)}&role_name=${enc(role)}"
            val o = json(get(url, token), "Getting credentials for role $role in account $account")
            val c = o.getAsJsonObject("roleCredentials") ?: throw unexpected("GetRoleCredentials")
            val id = c.str("accessKeyId")
            val secret = c.str("secretAccessKey")
            if (id.isNullOrEmpty() || secret.isNullOrEmpty()) throw unexpected("GetRoleCredentials")
            // Milliseconds since the epoch
            val expiration = c.get("expiration")?.takeIf { it.isJsonPrimitive }?.asLong?.let(Instant::ofEpochMilli)
            return AwsKeys(id, secret, c.str("sessionToken"), expiration)
        }

        /** GETs a portal path (`ListAccounts`, `ListAccountRoles`) with the token in the bearer header; see [AwsSsoAssignments]. */
        fun page(path: String, query: List<Pair<String, String>>, token: String): JsonObject {
            val what = if (path == AwsSsoAssignments.ROLES_PATH) "Listing your roles in account ${query.firstOrNull { it.first == "account_id" }?.second}" else "Listing your AWS accounts"
            val url = "$portal$path" + query.joinToString("&", prefix = if (query.isEmpty()) "" else "?") { (k, v) -> "${enc(k)}=${enc(v)}" }
            return json(get(url, token), what)
        }

        fun logout(token: String) {
            val response = post("$portal/logout", null, token)
            if (response.statusCode() !in 200..299) throw failure(response, "Logging out of IAM Identity Center")
        }

        private fun post(url: String, body: JsonObject?, token: String?): HttpResponse<String> =
            send(request(url, token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body?.toString() ?: "")).build(), url)

        private fun get(url: String, token: String): HttpResponse<String> = send(request(url, token).GET().build(), url)

        private fun request(url: String, token: String?): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(AwsHttp.READ_TIMEOUT_MS))
            .header("User-Agent", CLIENT_NAME)
            .apply { token?.let { header("x-amz-sso_bearer_token", it) } }

        private fun send(request: HttpRequest, url: String): HttpResponse<String> = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            val base = url.substringBefore('?').let { u -> URI.create(u).let { "${it.scheme}://${it.authority}" } }
            LOG.debug("IAM Identity Center request to $base failed: ${e.javaClass.simpleName}")
            throw SecretProviderException(Kind.NETWORK, NetworkErrors.describe(base, e), e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SecretProviderException(Kind.CANCELLED, "Cancelled", e)
        }

        private fun json(response: HttpResponse<String>, what: String): JsonObject {
            if (response.statusCode() !in 200..299) throw failure(response, what)
            return runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
                ?: throw SecretProviderException(Kind.SERVER, "$what: AWS sent an unexpected response (not a JSON object)")
        }

        private fun unexpected(action: String) = SecretProviderException(Kind.SERVER, "IAM Identity Center sent an unexpected answer to $action")

        private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

        companion object {
            private val client: HttpClient by lazy {
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(AwsHttp.CONNECT_TIMEOUT_MS))
                    .proxy(ProxySelector.getDefault() ?: ProxySelector.of(null))
                    .apply { runCatching { com.intellij.util.net.ssl.CertificateManager.getInstance().sslContext }.getOrNull()?.let(::sslContext) }
                    .build()
            }
        }
    }

    /** Role credentials in memory, until five minutes before they expire. */
    private class Cached(val keys: AwsKeys, val account: String, val role: String) {
        private val until: Instant = keys.expiresAt?.minus(EXPIRY_MARGIN) ?: Instant.now().plus(Duration.ofMinutes(15))

        fun valid() = Instant.now().isBefore(until)
    }

    companion object {
        private val LOG = logger<AwsSso>()
        private const val CLIENT_NAME = "EnvBridge-IntelliJ"
        private const val SCOPE = "sso:account:access"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val SLOW_DOWN_MS = 5_000L
        private const val MAX_POLL_FAILURES = 5

        /** Tokens are renewed this long before they expire (as the AWS CLI does). */
        private const val REFRESH_WINDOW_S = 15 * 60L
        private val EXPIRY_MARGIN: Duration = Duration.ofMinutes(5)

        private const val EXTRA_CLIENT_ID = "clientId"
        private const val EXTRA_CLIENT_SECRET = "clientSecret"
        private const val EXTRA_CLIENT_EXPIRES = "clientSecretExpiresAt"
        private const val EXTRA_START_URL = "startUrl"
        private const val EXTRA_SSO_REGION = "ssoRegion"
        private const val EXTRA_ACCOUNT = "accountId"
        private const val EXTRA_ROLE = "roleName"

        private val cache = ConcurrentHashMap<String, Cached>()
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lock(connectionId: String): Any = locks.computeIfAbsent(connectionId) { Any() }

        /** Tests: the address of `oidc` / `portal` in a region instead of AWS's (a mock server). */
        @Volatile
        internal var endpointOverride: ((service: String, region: String) -> String)? = null

        /** Tests: poll this often instead of the interval AWS asks for. */
        @Volatile
        internal var pollIntervalOverrideMs: Long? = null

        private fun endpoint(service: String, region: String): String {
            endpointOverride?.let { return it(service, region).trimEnd('/') }
            val suffix = if (region.startsWith("cn-")) "amazonaws.com.cn" else "amazonaws.com"
            return if (service == "oidc") "https://oidc.$region.$suffix" else "https://portal.sso.$region.$suffix"
        }

        /** Whether [url] can be an IAM Identity Center start URL (https). */
        fun isStartUrl(url: String): Boolean {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            return uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        }

        private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

        /** The OAuth error code of a `CreateToken` error (`authorization_pending`, …), from `error` or the exception's name. */
        private fun oauthError(response: HttpResponse<String>): String? {
            val o = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
            o?.str("error")?.takeIf { it.isNotBlank() }?.let { return it }
            return when (errorType(response, o)) {
                "AuthorizationPendingException" -> "authorization_pending"
                "SlowDownException" -> "slow_down"
                "ExpiredTokenException" -> "expired_token"
                "AccessDeniedException" -> "access_denied"
                "InvalidGrantException" -> "invalid_grant"
                else -> null
            }
        }

        private fun errorType(response: HttpResponse<String>, o: JsonObject?): String? =
            (response.headers().firstValue("x-amzn-ErrorType").orElse(null) ?: o?.str("__type"))
                ?.substringAfterLast('#')?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }

        private fun message(response: HttpResponse<String>): String = runCatching {
            val o = JsonParser.parseString(response.body()).asJsonObject
            (o.str("error_description") ?: o.str("message") ?: o.str("Message")).orEmpty()
        }.getOrDefault("").trim().take(300)

        /** The exception for an error answer: 401 / UnauthorizedException and expired tokens are AUTH_REQUIRED. */
        private fun failure(response: HttpResponse<String>, what: String): SecretProviderException {
            val o = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
            val code = errorType(response, o) ?: o?.str("error")
            val detail = listOfNotNull(code, message(response).ifEmpty { null }).joinToString(": ").let { if (it.isEmpty()) "" else " ($it)" }
            val status = response.statusCode()
            return when {
                status == 401 || code == "UnauthorizedException" || code == "ExpiredTokenException" || code == "InvalidGrantException" || code == "invalid_grant" ->
                    SecretProviderException(Kind.AUTH_REQUIRED, "$what: IAM Identity Center didn't accept the session$detail; sign in again")
                status == 403 || code == "AccessDeniedException" -> SecretProviderException(Kind.FORBIDDEN, "$what: access denied$detail")
                code == "ResourceNotFoundException" || status == 404 -> SecretProviderException(Kind.NOT_FOUND, "$what: not found$detail")
                code == "TooManyRequestsException" || code == "SlowDownException" || status == 429 ->
                    SecretProviderException(Kind.SERVER, "$what: AWS throttled the request; retry in a moment$detail")
                status in 300..399 -> SecretProviderException(Kind.CONFIGURATION, "$what: the server redirected the request")
                status in 400..499 -> SecretProviderException(Kind.CONFIGURATION, "$what failed$detail")
                else -> SecretProviderException(Kind.SERVER, "$what failed (HTTP $status)$detail")
            }
        }
    }
}
