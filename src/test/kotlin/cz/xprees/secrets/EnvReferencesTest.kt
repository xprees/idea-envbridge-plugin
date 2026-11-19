package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.EnvResolution
import cz.xprees.secrets.core.resolve.ProdGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EnvReferencesTest {
    private val schemes = listOf("infisical")

    private fun names(text: String) = EnvReferences.find(text, schemes).map { it.name }

    @Test
    fun `variable reads in many languages`() {
        assertThat(names("""String a = System.getenv("JAVA_KEY");""")).containsExactly("JAVA_KEY")
        assertThat(names("""val b = System.getenv("KOTLIN_KEY")""")).containsExactly("KOTLIN_KEY")
        assertThat(names("""const c = process.env.NODE_KEY + process.env["BRACKET_KEY"]""")).containsExactly("NODE_KEY", "BRACKET_KEY")
        assertThat(names("""Bun.env.BUN_KEY; import.meta.env.VITE_KEY; Deno.env.get("DENO_KEY")""")).containsExactly("BUN_KEY", "VITE_KEY", "DENO_KEY")
        assertThat(names("""os.environ["PY_KEY"]; os.environ.get('PY_GET'); os.getenv("PY_GETENV")""")).containsExactly("PY_KEY", "PY_GET", "PY_GETENV")
        assertThat(names("""v := os.Getenv("GO_KEY"); w, _ := os.LookupEnv("GO_LOOKUP")""")).containsExactly("GO_KEY", "GO_LOOKUP")
        assertThat(names("""Environment.GetEnvironmentVariable("DOTNET_KEY")""")).containsExactly("DOTNET_KEY")
        assertThat(names("""ENV["RUBY_KEY"]; ENV.fetch("RUBY_FETCH")""")).containsExactly("RUBY_KEY", "RUBY_FETCH")
        assertThat(names("""std::env::var("RUST_KEY"); env("LARAVEL_KEY")""")).containsExactly("RUST_KEY", "LARAVEL_KEY")
        assertThat(names("""password: ${'$'}{DB_PASSWORD}\nurl: ${'$'}{DB_URL:jdbc:h2:mem}""")).containsExactly("DB_PASSWORD", "DB_URL")
    }

    @Test
    fun `secret references and ranges`() {
        val text = """DB=infisical://prod/backend/DB_PASSWORD"""
        val found = EnvReferences.find(text, schemes).single()
        assertThat(found.kind).isEqualTo(EnvReferences.Kind.REFERENCE)
        assertThat(found.name).isEqualTo("DB_PASSWORD")
        assertThat(found.reference!!.environment).isEqualTo("prod")
        assertThat(text.substring(found.start, found.end)).isEqualTo("infisical://prod/backend/DB_PASSWORD")
        // scope imports name no key
        assertThat(names("X=infisical://*")).isEmpty()
        assertThat(names("not.process.env")).isEmpty()
    }

    @Test
    fun `completion contexts`() {
        fun prefix(line: String) = EnvReferences.completionPrefix(line, schemes)
        assertThat(prefix("""System.getenv("DB_""")).isEqualTo("DB_")
        assertThat(prefix("""process.env.""")).isEqualTo("")
        assertThat(prefix("""os.environ["""")).isEqualTo("")
        assertThat(prefix("""url: ${'$'}{""")).isEqualTo("")
        assertThat(prefix("""DB=infisical://prod/backend/DB""")).isEqualTo("DB")
        assertThat(prefix("""DB=infisical://""")).isEqualTo("")
        assertThat(prefix("""val x = "DB_""")).isNull()
        assertThat(prefix("""System.getenv("DB_PASSWORD")""")).isNull()
    }

    @Test
    fun `prod guard patterns`() {
        assertThat(ProdGuard.matches("prod", "prod*, live*")).isTrue()
        assertThat(ProdGuard.matches("Production", "prod*")).isTrue()
        assertThat(ProdGuard.matches("live-eu", "prod*, live*")).isTrue()
        assertThat(ProdGuard.matches("dev", "prod*, live*")).isFalse()
        assertThat(ProdGuard.matches("preprod", "prod*")).isFalse()
        assertThat(ProdGuard.matches("stage1", "stage?")).isTrue()
        assertThat(ProdGuard.matches("prod", "")).isFalse()
    }

    @Test
    fun `local overrides replace secrets only`() {
        val scope = SecretScope("p", "prod")
        val reference = SecretReference("infisical", "DB")
        val result = EnvResolution.Result(
            environment = mapOf("DB" to "team", "PLAIN" to "x", "API" to "key"),
            sources = mapOf(
                "DB" to EnvResolution.Source.Reference(reference, scope),
                "PLAIN" to EnvResolution.Source.Explicit,
                "API" to EnvResolution.Source.Scope(SecretReference("infisical", null), scope, "/"),
            ),
        )
        val overridden = result.withOverrides(mapOf("DB" to "mine", "PLAIN" to "nope", "MISSING" to "x"))
        assertThat(overridden.environment).containsEntry("DB", "mine").containsEntry("PLAIN", "x").doesNotContainKey("MISSING")
        assertThat(overridden.sources["DB"]).isInstanceOf(EnvResolution.Source.LocalOverride::class.java)
        assertThat(overridden.environments).containsExactly("prod")
        assertThat(overridden.secretValues).contains("mine", "key").doesNotContain("x")
    }
}
