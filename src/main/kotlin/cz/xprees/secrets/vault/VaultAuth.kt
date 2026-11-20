package cz.xprees.secrets.vault

import com.google.gson.JsonObject
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.infisical.auth.LoopbackServer
import cz.xprees.secrets.infisical.auth.OAuthRedirectCallback
import cz.xprees.secrets.infisical.auth.Pkce
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Sessions of one Vault / OpenBao connection: signs in with the connection's method, keeps the token in the credential
 * store, renews renewable tokens before they expire, signs in again without asking where it can (AppRole, userpass, LDAP:
 * their secret is stored) and retries a request once after the token was refused.
 */
class VaultAuth(private val connection: SecretConnection, private val api: VaultApi) {

    private val lock: Any get() = LOCKS.computeIfAbsent(connection.id) { Any() }

    /** The namespace sign-in and token calls use: the connection's namespace, or the root. */
    val loginNamespace: String get() = VaultGrammar.normalizeNamespace(connection.option(VaultAuthMethods.OPTION_NAMESPACE))

    private val method: String get() = connection.authMethodId

    fun hasCredentials(): Boolean = loadSession() != null || when (method) {
        VaultAuthMethods.TOKEN -> configuredToken() != null
        VaultAuthMethods.APPROLE -> secretOption(VaultAuthMethods.OPTION_SECRET_ID) != null
        VaultAuthMethods.USERPASS, VaultAuthMethods.LDAP -> secretOption(VaultAuthMethods.OPTION_PASSWORD) != null
        else -> false
    }

    /** Runs [call] with a valid token; after the token was refused the session is renewed and [call] retried once. */
    fun <T> withToken(call: (String) -> T): T {
        val token = token()
        return try {
            call(token)
        } catch (e: SecretProviderException) {
            // A dead token can look like a missing permission (OpenBao answers only "permission denied"): ask the server
            if (e.kind != Kind.AUTH_REQUIRED && !(e.kind == Kind.FORBIDDEN && !tokenIsValid(token))) throw e
            call(renew(staleToken = token))
        }
    }

    private fun tokenIsValid(token: String): Boolean = try {
        api.lookupSelf(token, loginNamespace)
        true
    } catch (e: SecretProviderException) {
        e.kind != Kind.AUTH_REQUIRED
    }

    /** A valid token, renewed (or obtained again) first when it is about to expire. */
    fun token(): String = synchronized(lock) {
        val session = loadSession()
        if (session != null && !session.isExpired()) session.accessToken else renewLocked(session).accessToken
    }

    private fun renew(staleToken: String): String = synchronized(lock) {
        val current = loadSession()
        if (current != null && current.accessToken != staleToken && !current.isExpired()) return current.accessToken
        // The token was refused although it looked valid: renewing it won't help, sign in again
        renewLocked(current?.takeIf { it.accessToken != staleToken }).accessToken
    }

    /** Renews [session]'s token where possible, else signs in again without the browser; fails with AUTH_REQUIRED otherwise. */
    private fun renewLocked(session: TokenSet?): TokenSet {
        if (session != null && session.extra[VaultApi.EXTRA_RENEWABLE] == "true" && !session.isExpired(skewSeconds = 0)) {
            try {
                return api.renewSelf(session, loginNamespace).also(::storeSession)
            } catch (e: SecretProviderException) {
                if (e.kind != Kind.AUTH_REQUIRED && e.kind != Kind.FORBIDDEN) throw e
            }
        }
        val renewed = try {
            when (method) {
                // The user may have replaced the token (or the Vault CLI's) since: check what is configured now
                VaultAuthMethods.TOKEN -> api.lookupSelf(configuredToken() ?: throw notSignedIn(), loginNamespace)
                VaultAuthMethods.APPROLE, VaultAuthMethods.USERPASS, VaultAuthMethods.LDAP -> passwordLogin()
                VaultAuthMethods.OIDC -> throw notSignedIn()
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
        val session = when (method) {
            VaultAuthMethods.OIDC -> oidcSignIn(interaction)
            VaultAuthMethods.TOKEN -> {
                interaction.status("Checking the token…")
                val token = configuredToken() ?: throw SecretProviderException(
                    Kind.CONFIGURATION,
                    "Connection '${connection.name}' has no token, and there is no ~/.vault-token; edit the connection in the settings",
                )
                api.lookupSelf(token, loginNamespace)
            }

            VaultAuthMethods.APPROLE, VaultAuthMethods.USERPASS, VaultAuthMethods.LDAP -> {
                interaction.status("Signing in…")
                passwordLogin()
            }

            else -> throw unknownMethod()
        }
        synchronized(lock) { storeSession(session) }
        return session.account ?: connection.name
    }

    fun signOut() {
        val session = synchronized(lock) {
            loadSession().also { CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION) }
        }
        // A pasted token (or the Vault CLI's) is the user's own: forget it, but don't revoke it
        if (session != null && method != VaultAuthMethods.TOKEN) api.revokeSelf(session.accessToken, loginNamespace)
    }

    // ---- Flows -----------------------------------------------------------------------------------------------------

    private fun oidcSignIn(interaction: SignInInteraction): TokenSet {
        val mount = connection.option(VaultAuthMethods.OPTION_MOUNT) ?: VaultAuthMethods.OIDC
        val port = connection.option(VaultAuthMethods.OPTION_CALLBACK_PORT)?.toIntOrNull() ?: VaultAuthMethods.DEFAULT_CALLBACK_PORT
        val clientNonce = Pkce.randomToken()
        // The Vault CLI's redirect URI, so roles that allow the CLI work as they are
        val redirectUri = "http://localhost:$port${VaultAuthMethods.CALLBACK_PATH}"
        interaction.status("Starting the sign-in…")
        val authUrl = api.oidcAuthUrl(loginNamespace, mount, connection.option(VaultAuthMethods.OPTION_ROLE), redirectUri, clientNonce)
        val state = stateOf(authUrl)
            ?: throw SecretProviderException(Kind.SERVER, "The OIDC sign-in address has no state parameter")
        val callback = OAuthRedirectCallback(state, VaultAuthMethods.CALLBACK_PATH)
        LoopbackServer.start(port, callback::handle).use {
            interaction.status("Waiting for the sign-in in your browser…")
            interaction.openBrowser(authUrl)
            val code = LoopbackServer.await(callback.result, interaction)
            interaction.status("Completing the sign-in…")
            val token = api.oidcCallback(loginNamespace, mount, state, code, clientNonce)
            return api.lookupSelf(token, loginNamespace)
        }
    }

    /** AppRole, userpass or LDAP login with the stored secret. */
    private fun passwordLogin(): TokenSet {
        val token = when (method) {
            VaultAuthMethods.APPROLE -> api.login(
                loginNamespace,
                connection.option(VaultAuthMethods.OPTION_MOUNT) ?: VaultAuthMethods.APPROLE,
                username = null,
                body = JsonObject().apply {
                    addProperty("role_id", requireOption(VaultAuthMethods.OPTION_ROLE_ID))
                    addProperty("secret_id", requireSecretOption(VaultAuthMethods.OPTION_SECRET_ID))
                },
            )

            else -> api.login(
                loginNamespace,
                connection.option(VaultAuthMethods.OPTION_MOUNT) ?: method,
                username = requireOption(VaultAuthMethods.OPTION_USERNAME),
                body = JsonObject().apply { addProperty("password", requireSecretOption(VaultAuthMethods.OPTION_PASSWORD)) },
            )
        }
        return api.lookupSelf(token, loginNamespace)
    }

    // ---- Storage ---------------------------------------------------------------------------------------------------

    private fun loadSession(): TokenSet? = CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)?.let(TokenSet::fromJson)

    private fun storeSession(session: TokenSet) = CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, session.toJson())

    private fun secretOption(key: String): String? = CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(key))?.takeIf { it.isNotBlank() }

    /** The token of a token connection: the stored one, else the Vault CLI's `~/.vault-token`. */
    private fun configuredToken(): String? = secretOption(VaultAuthMethods.OPTION_TOKEN) ?: cliToken()

    private fun requireOption(key: String): String = connection.option(key)
        ?: throw SecretProviderException(Kind.CONFIGURATION, "Connection '${connection.name}' has no $key; edit it in the settings")

    private fun requireSecretOption(key: String): String = secretOption(key)
        ?: throw SecretProviderException(Kind.CONFIGURATION, "Connection '${connection.name}' has no stored $key; edit it in the settings")

    private fun notSignedIn() = SecretProviderException(Kind.AUTH_REQUIRED, "Not signed in to '${connection.name}'")

    private fun unknownMethod() = SecretProviderException(Kind.CONFIGURATION, "Unknown sign-in method '$method'")

    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()

        /** Where the Vault CLI keeps its token; tests point it elsewhere. */
        @Volatile
        var cliTokenFile: Path? = runCatching { Path.of(System.getProperty("user.home"), ".vault-token") }.getOrNull()

        private fun cliToken(): String? = cliTokenFile?.takeIf { Files.isRegularFile(it) }
            ?.let { runCatching { Files.readString(it).trim() }.getOrNull() }?.takeIf { it.isNotEmpty() }

        /** The `state` query parameter of [url]. */
        fun stateOf(url: String): String? = runCatching { URI(url).rawQuery }.getOrNull().orEmpty().split('&')
            .firstOrNull { it.startsWith("state=") }?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }?.takeIf { it.isNotEmpty() }
    }
}
