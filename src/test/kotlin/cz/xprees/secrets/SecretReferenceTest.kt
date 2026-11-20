package cz.xprees.secrets

import cz.xprees.secrets.core.reference.SecretReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class SecretReferenceTest {
    private val schemes = listOf("infisical")
    private fun parse(text: String) = SecretReference.parse(text, schemes)

    @Test
    fun `plain values are not references`() {
        assertThat(parse("hello")).isNull()
        assertThat(parse("https://example.com")).isNull()
        assertThat(parse("doppler://KEY")).isNull()
        assertThat(parse("")).isNull()
    }

    @Test
    fun `a bare key takes the environment from the binding and reads the root folder`() {
        val ref = parse("infisical://DB_PASSWORD")!!
        assertThat(ref.key).isEqualTo("DB_PASSWORD")
        assertThat(ref.environment).isNull()
        assertThat(ref.path).isNull()
        // The binding's folder is a UI default only: references are absolute
        val scope = ref.scope(SecretReference.Defaults("p1", "dev", "/backend"))
        assertThat(scope.projectId).isEqualTo("p1")
        assertThat(scope.environment).isEqualTo("dev")
        assertThat(scope.path).isEqualTo("/")
        assertThat(parse("infisical://workers/KEY")!!.scope(SecretReference.Defaults("p1", "dev", "/backend")).path).isEqualTo("/workers")
    }

    @Test
    fun `environment and folder segments`() {
        val ref = parse("infisical://prod:backend/api/DB_PASSWORD")!!
        assertThat(ref.environment).isEqualTo("prod")
        assertThat(ref.path).isEqualTo("/backend/api")
        assertThat(ref.key).isEqualTo("DB_PASSWORD")

        val root = parse("infisical://prod:KEY")!!
        assertThat(root.environment).isEqualTo("prod")
        assertThat(root.path).describedAs("the root is no folder").isNull()
    }

    @Test
    fun `scope imports`() {
        val all = parse("infisical://*")!!
        assertThat(all.isScope).isTrue()
        assertThat(all.recursive).isFalse()
        val deep = parse("infisical://staging:backend/**")!!
        assertThat(deep.isScope).isTrue()
        assertThat(deep.recursive).isTrue()
        assertThat(deep.environment).isEqualTo("staging")
        assertThat(deep.path).isEqualTo("/backend")
    }

    @Test
    fun `query parameters override and add`() {
        val ref = parse("infisical://KEY?env=prod&path=backend/&project=abc&connection=Work")!!
        assertThat(ref.environment).isEqualTo("prod")
        assertThat(ref.path).isEqualTo("/backend")
        assertThat(ref.projectId).isEqualTo("abc")
        assertThat(ref.connectionId).isEqualTo("Work")
    }

    @Test
    fun `scheme is case insensitive and whitespace around is ignored`() {
        assertThat(parse("  INFISICAL://KEY ")!!.key).isEqualTo("KEY")
    }

    @Test
    fun `malformed references are rejected`() {
        assertThatThrownBy { parse("infisical://") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parse("infisical://a/*/b") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parse("infisical://KEY?bogus=1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parse("infisical://MY KEY") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `format round-trips`() {
        for (text in listOf(
            "infisical://KEY",
            "infisical://prod:KEY",
            "infisical://prod:backend/api/KEY",
            "infisical://*",
            "infisical://staging:backend/**",
            "infisical://KEY?project=abc&connection=Work",
            "infisical://KEY?path=%2Fbackend",
            "infisical://KEY?path=/backend/api",
        )) {
            val ref = parse(text)!!
            assertThat(parse(ref.format())).describedAs(text).isEqualTo(ref)
        }
        assertThat(parse("infisical://prod:backend/KEY")!!.format()).isEqualTo("infisical://prod:backend/KEY")
        assertThat(SecretReference("infisical", "KEY", path = "/backend/api").format()).isEqualTo("infisical://backend/api/KEY")
        assertThat(SecretReference("infisical", "KEY", environment = "prod", path = "/backend").format()).isEqualTo("infisical://prod:backend/KEY")
        // Old-style query forms still parse
        assertThat(parse("infisical://KEY?env=prod&path=/workers")).isEqualTo(parse("infisical://prod:workers/KEY"))
        // Folders are absolute and the root is no folder at all
        assertThat(parse("infisical://workers/KEY")!!.path).isEqualTo("/workers")
        assertThat(parse("infisical://KEY")!!.path).isNull()
        assertThat(parse("infisical://prod:*")!!.let { it.environment to it.isScope }).isEqualTo("prod" to true)
    }
}
