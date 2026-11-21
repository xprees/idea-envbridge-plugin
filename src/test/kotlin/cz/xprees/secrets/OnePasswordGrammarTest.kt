package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.onepassword.OnePasswordGrammar
import cz.xprees.secrets.vault.VaultGrammar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test

class OnePasswordGrammarTest {
    @Before
    fun setUp() {
        SecretReference.grammarOverrides["op"] = OnePasswordGrammar
        SecretReference.grammarOverrides["vault"] = VaultGrammar
    }

    @After
    fun tearDown() {
        SecretReference.grammarOverrides.remove("op")
        SecretReference.grammarOverrides.remove("vault")
    }

    private fun parse(text: String) = SecretReference.parse(text, listOf("infisical", "vault", "op"))!!

    @Test
    fun `vault, item and field`() {
        val ref = parse("op://Private/Database/password")
        assertThat(ref.projectId).isEqualTo("Private")
        assertThat(ref.path).isEqualTo("/Database")
        assertThat(ref.key).isEqualTo("password")
        assertThat(ref.environment).isNull()
        assertThat(ref.isScope).isFalse()
        assertThat(ref.format()).isEqualTo("op://Private/Database/password")
        assertThat(ref.scope(SecretReference.Defaults("other", "default"))).isEqualTo(SecretScope("Private", "default", "/Database"))
    }

    @Test
    fun `sections, IDs and names with spaces`() {
        val ref = parse("op://Dev Vault/AWS Keys/prod creds/secret access key")
        assertThat(ref.projectId).isEqualTo("Dev Vault")
        assertThat(ref.path).isEqualTo("/AWS Keys/prod creds")
        assertThat(ref.key).isEqualTo("secret access key")
        // written raw, as 1Password writes them, so the reference also works with op run
        assertThat(ref.format()).isEqualTo("op://Dev Vault/AWS Keys/prod creds/secret access key")

        val ids = parse("op://q5vq4vsfjhzi6y4wbqmpg2xuge/e7b3lhxqbbg6n3kbt4cwh4ttsm/password")
        assertThat(ids.projectId).isEqualTo("q5vq4vsfjhzi6y4wbqmpg2xuge")
        assertThat(ids.path).isEqualTo("/e7b3lhxqbbg6n3kbt4cwh4ttsm")
    }

    @Test
    fun `scope imports and connection`() {
        val item = parse("op://Private/Database/*")
        assertThat(item.isScope).isTrue()
        assertThat(item.recursive).isFalse()
        assertThat(item.path).isEqualTo("/Database")
        assertThat(parse("op://Private/Database/Extra/*").path).isEqualTo("/Database/Extra")

        val vault = parse("op://Private/**?connection=Work")
        assertThat(vault.isScope).isTrue()
        assertThat(vault.recursive).isTrue()
        assertThat(vault.path).isNull()
        assertThat(vault.connectionId).isEqualTo("Work")
        assertThat(vault.format()).isEqualTo("op://Private/**?connection=Work")
        assertThat(parse("op://Private/Database/password?attribute=value").key).isEqualTo("password")
    }

    @Test
    fun `round trips with encoded segments`() {
        listOf(
            "op://Private/Database/password",
            "op://Private/My%20Login%3A%20prod/pass%23word",
            "op://Private/Item/Section/field?connection=My%20Account",
            "op://Private/Item/%2A",
            "op://Private/Item/*",
            "op://Private/**",
            "op://Vault/%20padded/x",
        ).forEach { text -> assertThat(parse(parse(text).format())).isEqualTo(parse(text)) }
        assertThat(parse("op://Private/My%20Login%3A%20prod/x").path).isEqualTo("/My Login: prod")
        // an encoded star is a field called *, not an import
        assertThat(parse("op://Private/Item/%2A").key).isEqualTo("*")
        assertThat(parse("op://Private/Item/%2A").format()).isEqualTo("op://Private/Item/%2A")
        assertThat(parse("op://Private/Item/a+b").key).isEqualTo("a+b")
    }

    @Test
    fun `building references from the tool window`() {
        val field = OnePasswordGrammar.build("op", SecretScope("vaultid", "default", "/Database"), "password", SecretReference.Defaults("vaultid", "default"))
        assertThat(field.format()).isEqualTo("op://vaultid/Database/password")
        val item = OnePasswordGrammar.build("op", SecretScope("vaultid", "default", "/Database"), null, null)
        assertThat(item.format()).isEqualTo("op://vaultid/Database/*")
        val vault = OnePasswordGrammar.build("op", SecretScope("vaultid", "default", "/"), null, null)
        assertThat(vault.format()).isEqualTo("op://vaultid/**")
        assertThat(vault.recursive).isTrue()
    }

    @Test
    fun `malformed references are refused with a reason`() {
        mapOf(
            "op://Private" to "needs a vault, an item and a field",
            "op://Private/Database" to "needs a vault, an item and a field",
            "op://Private/*" to "names no item",
            "op://Private/Database/**" to "only for a whole vault",
            "op://Private//password" to "empty segment",
            "op://Private/Database/password/" to "empty segment",
            "op://Private/*/password" to "only be the last segment",
            "op://a/b/c/d/e" to "too many segments",
            "op://Private/a%2Fb/password" to "by its ID",
            "op://Private/Database/password?attribute=otp" to "attribute=otp is not supported",
            "op://Private/Database/password?ssh-format=openssh" to "field values only",
            "op://Private/Database/password?env=prod" to "Unknown parameter",
            "op://Private/ Database/password" to "whitespace",
            "op://Private/Database/%zz" to "malformed",
        ).forEach { (text, message) ->
            assertThatThrownBy { parse(text) }.describedAs(text).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
        }
    }

    @Test
    fun `other providers' references are unaffected`() {
        assertThat(parse("infisical://prod:backend/KEY").let { it.environment to it.path }).isEqualTo("prod" to "/backend")
        assertThat(parse("vault://secret/app/db#password").projectId).isEqualTo("secret")
        assertThat(SecretReference.parse("op://Private/Database/password", listOf("infisical"))).isNull()
        assertThat(SecretReference.parse("OP://Private/Database/password", listOf("op"))!!.scheme).isEqualTo("op")
    }

    @Test
    fun `names with emoji round trip`() {
        val reference = parse("op://Dev/%F0%9F%94%91%20API/password")
        assertThat(reference.format()).isEqualTo("op://Dev/%F0%9F%94%91 API/password")
        assertThat(parse(reference.format())).isEqualTo(reference)
    }
}
