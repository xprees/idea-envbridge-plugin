package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.EnvResolution
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class EnvResolutionTest {
    private val defaults = SecretReference.Defaults("p1", "dev", "/")
    private val store = mapOf(
        SecretScope("p1", "dev", "/") to listOf(secret("A", "a-dev"), secret("B", "b-dev"), secret("SHARED", "from-scope")),
        SecretScope("p1", "prod", "/") to listOf(secret("A", "a-prod"), secret("EMPTY", "")),
        SecretScope("p1", "dev", "/hidden") to listOf(SecretEntry("H", SecretValue(""), valueHidden = true), secret("V", "v")),
    )
    private val fetched = mutableListOf<SecretScope>()

    private fun secret(key: String, value: String) = SecretEntry(key, SecretValue(value))

    private fun resolve(envs: Map<String, String>): EnvResolution.Result =
        EnvResolution.resolve(EnvResolution.plan(envs, listOf("infisical")), { defaults }) { _, scope ->
            fetched += scope
            store[scope] ?: emptyList()
        }

    @Test
    fun `plain variables pass through untouched`() {
        val result = resolve(mapOf("PLAIN" to "x"))
        assertThat(result.environment).containsExactly(entry("PLAIN", "x"))
        assertThat(fetched).isEmpty()
    }

    @Test
    fun `references are replaced, explicit values win over scope imports`() {
        val result = resolve(
            linkedMapOf(
                "INFISICAL" to "infisical://*",
                "PASSWORD" to "infisical://prod/A",
                "SHARED" to "explicit",
            ),
        )
        assertThat(result.environment).containsEntry("PASSWORD", "a-prod")
            .containsEntry("A", "a-dev").containsEntry("B", "b-dev")
            .containsEntry("SHARED", "explicit")
            .doesNotContainKey("INFISICAL")
        assertThat(result.sources["SHARED"]).isEqualTo(EnvResolution.Source.Explicit)
        assertThat(result.secretValues).contains("a-prod", "a-dev").doesNotContain("explicit")
    }

    @Test
    fun `empty values are passed on with a warning and named in the console summary`() {
        val result = resolve(linkedMapOf("URL" to "infisical://prod/EMPTY", "PASSWORD" to "infisical://prod/A", "PLAIN" to "x"))
        assertThat(result.environment).containsEntry("URL", "")
        assertThat(result.warnings).singleElement().asString().contains("URL").contains("EMPTY").contains("prod:/")
        val summary = result.summary()!!
        assertThat(summary).contains("2 variables").contains("[prod:/] URL, PASSWORD").contains("empty: URL")
            .doesNotContain("PLAIN").doesNotContain("a-prod")
        assertThat(resolve(mapOf("PLAIN" to "x")).summary()).isNull()
    }

    @Test
    fun `each scope is fetched once`() {
        resolve(mapOf("X" to "infisical://A", "Y" to "infisical://B", "Z" to "infisical://*"))
        assertThat(fetched).containsExactly(SecretScope("p1", "dev", "/"))
    }

    @Test
    fun `missing secrets and malformed references fail closed with every problem listed`() {
        assertThatThrownBy { resolve(mapOf("X" to "infisical://NOPE", "Y" to "infisical://prod/NOPE2")) }
            .isInstanceOf(SecretProviderException::class.java)
            .hasMessageContaining("NOPE").hasMessageContaining("NOPE2")
        assertThatThrownBy { resolve(mapOf("X" to "infisical://a/*/b")) }
            .isInstanceOf(SecretProviderException::class.java)
            .extracting { (it as SecretProviderException).kind }.isEqualTo(SecretProviderException.Kind.CONFIGURATION)
    }

    @Test
    fun `hidden values fail references and are skipped with a warning in scope imports`() {
        val result = resolve(mapOf("ALL" to "infisical://dev/hidden/*"))
        assertThat(result.environment).containsOnlyKeys("V")
        assertThat(result.warnings).hasSize(1)
        assertThatThrownBy { resolve(mapOf("X" to "infisical://dev/hidden/H")) }
            .hasMessageContaining("no permission")
    }

    @Test
    fun `secret values never show in toString`() {
        assertThat(SecretValue("s3cr3t").toString()).doesNotContain("s3cr3t")
        assertThat(secret("K", "s3cr3t").toString()).doesNotContain("s3cr3t")
    }

    private fun entry(k: String, v: String) = org.assertj.core.api.Assertions.entry(k, v)
}
