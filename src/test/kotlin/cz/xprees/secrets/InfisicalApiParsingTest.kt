package cz.xprees.secrets

import com.google.gson.JsonParser
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.infisical.api.InfisicalApi
import cz.xprees.secrets.infisical.auth.Pkce
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class InfisicalApiParsingTest {
    @Test
    fun `local secrets win over imports and later imports win over earlier ones`() {
        val json = JsonParser.parseString(
            """
            {
              "secrets": [
                {"secretKey":"A","secretValue":"local","secretComment":"c","type":"shared","environment":"dev","secretPath":"/"},
                {"secretKey":"P","secretValue":"mine","type":"personal","environment":"dev"},
                {"secretKey":"H","secretValue":"","secretValueHidden":true}
              ],
              "imports": [
                {"secretPath":"/shared","environment":"dev","secrets":[
                  {"secretKey":"A","secretValue":"import1"},{"secretKey":"B","secretValue":"import1"},{"secretKey":"C","secretValue":"import1"}]},
                {"secretPath":"/base","environment":"prod","secrets":[{"secretKey":"B","secretValue":"import2"}]}
              ]
            }
            """.trimIndent(),
        ).asJsonObject
        val secrets = InfisicalApi.parseSecrets(json, SecretScope("p", "dev", "/")).associateBy { it.key }
        assertThat(secrets.getValue("A").value.reveal()).isEqualTo("local")
        assertThat(secrets.getValue("A").comment).isEqualTo("c")
        assertThat(secrets.getValue("B").value.reveal()).isEqualTo("import2")
        assertThat(secrets.getValue("B").origin).isEqualTo(SecretOrigin.IMPORTED)
        assertThat(secrets.getValue("B").environment).isEqualTo("prod")
        assertThat(secrets.getValue("B").path).isEqualTo("/base")
        assertThat(secrets.getValue("C").value.reveal()).isEqualTo("import1")
        assertThat(secrets.getValue("P").origin).isEqualTo(SecretOrigin.PERSONAL)
        assertThat(secrets.getValue("H").valueHidden).isTrue()
    }

    @Test
    fun `projects with environments`() {
        val project = InfisicalApi.parseProject(
            JsonParser.parseString("""{"id":"p1","name":"Shop","slug":"shop","orgId":"o","environments":[{"name":"Development","slug":"dev","id":"e1"}]}""").asJsonObject,
        )!!
        assertThat(project.environments.single().slug).isEqualTo("dev")
        assertThat(project.organizationId).isEqualTo("o")
    }

    @Test
    fun `pkce matches the RFC 7636 example`() {
        assertThat(Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        val generated = Pkce.generate()
        assertThat(generated.verifier).hasSize(43).matches("[A-Za-z0-9_-]+")
        assertThat(Pkce.randomToken()).isNotEqualTo(Pkce.randomToken())
    }

    @Test
    fun `base urls must be https except for loopback`() {
        assertThat(SecretConnection.normalizeBaseUrl("https://eu.infisical.com/")).isEqualTo("https://eu.infisical.com")
        assertThat(SecretConnection.normalizeBaseUrl("https://vault.example.com:8443/infisical")).isEqualTo("https://vault.example.com:8443/infisical")
        assertThat(SecretConnection.normalizeBaseUrl("http://localhost:8080")).isEqualTo("http://localhost:8080")
        assertThat(SecretConnection.normalizeBaseUrl("http://127.0.0.1")).isEqualTo("http://127.0.0.1")
        assertThat(SecretConnection.normalizeBaseUrl("http://infisical.example.com")).isNull()
        assertThat(SecretConnection.normalizeBaseUrl("https://user:pw@example.com")).isNull()
        assertThat(SecretConnection.normalizeBaseUrl("ftp://example.com")).isNull()
        assertThat(SecretConnection.normalizeBaseUrl("not a url")).isNull()
    }

    @Test
    fun `token sets round-trip and hide tokens in toString`() {
        val tokens = TokenSet("access", "refresh", 123, "me@example.com", mapOf("organizationId" to "o"))
        assertThat(TokenSet.fromJson(tokens.toJson())).isEqualTo(tokens)
        assertThat(tokens.toString()).doesNotContain("access").doesNotContain("refresh\"")
        assertThat(tokens.isExpired(nowEpochSeconds = 100, skewSeconds = 60)).isTrue()
        assertThat(tokens.isExpired(nowEpochSeconds = 10, skewSeconds = 60)).isFalse()
    }
}
