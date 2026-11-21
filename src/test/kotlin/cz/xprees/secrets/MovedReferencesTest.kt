package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.MovedReferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class MovedReferencesTest {
    private val defaults = SecretReference.Defaults("p1", "dev")

    private fun moved(from: SecretScope, to: SecretScope, keys: Set<String> = setOf("DB_PASSWORD"), connection: (String?) -> Boolean = { it == null || it == "Main" }) =
        MovedReferences("infisical", keys, from, to, connection)

    @Test
    fun `references to a moved secret follow it to its new folder and environment`() {
        val toBackend = moved(SecretScope("p1", "dev", "/"), SecretScope("p1", "dev", "/backend"))
        assertThat(toBackend.rewriteText("DB=infisical://DB_PASSWORD", defaults)).isEqualTo("DB=infisical://backend/DB_PASSWORD")
        // other keys, other folders, other environments and scope imports stay
        assertThat(toBackend.rewriteText("A=infisical://API_KEY\nB=infisical://other/DB_PASSWORD\nC=infisical://prod:DB_PASSWORD\nD=infisical://*", defaults)).isNull()

        val toProd = moved(SecretScope("p1", "dev", "/backend"), SecretScope("p1", "prod", "/"))
        assertThat(toProd.rewriteText("DB_PASSWORD= # infisical://backend/DB_PASSWORD", defaults)).isEqualTo("DB_PASSWORD= # infisical://prod:DB_PASSWORD")
        // an explicit environment that equals the source's still names the moved secret
        assertThat(toProd.rewriteText("X=infisical://dev:backend/DB_PASSWORD", defaults)).isEqualTo("X=infisical://prod:DB_PASSWORD")
    }

    @Test
    fun `references read through another binding environment or connection are left alone`() {
        val m = moved(SecretScope("p1", "dev", "/"), SecretScope("p1", "dev", "/backend"))
        // the run configuration (or a reference) reads prod: not the moved secret
        assertThat(m.rewriteText("X=infisical://DB_PASSWORD", defaults.copy(environment = "prod"))).isNull()
        assertThat(m.rewriteText("X=infisical://DB_PASSWORD?connection=Other", defaults)).isNull()
        assertThat(m.rewriteText("X=infisical://DB_PASSWORD?connection=Main", defaults)).isEqualTo("X=infisical://backend/DB_PASSWORD?connection=Main")
        // another project named explicitly
        assertThat(m.rewriteText("X=infisical://DB_PASSWORD?project=p2", defaults)).isNull()
        assertThat(m.rewriteText("X=infisical://DB_PASSWORD?project=p1", defaults)).isEqualTo("X=infisical://backend/DB_PASSWORD?project=p1")
    }
}
