package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.vault.VaultAuth
import cz.xprees.secrets.vault.VaultGrammar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test

class VaultGrammarTest {
    @Before
    fun setUp() {
        SecretReference.grammarOverrides["vault"] = VaultGrammar
    }

    @After
    fun tearDown() {
        SecretReference.grammarOverrides.remove("vault")
    }

    private fun parse(text: String) = SecretReference.parse(text, listOf("infisical", "vault"))!!

    @Test
    fun `mount, path and field`() {
        val ref = parse("vault://secret/app/db#password")
        assertThat(ref.projectId).isEqualTo("secret")
        assertThat(ref.path).isEqualTo("/app/db")
        assertThat(ref.key).isEqualTo("password")
        assertThat(ref.environment).isNull()
        assertThat(ref.isScope).isFalse()
        assertThat(ref.format()).isEqualTo("vault://secret/app/db#password")
    }

    @Test
    fun `scope imports, namespace and connection`() {
        val all = parse("vault://secret/app/db#*")
        assertThat(all.isScope).isTrue()
        assertThat(all.recursive).isFalse()
        val deep = parse("vault://secret/app#**?namespace=/team-a/&connection=Prod")
        assertThat(deep.recursive).isTrue()
        assertThat(deep.environment).isEqualTo("team-a")
        assertThat(deep.connectionId).isEqualTo("Prod")
        assertThat(deep.format()).isEqualTo("vault://secret/app#**?namespace=team-a&connection=Prod")
        // the query may also come before the field
        assertThat(parse("vault://secret/app/db?namespace=team-a/dev#password").environment).isEqualTo("team-a/dev")
    }

    @Test
    fun `round trips with encoded segments`() {
        listOf(
            "vault://secret/app/db#password",
            "vault://kv%2Fteam/my%20app/db#api.key",
            "vault://secret/a/b/c#*?namespace=team-a%2Fdev",
        ).forEach { text -> assertThat(parse(parse(text).format())).isEqualTo(parse(text)) }
        assertThat(parse("vault://kv%2Fteam/app#f").projectId).isEqualTo("kv/team")
    }

    @Test
    fun `malformed references are rejected`() {
        assertThatThrownBy { parse("vault://secret/app/db") }.hasMessageContaining("#field")
        assertThatThrownBy { parse("vault://secret#password") }.hasMessageContaining("mount and a secret path")
        assertThatThrownBy { parse("vault://secret/app#") }.hasMessageContaining("names no field")
        assertThatThrownBy { parse("vault://secret/a/../b#f") }.hasMessageContaining(". or ..")
        assertThatThrownBy { parse("vault://secret/./a#f") }.hasMessageContaining(". or ..")
        assertThatThrownBy { parse("vault://secret/app#f?env=prod") }.hasMessageContaining("Unknown parameter")
    }

    @Test
    fun `scope uses the binding's namespace when none is written`() {
        val scope = parse("vault://secret/app/db#password").scope(SecretReference.Defaults("other", "team-b"))
        assertThat(scope).isEqualTo(SecretScope("secret", "team-b", "/app/db"))
    }

    @Test
    fun `build always writes the mount and leaves out the bound namespace`() {
        val defaults = SecretReference.Defaults("secret", "root")
        assertThat(VaultGrammar.build("vault", SecretScope("secret", "root", "/app/db"), "password", defaults).format())
            .isEqualTo("vault://secret/app/db#password")
        assertThat(VaultGrammar.build("vault", SecretScope("kv", "team-a", "/x"), null, defaults).format())
            .isEqualTo("vault://kv/x#*?namespace=team-a")
    }

    @Test
    fun `infisical references keep their grammar`() {
        assertThat(parse("infisical://prod:workers/KEY").format()).isEqualTo("infisical://prod:workers/KEY")
    }

    @Test
    fun `namespace normalization and OIDC state`() {
        assertThat(VaultGrammar.normalizeNamespace(null)).isEqualTo("root")
        assertThat(VaultGrammar.normalizeNamespace(" / ")).isEqualTo("root")
        assertThat(VaultGrammar.normalizeNamespace("ROOT")).isEqualTo("root")
        assertThat(VaultGrammar.normalizeNamespace("team-a/dev/")).isEqualTo("team-a/dev")
        assertThat(VaultAuth.stateOf("https://idp.example/auth?client_id=x&state=st%2B1&nonce=n")).isEqualTo("st+1")
        assertThat(VaultAuth.stateOf("https://idp.example/auth?client_id=x")).isNull()
    }
}
