package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.doppler.DopplerGrammar
import cz.xprees.secrets.doppler.DopplerProjectFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DopplerGrammarTest {
    @Before
    fun setUp() {
        SecretReference.grammarOverrides["doppler"] = DopplerGrammar
    }

    @After
    fun tearDown() {
        SecretReference.grammarOverrides.remove("doppler")
    }

    private fun parse(text: String) = SecretReference.parse(text, listOf("infisical", "doppler"))!!

    @Test
    fun `key of the bound config, of a named config, and every secret`() {
        val bound = parse("doppler://DB_PASSWORD")
        assertThat(bound.key).isEqualTo("DB_PASSWORD")
        assertThat(bound.environment).isNull()
        assertThat(bound.path).isNull()
        assertThat(bound.format()).isEqualTo("doppler://DB_PASSWORD")

        val prd = parse("doppler://prd:DB_PASSWORD")
        assertThat(prd.environment).isEqualTo("prd")
        assertThat(prd.format()).isEqualTo("doppler://prd:DB_PASSWORD")

        val personal = parse("doppler://dev_personal:*")
        assertThat(personal.isScope).isTrue()
        assertThat(personal.environment).isEqualTo("dev_personal")
        assertThat(personal.format()).isEqualTo("doppler://dev_personal:*")

        // ** means the same as * (there are no folders below a config)
        val recursive = parse("doppler://**")
        assertThat(recursive.isScope).isTrue()
        assertThat(recursive.recursive).isFalse()
        assertThat(recursive.format()).isEqualTo("doppler://*")
    }

    @Test
    fun `project and connection parameters`() {
        val ref = parse("doppler://stg:API_KEY?project=backend&connection=Work")
        assertThat(ref.projectId).isEqualTo("backend")
        assertThat(ref.connectionId).isEqualTo("Work")
        assertThat(ref.environment).isEqualTo("stg")
        assertThat(parse(ref.format())).isEqualTo(ref)
    }

    @Test
    fun `folders are refused with a clear message`() {
        assertThatThrownBy { parse("doppler://backend/DB_PASSWORD") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Doppler has no folders")
        assertThatThrownBy { parse("doppler://prd:app/*") }.hasMessageContaining("Doppler has no folders")
        assertThatThrownBy { parse("doppler://KEY?path=/x") }.hasMessageContaining("Doppler has no folders")
        assertThatThrownBy { parse("doppler://KEY?folder=x") }.hasMessageContaining("Unknown parameter")
    }

    @Test
    fun `building leaves out what the binding says, never writes a folder`() {
        val defaults = SecretReference.Defaults("backend", "dev")
        assertThat(DopplerGrammar.build("doppler", SecretScope("backend", "dev"), "DB_PASSWORD", defaults).format()).isEqualTo("doppler://DB_PASSWORD")
        assertThat(DopplerGrammar.build("doppler", SecretScope("backend", "prd"), null, defaults).format()).isEqualTo("doppler://prd:*")
        assertThat(DopplerGrammar.build("doppler", SecretScope("web", "dev", "/x", recursive = true), "K", defaults).format())
            .isEqualTo("doppler://dev:K?project=web")
    }

    @Test
    fun `doppler yaml with one project or a list of directories`() {
        val root = Files.createTempDirectory("doppler-yaml")
        try {
            val sub = Files.createDirectories(root.resolve("services/api"))
            Files.writeString(root.resolve("doppler.yaml"), "setup:\n  project: backend\n  config: dev_personal\n")
            val single = DopplerProjectFile.find(sub)!!
            assertThat(single.project).isEqualTo("backend")
            assertThat(single.config).isEqualTo("dev_personal")
            assertThat(single.toDefaults().environment).isEqualTo("dev_personal")

            Files.writeString(
                root.resolve("doppler.yaml"),
                """
                setup:
                  - project: web
                    config: dev
                    path: web/
                  - project: api
                    config: stg
                    path: ./services/api
                """.trimIndent(),
            )
            assertThat(DopplerProjectFile.find(sub)!!.project).isEqualTo("api")
            // no entry for the directory itself: the first one
            assertThat(DopplerProjectFile.find(root)!!.project).isEqualTo("web")

            Files.writeString(root.resolve("doppler.yaml"), "not: [a, setup")
            assertThat(DopplerProjectFile.find(sub)).isNull()
        } finally {
            root.toFile().deleteRecursively()
        }
        assertThat(DopplerProjectFile.parse(Path.of("doppler.yaml"), "setup: {}", Path.of("."))).isNull()
    }

    @Test
    fun `an API endpoint pasted as the address works like the API address`() {
        assertThat(cz.xprees.secrets.doppler.DopplerProvider.apiOrigin("https://api.doppler.com")).isEqualTo("https://api.doppler.com")
        assertThat(cz.xprees.secrets.doppler.DopplerProvider.apiOrigin("https://api.doppler.com/")).isEqualTo("https://api.doppler.com")
        assertThat(cz.xprees.secrets.doppler.DopplerProvider.apiOrigin("https://api.doppler.com/v3/workplace")).isEqualTo("https://api.doppler.com")
        assertThat(cz.xprees.secrets.doppler.DopplerProvider.apiOrigin("https://api.doppler.com/v3/")).isEqualTo("https://api.doppler.com")
        assertThat(cz.xprees.secrets.doppler.DopplerProvider.apiOrigin("http://127.0.0.1:8123/proxy/v3/me")).isEqualTo("http://127.0.0.1:8123/proxy")
        assertThat(cz.xprees.secrets.doppler.DopplerProvider.apiOrigin("http://127.0.0.1:8123")).isEqualTo("http://127.0.0.1:8123")
    }
}
