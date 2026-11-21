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
}
