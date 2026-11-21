package cz.xprees.secrets

import cz.xprees.secrets.aws.AwsGrammar
import cz.xprees.secrets.aws.AwsSecretNames
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test

class AwsGrammarTest {
    @Before
    fun setUp() {
        SecretReference.grammarOverrides["aws-sm"] = AwsGrammar
    }

    @After
    fun tearDown() {
        SecretReference.grammarOverrides.remove("aws-sm")
    }

    private fun parse(text: String) = SecretReference.parse(text, listOf("infisical", "aws-sm"))!!

    @Test
    fun `secret names map to prefix and environment`() {
        assertThat(AwsSecretNames.split("myapp/dev")).isEqualTo("myapp" to "dev")
        assertThat(AwsSecretNames.split("team/myapp/prod")).isEqualTo("team/myapp" to "prod")
        assertThat(AwsSecretNames.split("/prod/web")).isEqualTo("/prod" to "web")
        assertThat(AwsSecretNames.split("/web")).isEqualTo("/" to "web")
        assertThat(AwsSecretNames.split("legacy-token")).isEqualTo(AwsSecretNames.NO_PREFIX to "legacy-token")
        for (name in listOf("myapp/dev", "team/myapp/prod", "/prod/web", "/web", "legacy-token", "a+b=c@d.e_f-g/h")) {
            val (project, environment) = AwsSecretNames.split(name)!!
            assertThat(AwsSecretNames.join(project, environment)).isEqualTo(name)
        }
        // names the mapping can't express
        assertThat(AwsSecretNames.split("myapp/")).isNull()
        assertThat(AwsSecretNames.split("a//b")).isNull()
        assertThat(AwsSecretNames.split("")).isNull()
    }

    @Test
    fun `full names, any prefix depth`() {
        val ref = parse("aws-sm://myapp/dev#DB_PASSWORD")
        assertThat(ref.projectId).isEqualTo("myapp")
        assertThat(ref.environment).isEqualTo("dev")
        assertThat(ref.key).isEqualTo("DB_PASSWORD")
        assertThat(ref.path).isNull()
        assertThat(ref.isScope).isFalse()
        assertThat(ref.format()).isEqualTo("aws-sm://myapp/dev#DB_PASSWORD")

        val deep = parse("aws-sm://team/myapp/prod#API_KEY")
        assertThat(deep.projectId).isEqualTo("team/myapp")
        assertThat(deep.environment).isEqualTo("prod")
        assertThat(deep.format()).isEqualTo("aws-sm://team/myapp/prod#API_KEY")

        val rooted = parse("aws-sm:///prod/web#KEY")
        assertThat(rooted.projectId).isEqualTo("/prod")
        assertThat(rooted.environment).isEqualTo("web")
        assertThat(rooted.format()).isEqualTo("aws-sm:///prod/web#KEY")

        val single = parse("aws-sm://legacy-token#legacy-token")
        assertThat(single.projectId).isEqualTo(AwsSecretNames.NO_PREFIX)
        assertThat(single.environment).isEqualTo("legacy-token")
        assertThat(single.format()).isEqualTo("aws-sm://legacy-token#legacy-token")
    }

    @Test
    fun `scope imports`() {
        val all = parse("aws-sm://myapp/dev#*")
        assertThat(all.isScope).isTrue()
        assertThat(all.recursive).isFalse()
        assertThat(all.format()).isEqualTo("aws-sm://myapp/dev#*")
        // ** means the same as * (there are no folders below a secret)
        assertThat(parse("aws-sm://myapp/dev#**")).isEqualTo(all)
        // only a raw star imports: %2A is a key called *
        val star = parse("aws-sm://myapp/dev#%2A")
        assertThat(star.key).isEqualTo("*")
        assertThat(star.format()).isEqualTo("aws-sm://myapp/dev#%2A")
        assertThat(parse(star.format())).isEqualTo(star)
    }

    @Test
    fun `the short form follows the binding`() {
        val bound = parse("aws-sm://#DB_PASSWORD")
        assertThat(bound.projectId).isNull()
        assertThat(bound.environment).isNull()
        assertThat(bound.key).isEqualTo("DB_PASSWORD")
        assertThat(bound.format()).isEqualTo("aws-sm://#DB_PASSWORD")
        assertThat(bound.scope(SecretReference.Defaults("myapp", "stg"))).isEqualTo(SecretScope("myapp", "stg"))

        val otherEnvironment = parse("aws-sm://#DB_PASSWORD?env=prod")
        assertThat(otherEnvironment.environment).isEqualTo("prod")
        assertThat(otherEnvironment.projectId).isNull()
        assertThat(otherEnvironment.format()).isEqualTo("aws-sm://#DB_PASSWORD?env=prod")

        val otherProject = parse("aws-sm://#*?project=team/web")
        assertThat(otherProject.projectId).isEqualTo("team/web")
        assertThat(otherProject.format()).isEqualTo("aws-sm://#*?project=team/web")
        assertThat(parse(otherProject.format())).isEqualTo(otherProject)
    }

    @Test
    fun `connection parameter before or after the key`() {
        val after = parse("aws-sm://myapp/dev#DB_PASSWORD?connection=Work")
        val before = parse("aws-sm://myapp/dev?connection=Work#DB_PASSWORD")
        assertThat(after).isEqualTo(before)
        assertThat(after.connectionId).isEqualTo("Work")
        assertThat(after.format()).isEqualTo("aws-sm://myapp/dev#DB_PASSWORD?connection=Work")
        assertThat(parse(after.format())).isEqualTo(after)
    }

    @Test
    fun `names and keys outside the plain characters are percent-encoded`() {
        // AWS allows /_+=.@- in names: written raw; + stays a plus
        val plain = parse("aws-sm://team@x/app+v2=1#KEY")
        assertThat(plain.projectId).isEqualTo("team@x")
        assertThat(plain.environment).isEqualTo("app+v2=1")
        assertThat(plain.format()).isEqualTo("aws-sm://team@x/app+v2=1#KEY")

        // A managed secret's ! and a key with a space or #
        val managed = SecretReference("aws-sm", key = "my key#1", environment = "rds!db-1234", projectId = AwsSecretNames.NO_PREFIX)
        assertThat(managed.format()).isEqualTo("aws-sm://rds%21db-1234#my%20key%231")
        assertThat(parse(managed.format())).isEqualTo(managed)
    }

    @Test
    fun `build writes the full name unless the scope is the bound secret`() {
        val dev = SecretScope("myapp", "dev")
        assertThat(AwsGrammar.build("aws-sm", dev, "DB_PASSWORD", null).format()).isEqualTo("aws-sm://myapp/dev#DB_PASSWORD")
        assertThat(AwsGrammar.build("aws-sm", dev, "DB_PASSWORD", SecretReference.Defaults("myapp", "dev")).format()).isEqualTo("aws-sm://#DB_PASSWORD")
        assertThat(AwsGrammar.build("aws-sm", dev, null, SecretReference.Defaults("myapp", "prod")).format()).isEqualTo("aws-sm://myapp/dev#*")
        assertThat(AwsGrammar.build("aws-sm", dev, "K", SecretReference.Defaults("other", "dev")).format()).isEqualTo("aws-sm://myapp/dev#K")
        assertThat(AwsGrammar.build("aws-sm", SecretScope(AwsSecretNames.NO_PREFIX, "legacy"), "legacy", null).format()).isEqualTo("aws-sm://legacy#legacy")
    }

    @Test
    fun `malformed references are refused with a clear message`() {
        assertThatThrownBy { parse("aws-sm://myapp/dev") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("add #KEY")
        assertThatThrownBy { parse("aws-sm://myapp/dev#") }.hasMessageContaining("names no key")
        assertThatThrownBy { parse("aws-sm://myapp/dev #KEY") }.hasMessageContaining("whitespace")
        assertThatThrownBy { parse("aws-sm://myapp/*#KEY") }.hasMessageContaining("* may only follow #")
        assertThatThrownBy { parse("aws-sm://myapp/#KEY") }.hasMessageContaining("may not end with /")
        assertThatThrownBy { parse("aws-sm://a//b#KEY") }.hasMessageContaining("contain //")
        assertThatThrownBy { parse("aws-sm://myapp/dev#KEY?region=eu-west-1") }.hasMessageContaining("Unknown parameter").hasMessageContaining("?connection=")
        assertThatThrownBy { parse("aws-sm://myapp/dev#KEY?env=prod") }.hasMessageContaining("can't be combined")
        assertThatThrownBy { parse("aws-sm://#KEY?env=a/b") }.hasMessageContaining("can't contain /")
    }

    @Test
    fun `other schemes are unaffected`() {
        assertThat(SecretReference.parse("infisical://prod:KEY", listOf("infisical", "aws-sm"))!!.environment).isEqualTo("prod")
        assertThat(SecretReference.parse("plain value", listOf("aws-sm"))).isNull()
        assertThat(SecretReference.parse("vault://secret/a#b", listOf("aws-sm"))).isNull()
    }
}
