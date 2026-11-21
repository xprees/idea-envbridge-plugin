package cz.xprees.secrets

import cz.xprees.secrets.editor.SecretInlayMenu
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SecretInlayMenuTest {
    @Test
    fun `hint payload round trips the secret's location`() {
        assertThat(SecretInlayMenu.decode(SecretInlayMenu.encode("DB_PASSWORD", "prod", "/workers")))
            .isEqualTo(SecretInlayMenu.Target("DB_PASSWORD", "prod", "/workers"))
        // No environment written: the bound one
        assertThat(SecretInlayMenu.decode(SecretInlayMenu.encode("KEY", null, "/"))).isEqualTo(SecretInlayMenu.Target("KEY", null, "/"))
        assertThat(SecretInlayMenu.decode("garbage")).isNull()
        assertThat(SecretInlayMenu.decode(SecretInlayMenu.encode("", "dev", "/"))).isNull()
    }
}
