package cz.xprees.secrets

import cz.xprees.secrets.aws.AwsNameLayout
import cz.xprees.secrets.aws.AwsSecretNames
import cz.xprees.secrets.core.provider.SecretProviderException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class AwsNameLayoutTest {
    private fun layout(template: String) = AwsNameLayout.parse(template)

    @Test
    fun `the default layout is the original mapping, including names without a prefix`() {
        for (template in listOf("", "  ", AwsNameLayout.DEFAULT)) {
            val layout = AwsNameLayout.parse(template)
            assertThat(layout.isDefault).isTrue()
            for (name in listOf("myapp/dev", "team/myapp/prod", "/prod/web", "/web", "legacy-token", "a//b", "a/")) {
                assertThat(layout.split(name)).describedAs(name).isEqualTo(AwsSecretNames.split(name))
            }
            assertThat(layout.join(AwsSecretNames.NO_PREFIX, "token")).isEqualTo("token")
            assertThat(layout.join("team/myapp", "dev")).isEqualTo("team/myapp/dev")
            assertThat(layout.fixedPrefix).isEmpty()
        }
    }

    @Test
    fun `environment first takes the first segment as the environment and the rest as the project`() {
        val layout = layout("{env}/{project}")
        assertThat(layout.split("dev/myapp")).isEqualTo("myapp" to "dev")
        assertThat(layout.split("prod/team/web")).isEqualTo("team/web" to "prod")
        assertThat(layout.split("token")).isNull()
        assertThat(layout.split("dev/")).isNull()
        assertThat(layout.split("dev//x")).isNull()
        assertThat(layout.join("team/web", "prod")).isEqualTo("prod/team/web")
        assertThat(layout.fixedPrefix).isEmpty()
        assertThatThrownBy { layout.join(AwsSecretNames.NO_PREFIX, "dev") }.isInstanceOf(SecretProviderException::class.java)
        assertThatThrownBy { layout.join("web", "a/b") }.isInstanceOf(SecretProviderException::class.java)
    }

    @Test
    fun `literal parts are matched exactly and the leading one is the server-side prefix`() {
        val layout = layout("acme/{env}/{project}")
        assertThat(layout.split("acme/dev/web")).isEqualTo("web" to "dev")
        assertThat(layout.split("acme/dev/team/web")).isEqualTo("team/web" to "dev")
        assertThat(layout.split("other/dev/web")).isNull()
        assertThat(layout.split("acme/dev")).isNull()
        assertThat(layout.fixedPrefix).isEqualTo("acme/")
        assertThat(layout.join("web", "prod")).isEqualTo("acme/prod/web")

        val suffixed = layout("{project}/{env}/config")
        assertThat(suffixed.split("myapp/dev/config")).isEqualTo("myapp" to "dev")
        assertThat(suffixed.split("myapp/dev")).isNull()
    }

    @Test
    fun `separators other than slash leave the environment as short as possible`() {
        val dashed = layout("{project}-{env}")
        assertThat(dashed.split("my-app-dev")).isEqualTo("my-app" to "dev")
        assertThat(dashed.split("team/app-prod")).isEqualTo("team/app" to "prod")
        assertThat(dashed.split("app-dev/x")).isNull()
        assertThat(dashed.split("nodash")).isNull()
        assertThat(dashed.join("my-app", "dev")).isEqualTo("my-app-dev")
        // an environment the name couldn't be read back as is refused when creating
        assertThatThrownBy { dashed.joinForCreate("my", "app-dev") }.hasMessageContaining("would not be read back")
        assertThat(dashed.joinForCreate("my-app", "qa")).isEqualTo("my-app-qa")

        val envFirst = layout("{env}.{project}")
        assertThat(envFirst.split("dev.my.app")).isEqualTo("my.app" to "dev")
    }

    @Test
    fun `invalid templates are refused with a reason`() {
        mapOf(
            "{project}{env}" to "separator",
            "{project}" to "exactly once",
            "{env}/{env}/{project}" to "exactly once",
            "{proj}/{env}/{project}" to "Only {project} and {env}",
            "my app/{project}/{env}" to "may only contain",
            "{project}//{env}" to "//",
            "{project}/{env}/" to "end with /",
        ).forEach { (template, reason) ->
            assertThat(AwsNameLayout.problem(template)).describedAs(template).contains(reason)
        }
        assertThat(AwsNameLayout.problem("{env}/{project}")).isNull()
        assertThat(AwsNameLayout.problem("")).isNull()
    }

    @Test
    fun `the server prefix combines the layout and the name prefix option`() {
        assertThat(AwsNameLayout.serverPrefix(layout("acme/{env}/{project}"), null)).isEqualTo("acme/")
        assertThat(AwsNameLayout.serverPrefix(layout("acme/{env}/{project}"), "acme/prod/")).isEqualTo("acme/prod/")
        assertThat(AwsNameLayout.serverPrefix(layout("acme/{env}/{project}"), "ac")).isEqualTo("acme/")
        assertThat(AwsNameLayout.serverPrefix(layout("acme/{env}/{project}"), "other/")).isNull()
        assertThat(AwsNameLayout.serverPrefix(layout("{project}/{env}"), "team/")).isEqualTo("team/")
        assertThat(AwsNameLayout.serverPrefix(layout("{project}/{env}"), null)).isEmpty()
    }
}
