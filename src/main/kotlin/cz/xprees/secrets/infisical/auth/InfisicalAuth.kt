package cz.xprees.secrets.infisical.auth

import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.auth.Jwt
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.api.InfisicalApi
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Sessions of one Infisical connection: signs in with the connection's method, keeps the tokens in the credential store,
 * renews them before they expire and retries a request once after a 401.
 */
class InfisicalAuth(private val connection: SecretConnection, private val api: InfisicalApi) {

    private val lock: Any get() = LOCKS.computeIfAbsent(connection.id) { Any() }

    fun hasCredentials(): Boolean = when (connection.authMethodId) {
        InfisicalAuthMethods.MACHINE_IDENTITY -> secretOption(InfisicalAuthMethods.OPTION_CLIENT_SECRET) != null
        else -> loadSession() != null
    }

    /** Runs [call] with a valid access token; after a 401 the session is renewed and [call] retried once. */
    fun <T> withToken(call: (String) -> T): T {
        val token = accessToken()
        return try {
            call(token)
        } catch (e: SecretProviderException) {
            if (e.kind != Kind.AUTH_REQUIRED) throw e
            call(renew(staleToken = token))
        }
    }

    /** A valid access token, renewed first when it is about to expire. */
    fun accessToken(): String = synchronized(lock) {
        val session = loadSession()
        when {
            session != null && !session.isExpired() -> session.accessToken
            session != null || connection.authMethodId == InfisicalAuthMethods.MACHINE_IDENTITY -> renewLocked(session).accessToken
            else -> throw notSignedIn()
        }
    }

    /** Renews the session unless another thread already did since [staleToken] was handed out. */
    private fun renew(staleToken: String): String = synchronized(lock) {
        val current = loadSession()
        if (current != null && current.accessToken != staleToken && !current.isExpired()) return current.accessToken
        renewLocked(current).accessToken
    }

    private fun renewLocked(session: TokenSet?): TokenSet {
        val renewed = try {
            when (connection.authMethodId) {
                InfisicalAuthMethods.BROWSER -> api.refreshUserSession(session ?: throw notSignedIn())
                InfisicalAuthMethods.OAUTH -> {
                    val refresh = session?.refreshToken ?: throw notSignedIn()
                    api.oauthToken(
                        clientId = requireOption(InfisicalAuthMethods.OPTION_CLIENT_ID),
                        clientSecret = requireSecretOption(InfisicalAuthMethods.OPTION_CLIENT_SECRET),
                        form = mapOf("grant_type" to "refresh_token", "refresh_token" to refresh),
                        previous = session,
                    )
                }

                InfisicalAuthMethods.MACHINE_IDENTITY -> machineIdentityLogin()
                else -> throw unknownMethod()
            }
        } catch (e: SecretProviderException) {
            if (e.kind == Kind.AUTH_REQUIRED) CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION)
            throw e
        }
        storeSession(renewed)
        return renewed
    }

    /** Interactive sign-in. Returns the account description. */
    fun signIn(interaction: SignInInteraction): String {
        val session = when (connection.authMethodId) {
            InfisicalAuthMethods.BROWSER -> browserSignIn(interaction)
            InfisicalAuthMethods.OAUTH -> oauthSignIn(interaction)
            InfisicalAuthMethods.MACHINE_IDENTITY -> machineIdentityLogin()
            else -> throw unknownMethod()
        }
        synchronized(lock) { storeSession(session) }
        return session.account ?: connection.name
    }

    fun signOut() {
        val session = synchronized(lock) {
            loadSession().also { CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION) }
        }
        if (session != null && connection.authMethodId == InfisicalAuthMethods.BROWSER) api.revokeUserSession(session.accessToken)
    }

    fun account(): String? = loadSession()?.account

    // ---- Flows -----------------------------------------------------------------------------------------------------

    private fun browserSignIn(interaction: SignInInteraction): TokenSet {
        val callback = BrowserSessionCallback(connection.baseUrl)
        LoopbackServer.start(handler = callback::handle).use { server ->
            interaction.status("Waiting for the sign-in in your browser…")
            interaction.openBrowser("${connection.baseUrl}/login?callback_port=${server.port}")
            val session = LoopbackServer.await(callback.result, interaction)
            interaction.status("Checking the session…")
            api.checkUserSession(session.accessToken)
            return TokenSet(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                expiresAt = Jwt.expiresAt(session.accessToken),
                account = session.email ?: Jwt.stringClaim(session.accessToken, "email"),
                extra = listOfNotNull(Jwt.stringClaim(session.accessToken, "organizationId")?.let { "organizationId" to it }).toMap(),
            )
        }
    }

    private fun oauthSignIn(interaction: SignInInteraction): TokenSet {
        val clientId = requireOption(InfisicalAuthMethods.OPTION_CLIENT_ID)
        val clientSecret = requireSecretOption(InfisicalAuthMethods.OPTION_CLIENT_SECRET)
        val port = connection.option(InfisicalAuthMethods.OPTION_CALLBACK_PORT)?.toIntOrNull() ?: InfisicalAuthMethods.DEFAULT_CALLBACK_PORT
        val pkce = Pkce.generate()
        val state = Pkce.randomToken()
        val callback = OAuthRedirectCallback(state)
        LoopbackServer.start(port, callback::handle).use { server ->
            val redirectUri = "http://127.0.0.1:${server.port}${OAuthRedirectCallback.PATH}"
            val authorizeUrl = api.http.url(
                "/api/v1/oauth/authorize",
                mapOf(
                    "response_type" to "code",
                    "client_id" to clientId,
                    "redirect_uri" to redirectUri,
                    "scope" to InfisicalAuthMethods.OAUTH_SCOPE,
                    "state" to state,
                    "code_challenge" to pkce.challenge,
                    "code_challenge_method" to "S256",
                ),
            )
            interaction.status("Waiting for the approval in your browser…")
            interaction.openBrowser(authorizeUrl)
            val code = LoopbackServer.await(callback.result, interaction)
            interaction.status("Completing the sign-in…")
            return api.oauthToken(
                clientId = clientId,
                clientSecret = clientSecret,
                form = mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to pkce.verifier,
                ),
            )
        }
    }

    private fun machineIdentityLogin(): TokenSet = api.universalAuthLogin(
        clientId = requireOption(InfisicalAuthMethods.OPTION_CLIENT_ID),
        clientSecret = requireSecretOption(InfisicalAuthMethods.OPTION_CLIENT_SECRET),
        organizationSlug = connection.option(InfisicalAuthMethods.OPTION_ORGANIZATION_SLUG),
    )

    // ---- Storage ---------------------------------------------------------------------------------------------------

    private fun loadSession(): TokenSet? = CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)?.let(TokenSet::fromJson)

    private fun storeSession(session: TokenSet) = CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, session.toJson())

    private fun secretOption(key: String): String? = CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(key))

    private fun requireOption(key: String): String = connection.option(key)
        ?: throw SecretProviderException(Kind.CONFIGURATION, "Connection '${connection.name}' has no $key; edit it in the settings")

    private fun requireSecretOption(key: String): String = secretOption(key)
        ?: throw SecretProviderException(Kind.CONFIGURATION, "Connection '${connection.name}' has no stored $key; edit it in the settings")

    private fun notSignedIn() = SecretProviderException(Kind.AUTH_REQUIRED, "Not signed in to '${connection.name}'")

    private fun unknownMethod() = SecretProviderException(Kind.CONFIGURATION, "Unknown sign-in method '${connection.authMethodId}'")

    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

/** PKCE (RFC 7636) with S256, and random tokens for `state`. */
object Pkce {
    private val random = SecureRandom()

    data class Pair(val verifier: String, val challenge: String)

    fun generate(): Pair {
        val verifier = randomToken()
        return Pair(verifier, challengeOf(verifier))
    }

    fun challengeOf(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    /** 256 random bits, base64url without padding (43 characters, valid as a PKCE verifier). */
    fun randomToken(): String = ByteArray(32).also(random::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
