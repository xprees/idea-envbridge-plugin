package cz.xprees.secrets

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.ReferenceCompletion
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.EnvReferences
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
        assertThatThrownBy { parse("vault://secret/app#f?env=prod") }.hasMessageContaining("belong to the short form")
        assertThatThrownBy { parse("vault://secret/app#f?project=secret/x/{env}") }.hasMessageContaining("belong to the short form")
        assertThatThrownBy { parse("vault://secret/app#f?path=/x") }.hasMessageContaining("Unknown parameter")
    }

    @Test
    fun `short form names the bound project's root or a folder of it`() {
        val root = parse("vault://#password")
        assertThat(root).isEqualTo(SecretReference("vault", key = "password"))
        assertThat(root.format()).isEqualTo("vault://#password")
        val folder = parse("vault://./db/main#password")
        assertThat(folder).isEqualTo(SecretReference("vault", key = "password", path = "/db/main"))
        assertThat(folder.format()).isEqualTo("vault://./db/main#password")
        // "./" alone is the root too
        assertThat(parse("vault://./#password")).isEqualTo(root)
        assertThat(parse("vault://.#password")).isEqualTo(root)
        // scope imports
        assertThat(parse("vault://./db#*").isScope).isTrue()
        assertThat(parse("vault://#**").let { it.isScope && it.recursive && it.path == null }).isTrue()
        assertThat(VaultGrammar.isShort(folder)).isTrue()
        assertThat(VaultGrammar.isShort(parse("vault://secret/app/db#password"))).isFalse()
    }

    @Test
    fun `short form fixes the environment or names a layout project with parameters`() {
        val prod = parse("vault://./db#password?env=prod&connection=Work")
        assertThat(prod).isEqualTo(SecretReference("vault", key = "password", environment = "prod", path = "/db", connectionId = "Work"))
        assertThat(prod.format()).isEqualTo("vault://./db#password?env=prod&connection=Work")
        val other = parse("vault://./db?project=secret/other/{env}#password")
        assertThat(other.projectId).isEqualTo("secret/other/{env}")
        assertThat(other.format()).isEqualTo("vault://./db#password?project=secret/other/{env}")
        // the encoded form parses the same
        assertThat(parse("vault://./db#password?project=secret%2Fother%2F%7Benv%7D")).isEqualTo(other)
        // a layout project of a mount with a slash in its name
        assertThat(parse("vault://#k?project=kv%252Fteam/{env}/app").projectId).isEqualTo("kv%2Fteam/{env}/app")
    }

    @Test
    fun `short and full forms round trip`() {
        listOf(
            "vault://#password",
            "vault://./db#password",
            "vault://./my%20db/x#api.key?env=prod",
            "vault://./db#*?env=dev&project=secret/myapp/{env}",
            "vault://#**?connection=Work",
            "vault://secret/myapp/dev/db#password",
            "vault://secret/myapp/dev/db#password?namespace=team-a",
        ).forEach { text ->
            val ref = parse(text)
            assertThat(ref.format()).isEqualTo(text)
            assertThat(parse(ref.format())).isEqualTo(ref)
        }
    }

    @Test
    fun `malformed short references are rejected`() {
        assertThatThrownBy { parse("vault://./db") }.hasMessageContaining("#field")
        assertThatThrownBy { parse("vault://#") }.hasMessageContaining("names no field")
        assertThatThrownBy { parse("vault://./a/../b#f") }.hasMessageContaining(". or ..")
        assertThatThrownBy { parse("vault://././b#f") }.hasMessageContaining(". or ..")
        assertThatThrownBy { parse("vault://./db#f?namespace=team-a") }.hasMessageContaining("?namespace= belongs to the full form")
        assertThatThrownBy { parse("vault://./db#f?project=secret") }.hasMessageContaining("isn't an environment layout project")
        assertThatThrownBy { parse("vault://./db#f?project=secret/{env}/{env}") }.hasMessageContaining("isn't an environment layout project")
        assertThatThrownBy { parse("vault://./db#f?path=/x") }.hasMessageContaining("Unknown parameter")
        assertThatThrownBy { parse("vault://./my db#f") }.hasMessageContaining("whitespace")
        // a "." only marks the short form as the first segment
        assertThatThrownBy { parse("vault://secret/./db#f") }.hasMessageContaining(". or ..")
    }

    @Test
    fun `short references take project and environment from the binding`() {
        val defaults = SecretReference.Defaults("secret/myapp/{env}", "dev")
        assertThat(parse("vault://./db#password").scope(defaults)).isEqualTo(SecretScope("secret/myapp/{env}", "dev", "/db"))
        assertThat(parse("vault://#password?env=prod").scope(defaults)).isEqualTo(SecretScope("secret/myapp/{env}", "prod", "/"))
        // without a layout: the bound mount and namespace
        assertThat(parse("vault://./app/db#password").scope(SecretReference.Defaults("secret", "root")))
            .isEqualTo(SecretScope("secret", "root", "/app/db"))
    }

    @Test
    fun `layout scopes are built short in the bound project and as full paths elsewhere`() {
        val bound = SecretReference.Defaults("secret/myapp/{env}", "dev")
        fun build(scope: SecretScope, key: String?, defaults: SecretReference.Defaults?) = VaultGrammar.build("vault", scope, key, defaults).format()
        assertThat(build(SecretScope("secret/myapp/{env}", "dev", "/db"), "password", bound)).isEqualTo("vault://./db#password")
        assertThat(build(SecretScope("secret/myapp/{env}", "dev", "/"), "password", bound)).isEqualTo("vault://#password")
        assertThat(build(SecretScope("secret/myapp/{env}", "prod", "/db"), "password", bound)).isEqualTo("vault://./db#password?env=prod")
        assertThat(build(SecretScope("secret/myapp/{env}", "dev", "/db", recursive = true), null, bound)).isEqualTo("vault://./db#**")
        // another project, or no binding: the full path
        assertThat(build(SecretScope("secret/other/{env}", "prod", "/db"), "password", bound)).isEqualTo("vault://secret/other/prod/db#password")
        assertThat(build(SecretScope("secret/{env}/myapp", "dev", "/"), "password", null)).isEqualTo("vault://secret/dev/myapp#password")
        assertThat(build(SecretScope("kv%2Fteam/{env}", "dev", "/db"), "k", null)).isEqualTo("vault://kv%2Fteam/dev/db#k")
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

    @Test
    fun `completion contexts - mount, path segments, then fields after the hash`() {
        fun context(body: String) = VaultGrammar.completionContext(body)
        assertThat(context("")).isEqualTo(ReferenceCompletion.Project(""))
        assertThat(context("sec")).isEqualTo(ReferenceCompletion.Project("sec"))
        assertThat(context("secret/")).isEqualTo(ReferenceCompletion.Folder("", "secret", "/"))
        assertThat(context("secret/myapp/d")).isEqualTo(ReferenceCompletion.Folder("d", "secret", "/myapp"))
        // the hash: fields of the secret written before it (this broke the old folder pattern)
        assertThat(context("secret/myapp/db#")).isEqualTo(ReferenceCompletion.Key("", "secret", "/myapp/db"))
        assertThat(context("secret/myapp/db#pass")).isEqualTo(ReferenceCompletion.Key("pass", "secret", "/myapp/db"))
        assertThat(context("my%20mount/a%20b#k")).isEqualTo(ReferenceCompletion.Key("k", "my mount", "/a b"))
        // the short forms of an environment layout
        assertThat(context("#")).isEqualTo(ReferenceCompletion.Key("", null, null))
        assertThat(context("#MO")).isEqualTo(ReferenceCompletion.Key("MO", null, null))
        assertThat(context("./")).isEqualTo(ReferenceCompletion.Folder("", null, "/"))
        assertThat(context("./api/")).isEqualTo(ReferenceCompletion.Folder("", null, "/api"))
        assertThat(context("./db#")).isEqualTo(ReferenceCompletion.Key("", null, "/db"))
        assertThat(context(".#K")).isEqualTo(ReferenceCompletion.Key("K", null, null))
        // no place to complete: a mount alone before #, the query, a stray slash after #
        assertThat(context("secret#")).isNull()
        assertThat(context(".")).isNull()
        assertThat(context("secret/app/db#password?namespace=")).isNull()
        assertThat(context("secret/app/db#a/b")).isNull()
        assertThat(context("secret//x")).isNull()
    }

    @Test
    fun `completion inside a vault reference in a line, and a comment after one`() {
        val schemes = listOf("infisical", "vault")
        assertThat(EnvReferences.referenceCompletion("DB=vault://secret/myapp/db#pa", schemes))
            .isEqualTo("vault" to ReferenceCompletion.Key("pa", "secret", "/myapp/db"))
        assertThat(EnvReferences.referenceCompletion("""x = "vault://./db#""", schemes)).isEqualTo("vault" to ReferenceCompletion.Key("", null, "/db"))
        // raw whitespace ends a Vault reference; an infisical:// reference keeps the default completion
        assertThat(EnvReferences.referenceCompletion("DB=vault://secret/my app", schemes)).isNull()
        assertThat(EnvReferences.referenceCompletion("DB=infisical://", schemes)).isNull()
        // a ` # comment` after a Vault reference isn't part of it
        val found = EnvReferences.find("DB=vault://secret/app/db#password # the database\n", schemes).single()
        assertThat(found.name).isEqualTo("password")
        assertThat(found.reference!!.path).isEqualTo("/app/db")
    }
}
