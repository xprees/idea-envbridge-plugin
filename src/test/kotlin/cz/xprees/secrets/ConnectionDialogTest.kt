package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.ui.settings.ConnectionDialog
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ConnectionDialogTest {
    private val vault = SecretConnection("c1", "vault", "Vault", "https://vault.example", "token", mapOf("namespace" to "team-a"))

    @Test
    fun `an edited connection drops its session when anything it was made from changes`() {
        assertThat(ConnectionDialog.sessionStale(null, vault, secretsEntered = true)).isFalse()
        assertThat(ConnectionDialog.sessionStale(vault, vault.copy(name = "Renamed"), secretsEntered = false)).isFalse()
        // a newly pasted token, an option, the instance or the method
        assertThat(ConnectionDialog.sessionStale(vault, vault, secretsEntered = true)).isTrue()
        assertThat(ConnectionDialog.sessionStale(vault, vault.copy(options = mapOf("namespace" to "team-b")), secretsEntered = false)).isTrue()
        assertThat(ConnectionDialog.sessionStale(vault, vault.copy(baseUrl = "https://other.example"), secretsEntered = false)).isTrue()
        assertThat(ConnectionDialog.sessionStale(vault, vault.copy(authMethodId = "approle"), secretsEntered = false)).isTrue()
    }

    @Test
    fun `OK stores a session Choose signed in for, else drops a stale one`() {
        var stored = 0
        var removed = 0
        val pending = cz.xprees.secrets.core.provider.PendingSession { stored++ }
        // signed in by Choose…: stored (replacing whatever was there), and no "Sign in now?" needed
        assertThat(ConnectionDialog.applySession(pending, stale = true) { removed++ }).isTrue()
        assertThat(stored to removed).isEqualTo(1 to 0)
        assertThat(ConnectionDialog.applySession(null, stale = true) { removed++ }).isFalse()
        assertThat(stored to removed).isEqualTo(1 to 1)
        assertThat(ConnectionDialog.applySession(null, stale = false) { removed++ }).isFalse()
        assertThat(stored to removed).isEqualTo(1 to 1)
    }

    @Test
    fun `options that only select what the session is used for keep it`() {
        val sso = SecretConnection("c2", "aws", "AWS", "https://secretsmanager.eu-west-1.amazonaws.com", "sso", mapOf("ssoStartUrl" to "https://a.awsapps.com/start", "ssoAccountId" to "111111111111"))
        val keeps = setOf("ssoAccountId", "ssoRoleName")
        val switched = sso.copy(options = mapOf("ssoStartUrl" to "https://a.awsapps.com/start", "ssoAccountId" to "222222222222", "ssoRoleName" to "Admin"))
        assertThat(ConnectionDialog.sessionStale(sso, switched, secretsEntered = false, keepsSession = keeps)).isFalse()
        assertThat(ConnectionDialog.sessionStale(sso, sso.copy(options = mapOf("ssoStartUrl" to "https://a.awsapps.com/start")), secretsEntered = false, keepsSession = keeps)).isFalse()
        assertThat(ConnectionDialog.sessionStale(sso, switched, secretsEntered = false)).isTrue()
        assertThat(ConnectionDialog.sessionStale(sso, switched.copy(options = switched.options + ("ssoStartUrl" to "https://b.awsapps.com/start")), secretsEntered = false, keepsSession = keeps)).isTrue()
    }
}
