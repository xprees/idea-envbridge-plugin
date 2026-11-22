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

    private fun renamed(scope: SecretScope, old: String = "DB_PASSWORD", new: String = "DATABASE_PASSWORD", scheme: String = "infisical") =
        MovedReferences.renamed(scheme, scope, old, new) { it == null || it == "Main" }

    @Test
    fun `references to a renamed secret take its new name, in the same folder and environment`() {
        val root = renamed(SecretScope("p1", "dev", "/"))
        assertThat(root.rewriteText("DB=infisical://DB_PASSWORD", defaults)).isEqualTo("DB=infisical://DATABASE_PASSWORD")
        assertThat(root.rewriteText("DB_PASSWORD= # infisical://dev:DB_PASSWORD?connection=Main", defaults))
            .isEqualTo("DB_PASSWORD= # infisical://dev:DATABASE_PASSWORD?connection=Main")
        // other keys, other folders, other environments, other connections and scope imports stay
        assertThat(root.rewriteText("A=infisical://API_KEY\nB=infisical://other/DB_PASSWORD\nC=infisical://prod:DB_PASSWORD\nD=infisical://*\nE=infisical://DB_PASSWORD?connection=Other", defaults)).isNull()
        assertThat(root.rewriteText("X=infisical://DB_PASSWORD", defaults.copy(environment = "prod"))).isNull()

        val backend = renamed(SecretScope("p1", "prod", "/backend"))
        assertThat(backend.rewriteText("X=infisical://prod:backend/DB_PASSWORD?project=p1", defaults)).isEqualTo("X=infisical://prod:backend/DATABASE_PASSWORD?project=p1")
        assertThat(backend.rewriteText("X=infisical://backend/DB_PASSWORD", defaults)).isNull()
        // only the reference changes, never the variable named like the secret
        assertThat(backend.rewriteText("DB_PASSWORD=infisical://prod:backend/DB_PASSWORD", defaults)).isEqualTo("DB_PASSWORD=infisical://prod:backend/DATABASE_PASSWORD")
    }

    @Test
    fun `a move can rename too, and only the named keys get a new name`() {
        val m = MovedReferences("infisical", setOf("DB_PASSWORD", "API_KEY"), SecretScope("p1", "dev", "/"), SecretScope("p1", "dev", "/backend"), { it == null }, mapOf("DB_PASSWORD" to "PASS"))
        assertThat(m.rewriteText("A=infisical://DB_PASSWORD\nB=infisical://API_KEY", defaults)).isEqualTo("A=infisical://backend/PASS\nB=infisical://backend/API_KEY")
    }

    @Test
    fun `references that follow the selected environment are only rewritten when asked`() {
        fun renamed(includeFollowing: Boolean) = MovedReferences(
            "infisical", setOf("DB_PASSWORD"), SecretScope("p1", "dev", "/"), SecretScope("p1", "dev", "/"), { true },
            renames = mapOf("DB_PASSWORD" to "DATABASE_PASSWORD"),
            followsSelection = { it.environment == null },
            includeFollowing = includeFollowing,
        )
        val cautious = renamed(includeFollowing = false)
        assertThat(cautious.rewriteText("A=infisical://DB_PASSWORD\nB=infisical://dev:DB_PASSWORD", defaults))
            .isEqualTo("A=infisical://DB_PASSWORD\nB=infisical://dev:DATABASE_PASSWORD")
        assertThat(cautious.followingSkipped).isEqualTo(1)
        assertThat(renamed(includeFollowing = true).rewriteText("A=infisical://DB_PASSWORD", defaults)).isEqualTo("A=infisical://DATABASE_PASSWORD")
    }

    @Test
    fun `a project named instead of its ID still matches`() {
        val m = MovedReferences(
            "infisical", setOf("DB_PASSWORD"), SecretScope("id-123", "dev", "/"), SecretScope("id-123", "dev", "/backend"), { true },
            projectAliases = setOf("Private"),
        )
        assertThat(m.rewriteText("X=infisical://DB_PASSWORD?project=private", defaults)).isEqualTo("X=infisical://backend/DB_PASSWORD?project=private")
    }

    @Test
    fun `references written in another form follow through the equivalent folders`() {
        SecretReference.grammarOverrides["vault"] = cz.xprees.secrets.vault.VaultGrammar
        try {
            val layoutDefaults = SecretReference.Defaults("secret/myapp/{env}", "dev")
            val source = SecretScope("secret/myapp/{env}", "dev", "/db")
            fun m(destination: SecretScope, physical: SecretScope) = MovedReferences(
                "vault", setOf("password"), source, destination, { it == null },
                // a full path reads in the namespace "root"
                alsoNamed = listOf(SecretScope("secret", "root", "/myapp/dev/db") to physical),
            )
            val toCache = m(SecretScope("secret/myapp/{env}", "dev", "/cache"), SecretScope("secret", "root", "/myapp/dev/cache"))
            assertThat(toCache.rewriteText("A=vault://./db#password", layoutDefaults)).isEqualTo("A=vault://./cache#password")
            // a full path reads with the namespace as its environment, and stays a full path
            assertThat(toCache.rewriteText("B=vault://secret/myapp/dev/db#password", SecretReference.Defaults("secret", "root")))
                .isEqualTo("B=vault://secret/myapp/dev/cache#password")
            // into another layout project the grammar writes the full path, kept as built
            val elsewhere = m(SecretScope("secret/web/{env}", "prod", "/"), SecretScope("secret", "root", "/web/prod"))
            assertThat(elsewhere.rewriteText("A=vault://./db#password?env=dev", layoutDefaults)).isEqualTo("A=vault://secret/web/prod#password")
        } finally {
            SecretReference.grammarOverrides.remove("vault")
        }
    }
}
