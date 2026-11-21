package cz.xprees.secrets.doppler

import com.intellij.openapi.diagnostic.logger
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction

/**
 * Credentials of one Doppler connection.
 *
 * - Browser sign-in: the Doppler CLI's flow (`doppler login`). The IDE asks Doppler for a code, opens the sign-in page in the
 *   system browser and shows the code so the user can check the browser shows the same one, then polls until the user has
 *   approved. The resulting token is kept in the IDE's password storage only; sign-out revokes it on the server.
 * - Token: a personal (`dp.pt.`), service (`dp.st.`) or service account (`dp.sa.`) token pasted into the connection and kept
 *   in the password storage as a secret option. Sign-out forgets it but doesn't revoke it (it is the user's own).
 *
 * The session slot holds the token (browser sign-in) and, for both methods, what `/v3/me` said (workplace, token type and, for
 * a service token, its project and config) so listings don't ask again.
 */
class DopplerAuth(private val connection: SecretConnection, private val api: DopplerApi) {

    private val method: String get() = connection.authMethodId

    fun hasCredentials(): Boolean = when (method) {
        DopplerAuthMethods.TOKEN -> pastedToken() != null
        else -> loadSession() != null
    }

    /** The token to send, or AUTH_REQUIRED. */
    fun token(): String = when (method) {
        DopplerAuthMethods.TOKEN -> pastedToken() ?: throw SecretProviderException(
            Kind.CONFIGURATION,
            "Connection '${connection.name}' has no Doppler token; edit it in Settings › Tools › EnvBridge",
        )
        DopplerAuthMethods.BROWSER -> loadSession()?.accessToken ?: throw notSignedIn()
        else -> throw unknownMethod()
    }

    /** Runs [call] with the token. A refused browser session is forgotten, so the UI offers to sign in again. */
    fun <T> withToken(call: (String) -> T): T {
        val token = token()
        try {
            return call(token)
        } catch (e: SecretProviderException) {
            if (e.kind == Kind.AUTH_REQUIRED && method == DopplerAuthMethods.BROWSER) {
                synchronized(this) {
                    if (loadSession()?.accessToken == token) CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION)
                }
            }
            throw e
        }
    }

    /** What Doppler said about the current token (cached in the session slot; asked again when the token changed). */
    fun me(): DopplerApi.Me {
        val token = token()
        val session = loadSession()
        if (session != null && session.accessToken == token && session.extra[EXTRA_CHECKED] == "true") return session.toMe()
        val me = withToken(api::me)
        // Not if the user signed out (or changed the token) meanwhile: that would bring the session back
        synchronized(this) {
            if (stillCurrent(token)) storeSession(TokenSet(accessToken = token, account = me.describe(connection.name), extra = me.toExtra()))
        }
        return me
    }

    /** Whether [token] is still this connection's token (the browser session's, or the pasted one). */
    private fun stillCurrent(token: String): Boolean = when (method) {
        DopplerAuthMethods.TOKEN -> CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(DopplerAuthMethods.OPTION_TOKEN)) == token
        else -> loadSession()?.accessToken == token
    }

    fun signIn(interaction: SignInInteraction): String = when (method) {
        DopplerAuthMethods.TOKEN -> {
            interaction.status("Checking the token…")
            val token = token()
            val me = api.me(token)
            storeSession(TokenSet(accessToken = token, account = me.describe(connection.name), extra = me.toExtra()))
            me.describe(connection.name)
        }
        DopplerAuthMethods.BROWSER -> browserSignIn(interaction)
        else -> throw unknownMethod()
    }

    fun signOut() {
        val session = synchronized(this) { loadSession().also { CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION) } }
        when (method) {
            // A pasted token is the user's own: forget it, but don't revoke it
            DopplerAuthMethods.TOKEN -> CredentialVault.remove(connection.id, CredentialVault.secretOptionSlot(DopplerAuthMethods.OPTION_TOKEN))
            DopplerAuthMethods.BROWSER -> if (session != null) {
                try {
                    api.revoke(session.accessToken)
                } catch (e: SecretProviderException) {
                    // Forgotten here either way; the user can revoke it in the dashboard (Tokens) if the server couldn't be reached
                    LOG.info("Revoking the Doppler token of '${connection.name}' failed: ${e.kind}")
                }
            }
        }
    }

    // ---- Browser sign-in ---------------------------------------------------------------------------------------------

    private fun browserSignIn(interaction: SignInInteraction): String {
        interaction.status("Starting the Doppler sign-in…")
        val code = api.generateAuthCode(hostname(), System.getProperty("os.name").orEmpty().lowercase(), System.getProperty("os.arch").orEmpty())
        val authUrl = code.authUrl
        // Only https (or loopback, for tests) is opened in the browser
        if (SecretConnection.normalizeBaseUrl(authUrl.substringBefore('?').substringBefore('#')) == null) {
            throw SecretProviderException(Kind.SERVER, "Doppler sent a sign-in address that isn't https")
        }
        interaction.status("In your browser, check that Doppler shows the code ${code.code}, then approve")
        interaction.openBrowser(authUrl)

        val interval = pollIntervalOverrideMs ?: code.pollIntervalMs?.coerceIn(500, 10_000) ?: DEFAULT_POLL_INTERVAL_MS
        val deadline = System.currentTimeMillis() + timeoutMs
        var failures = 0
        while (true) {
            if (interaction.isCancelled()) throw SecretProviderException(Kind.CANCELLED, "Sign-in cancelled")
            // A passing network or server problem doesn't end the sign-in the user is completing in the browser; a declined or
            // expired code does
            val poll = try {
                api.pollAuthCode(code.pollingCode).also { failures = 0 }
            } catch (e: SecretProviderException) {
                if (e.kind != Kind.NETWORK && e.kind != Kind.SERVER) throw e
                if (++failures >= MAX_POLL_FAILURES || System.currentTimeMillis() >= deadline) throw e
                DopplerApi.Poll.Pending
            }
            when (poll) {
                is DopplerApi.Poll.Authorized -> {
                    interaction.status("Completing the sign-in…")
                    val me = runCatching { api.me(poll.token) }.getOrNull()
                    val account = me?.describe(connection.name) ?: poll.name ?: connection.name
                    val extra = me?.toExtra() ?: buildMap {
                        poll.workplaceSlug?.let { put(EXTRA_WORKPLACE, it) }
                        poll.workplaceName?.let { put(EXTRA_WORKPLACE_NAME, it) }
                    }
                    storeSession(TokenSet(accessToken = poll.token, account = account, extra = extra))
                    return account
                }
                DopplerApi.Poll.Pending -> Unit
            }
            if (System.currentTimeMillis() >= deadline) {
                throw SecretProviderException(Kind.AUTH_REQUIRED, "The Doppler sign-in wasn't approved within ${timeoutMs / 60_000} minutes; try again")
            }
            sleepCancellable(interval, interaction)
        }
    }

    private fun sleepCancellable(ms: Long, interaction: SignInInteraction) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (interaction.isCancelled()) throw SecretProviderException(Kind.CANCELLED, "Sign-in cancelled")
            try {
                Thread.sleep(minOf(100L, until - System.currentTimeMillis()).coerceAtLeast(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SecretProviderException(Kind.CANCELLED, "Sign-in cancelled", e)
            }
        }
    }

    // ---- Storage -----------------------------------------------------------------------------------------------------

    private fun loadSession(): TokenSet? = CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)?.let(TokenSet::fromJson)

    private fun storeSession(session: TokenSet) = synchronized(this) {
        CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, session.toJson())
    }

    private fun pastedToken(): String? =
        CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(DopplerAuthMethods.OPTION_TOKEN))?.trim()?.takeIf { it.isNotEmpty() }

    private fun notSignedIn() = SecretProviderException(Kind.AUTH_REQUIRED, "Not signed in to '${connection.name}'")

    private fun unknownMethod() = SecretProviderException(Kind.CONFIGURATION, "Unknown sign-in method '$method'")

    companion object {
        private val LOG = logger<DopplerAuth>()

        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        /** Network or server failures in a row that end a browser sign-in. */
        const val MAX_POLL_FAILURES = 5
        const val EXTRA_CHECKED = "checked"
        const val EXTRA_WORKPLACE = "workplace"
        const val EXTRA_WORKPLACE_NAME = "workplaceName"
        const val EXTRA_TYPE = "type"
        const val EXTRA_EMAIL = "email"
        const val EXTRA_NAME = "name"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_CONFIG = "config"

        /** Tests poll faster than Doppler asks. */
        @Volatile
        var pollIntervalOverrideMs: Long? = null

        /** How long the browser sign-in waits for the approval. */
        @Volatile
        var timeoutMs: Long = 5 * 60_000L

        /** The computer's name, for the token's name in Doppler (as the CLI names its tokens); never a secret. */
        private fun hostname(): String {
            val host = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            return "EnvBridge" + (host?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        }

        private fun DopplerApi.Me.toExtra(): Map<String, String> = buildMap {
            put(EXTRA_CHECKED, "true")
            workplaceSlug?.let { put(EXTRA_WORKPLACE, it) }
            workplaceName?.let { put(EXTRA_WORKPLACE_NAME, it) }
            type?.let { put(EXTRA_TYPE, it) }
            userEmail?.let { put(EXTRA_EMAIL, it) }
            name?.let { put(EXTRA_NAME, it) }
            project?.let { put(EXTRA_PROJECT, it) }
            config?.let { put(EXTRA_CONFIG, it) }
        }

        private fun TokenSet.toMe() = DopplerApi.Me(
            name = extra[EXTRA_NAME],
            type = extra[EXTRA_TYPE],
            workplaceSlug = extra[EXTRA_WORKPLACE],
            workplaceName = extra[EXTRA_WORKPLACE_NAME],
            userEmail = extra[EXTRA_EMAIL],
            project = extra[EXTRA_PROJECT],
            config = extra[EXTRA_CONFIG],
        )
    }
}
