package cz.xprees.secrets.integration

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.aws.AwsApi
import cz.xprees.secrets.aws.AwsAuthMethods
import cz.xprees.secrets.aws.AwsHttp
import cz.xprees.secrets.aws.AwsKeys
import cz.xprees.secrets.aws.AwsSecretsManagerProvider
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.UUID

/**
 * The AWS Secrets Manager provider against LocalStack (`scripts/integration/start.sh` starts it with Secrets Manager only;
 * `setup.sh` seeds `envbridge-it/dev`, `envbridge-it/prod` and the plain-string `envbridge-it-plain/TOKEN`): listing, reading,
 * launches resolving `aws-sm://` references, and writes, including the check-and-set against LocalStack's implementation of
 * `UpdateSecretVersionStage`. Writes go to secrets under a fresh `envbridge-rw/run-…` prefix.
 */
class AwsIntegrationTest : BasePlatformTestCase() {
    private val endpoint get() = IntegrationEnv.require("ENVBRIDGE_IT_AWS_ENDPOINT")
    private val region get() = IntegrationEnv.require("ENVBRIDGE_IT_AWS_REGION")
    private val connections = mutableListOf<SecretConnection>()

    override fun tearDown() {
        try {
            connections.forEach { CredentialVault.removeAll(it.id, AwsAuthMethods.SECRET_KEYS) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
        } finally {
            super.tearDown()
        }
    }

    private fun keys() = AwsKeys(IntegrationEnv.require("ENVBRIDGE_IT_AWS_ACCESS_KEY_ID"), IntegrationEnv.require("ENVBRIDGE_IT_AWS_SECRET_ACCESS_KEY"))

    private fun connection(): SecretConnection {
        val connection = SecretConnection(
            id = "it-aws-${System.nanoTime()}",
            providerId = AwsSecretsManagerProvider.ID,
            name = "LocalStack ${connections.size}",
            baseUrl = endpoint,
            authMethodId = AwsAuthMethods.ACCESS_KEY,
            options = mapOf(AwsAuthMethods.OPTION_REGION to region),
        )
        val keys = keys()
        CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(AwsAuthMethods.OPTION_ACCESS_KEY_ID), keys.accessKeyId)
        CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(AwsAuthMethods.OPTION_SECRET_ACCESS_KEY), keys.secretAccessKey)
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun client(connection: SecretConnection) = SecretsService.getInstance().client(connection)

    private val interaction = object : SignInInteraction {
        override fun openBrowser(url: String) = Unit
        override fun isCancelled(): Boolean = false
    }

    private val api get() = AwsApi(AwsHttp(endpoint, region))

    private fun current(name: String) = api.getSecretValue(keys(), name)

    private fun fields(name: String) = JsonParser.parseString(current(name).string!!).asJsonObject

    fun `test lists prefixes, environments and keys, JSON and plain strings`() {
        val client = client(connection())
        assertThat(client.signIn(interaction)).contains(region)
        val projects = client.listProjects()
        assertThat(projects.first { it.id == "envbridge-it" }.environments.map { it.slug }).contains("dev", "prod")
        assertThat(projects.first { it.id == "envbridge-it-plain" }.environments.map { it.slug }).contains("TOKEN")

        val dev = client.listSecrets(SecretScope("envbridge-it", "dev")).associate { it.key to it.value.reveal() }
        assertThat(dev).containsEntry("DB_PASSWORD", "dev-db-pass").containsEntry("API_KEY", "dev-api-key").containsEntry("PORT", "5432")
        val token = client.listSecrets(SecretScope("envbridge-it-plain", "TOKEN")).single()
        assertThat(token.key).isEqualTo("TOKEN")
        assertThat(token.value.reveal()).isEqualTo("plain-token")

        val missing = runCatching { client.listSecrets(SecretScope("envbridge-it", "missing")) }.exceptionOrNull() as SecretProviderException
        assertThat(missing.kind).isEqualTo(Kind.NOT_FOUND)
    }

    fun `test launches resolve aws-sm references`() {
        val connection = connection()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "envbridge-it"
            environment = "dev"
            path = null
            useProjectFile = false
            restrictToBoundProject = false
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(
                linkedMapOf(
                    "DB_PASSWORD" to "aws-sm://#DB_PASSWORD",
                    "PROD_PASSWORD" to "aws-sm://envbridge-it/prod#DB_PASSWORD",
                    "ALL" to "aws-sm://envbridge-it/dev#*",
                    "TOKEN" to "aws-sm://envbridge-it-plain/TOKEN#TOKEN",
                ),
            ),
        )
        assertThat(result.environment)
            .containsEntry("DB_PASSWORD", "dev-db-pass")
            .containsEntry("PROD_PASSWORD", "prod-db-pass")
            .containsEntry("API_KEY", "dev-api-key")
            .containsEntry("PORT", "5432")
            .containsEntry("TOKEN", "plain-token")
            .doesNotContainKey("ALL")
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "aws-sm://envbridge-it/dev#MISSING"))) }.isInstanceOf(SecretProviderException::class.java)
    }

    fun `test keys are created, renamed, moved, copied and deleted`() {
        val connection = connection()
        val service = SecretsService.getInstance()
        val prefix = "envbridge-rw/run-${System.nanoTime()}"
        val created = service.createEnvironment(connection, prefix, "Development", "dev")
        assertThat(created.slug).isEqualTo("dev")
        val dev = SecretScope(prefix, "dev")
        val stg = SecretScope(prefix, "stg")

        service.createSecret(connection, dev, "TOKEN", SecretValue("t1"))
        service.createSecret(connection, dev, "URL", SecretValue("https://example.test"))
        service.updateSecret(connection, dev, "TOKEN", SecretValue("t2"))
        assertThat(client(connection).listSecrets(dev).associate { it.key to it.value.reveal() })
            .containsExactlyEntriesOf(linkedMapOf("TOKEN" to "t2", "URL" to "https://example.test"))
        assertThatThrownBy { service.createSecret(connection, dev, "TOKEN", SecretValue("x")) }.hasMessageContaining("already has a key TOKEN")

        service.updateSecret(connection, dev, "TOKEN", SecretValue("t2"), null, "API_TOKEN")
        assertThat(fields("$prefix/dev").keySet()).containsExactly("API_TOKEN", "URL")
        assertThatThrownBy { service.updateSecret(connection, dev, "API_TOKEN", SecretValue("x"), null, "URL") }.hasMessageContaining("already has a key URL")

        // moving into an environment that doesn't exist yet creates its secret
        val moved = service.moveSecrets(connection, dev, client(connection).listSecrets(dev).filter { it.key == "URL" }, stg, overwrite = false)
        assertThat(moved.moved).containsExactly("URL")
        assertThat(fields("$prefix/stg").get("URL").asString).isEqualTo("https://example.test")
        assertThat(fields("$prefix/dev").has("URL")).isFalse()

        val copied = service.copySecrets(connection, dev, client(connection).listSecrets(dev), stg)
        assertThat(copied.copied).containsExactly("API_TOKEN")
        assertThat(fields("$prefix/stg").keySet()).containsExactlyInAnyOrder("URL", "API_TOKEN")

        service.deleteSecrets(connection, dev, listOf("API_TOKEN")).getValue("API_TOKEN").getOrThrow()
        // the AWS secret stays, empty
        assertThat(current("$prefix/dev").string).isEqualTo("{}")
    }

    fun `test the staging-label check-and-set refuses a write made meanwhile`() {
        val connection = connection()
        val keys = keys()
        val name = "envbridge-rw/cas-${System.nanoTime()}/dev"
        api.createSecret(keys, name, """{"A":"1"}""")
        val read = current(name)

        // Someone else writes after our read: the version we would replace is no longer current
        api.putSecretValue(keys, name, """{"A":"other"}""", UUID.randomUUID().toString(), listOf(AwsApi.CURRENT))
        val ours = UUID.randomUUID().toString()
        api.putSecretValue(keys, name, """{"A":"mine"}""", ours, listOf(AwsApi.PENDING))
        val refused = api.updateVersionStage(keys, name, AwsApi.CURRENT, moveTo = ours, removeFrom = read.versionId)
        assertThat(refused.isSuccess).isFalse()
        assertThat(AwsHttp.errorCode(refused)).isEqualTo("InvalidParameterException")
        assertThat(current(name).string).isEqualTo("""{"A":"other"}""")

        // Through the provider, a fresh read writes normally and leaves no helper label behind
        val scope = SecretScope(name.substringBeforeLast('/'), "dev")
        SecretsService.getInstance().updateSecret(connection, scope, "A", SecretValue("final"))
        assertThat(fields(name).get("A").asString).isEqualTo("final")
    }
}
