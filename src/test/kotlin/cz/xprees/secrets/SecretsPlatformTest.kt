package cz.xprees.secrets

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.run.CommonParametersEnvPatcher
import cz.xprees.secrets.run.EnvPatcher
import cz.xprees.secrets.run.ExternalSystemEnvPatcher
import cz.xprees.secrets.run.SecretsProgramRunner
import cz.xprees.secrets.run.maven.MavenEnvPatcher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.idea.maven.execution.MavenRunConfiguration
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType

class SecretsPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockInfisical
    private lateinit var connection: SecretConnection

    override fun setUp() {
        super.setUp()
        mock = MockInfisical()
        connection = SecretConnection(
            id = "test-${System.nanoTime()}",
            providerId = InfisicalProvider.ID,
            name = "Test",
            baseUrl = mock.baseUrl,
            authMethodId = InfisicalAuthMethods.MACHINE_IDENTITY,
            options = mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to mock.clientId),
        )
        CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET), mock.clientSecret)
        SecretsAppSettings.getInstance().setConnections(listOf(connection))
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "p1"
            environment = null
            path = null
            useProjectFile = false
        }
        SecretsService.getInstance().invalidate()
    }

    override fun tearDown() {
        try {
            CredentialVault.removeAll(connection.id, listOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET))
            SecretsAppSettings.getInstance().setConnections(emptyList())
            mock.close()
        } finally {
            super.tearDown()
        }
    }

    fun `test client lists projects, folders and secrets with a bearer token`() {
        val client = SecretsService.getInstance().client(connection)
        assertThat(client.hasCredentials()).isTrue()
        assertThat(client.listProjects().single().environments.map { it.slug }).containsExactly("dev", "prod")
        assertThat(client.listFolders(SecretScope("p1", "dev")).single().path).isEqualTo("/backend")
        assertThat(client.listSecrets(SecretScope("p1", "dev")).map { it.key }).containsExactly("DB_PASSWORD", "API_KEY", "ConnectionStrings__Default")

        val secretsRequest = mock.requests.last { it.path == "/api/v4/secrets" }
        assertThat(secretsRequest.authorization).startsWith("Bearer token-")
        assertThat(secretsRequest.query).containsEntry("includeImports", "true").containsEntry("expandSecretReferences", "true")
        // the session was stored in the credential store and reused: one login for three calls
        assertThat(mock.requests.count { it.path.endsWith("/universal-auth/login") }).isEqualTo(1)
    }

    fun `test expired sessions are renewed once and the call retried`() {
        val client = SecretsService.getInstance().client(connection)
        client.listProjects()
        mock.expireTokens()
        assertThat(client.listProjects()).hasSize(1)
        assertThat(mock.requests.count { it.path.endsWith("/universal-auth/login") }).isEqualTo(2)
    }

    fun `test wrong machine identity secret is a configuration error`() {
        mock.clientSecret = "rotated"
        // setUp's binding change makes KeysIndex sign in in the background with the old secret; without this, a session it
        // stored would answer the call below and nothing would need the (now wrong) secret
        mock.expireTokens()
        assertThatThrownBy { SecretsService.getInstance().client(connection).listProjects() }
            .isInstanceOf(SecretProviderException::class.java)
            .extracting { (it as SecretProviderException).kind }.isEqualTo(SecretProviderException.Kind.CONFIGURATION)
    }

    fun `test launch resolution uses the binding and run configuration environment override`() {
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(linkedMapOf("ALL" to "infisical://*", "PROD_DB" to "infisical://prod/DB_PASSWORD", "PLAIN" to "x")),
        )
        assertThat(result.environment).containsEntry("DB_PASSWORD", "dev-pass").containsEntry("API_KEY", "dev-key")
            .containsEntry("PROD_DB", "prod-pass").containsEntry("PLAIN", "x").doesNotContainKey("ALL")

        val overridden = resolver.resolve(resolver.plan(mapOf("DB" to "infisical://DB_PASSWORD", "INFISICAL_ENVIRONMENT" to "prod")))
        assertThat(overridden.environment).containsEntry("DB", "prod-pass")

        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "infisical://staging/DB_PASSWORD"))) }
            .isInstanceOf(SecretProviderException::class.java)
    }

    fun `test only the bound project may be used when restricted`() {
        val resolver = LaunchSecretResolver.getInstance(project)
        val other = resolver.plan(mapOf("X" to "infisical://DB_PASSWORD?project=someone-elses"))
        val otherConnection = resolver.plan(mapOf("X" to "infisical://DB_PASSWORD?connection=Other"))
        assertThat(ProjectSecretsSettings.getInstance(project).state.restrictToBoundProject).isTrue()
        assertThatThrownBy { resolver.resolve(other) }.isInstanceOf(SecretProviderException::class.java).hasMessageContaining("another project")
        assertThatThrownBy { resolver.resolve(otherConnection) }.isInstanceOf(SecretProviderException::class.java).hasMessageContaining("another connection")
        // the bound project named explicitly is fine
        assertThat(resolver.resolve(resolver.plan(mapOf("X" to "infisical://DB_PASSWORD?project=p1"))).environment).containsEntry("X", "dev-pass")

        ProjectSecretsSettings.getInstance(project).update { restrictToBoundProject = false }
        try {
            // the mock serves every project the same secrets
            assertThat(resolver.resolve(other).environment).containsEntry("X", "dev-pass")
        } finally {
            ProjectSecretsSettings.getInstance(project).update { restrictToBoundProject = true }
        }
    }

    fun `test binding environment precedence`() {
        val bindings = SecretBindingService.getInstance(project)
        val provider = InfisicalProvider()
        assertThat(bindings.binding(provider).environment).isEqualTo("dev")
        assertThat(bindings.binding(provider).environmentSource).isEqualTo("default")
        ProjectSecretsSettings.getInstance(project).update { environment = "staging" }
        assertThat(bindings.binding(provider).environment).isEqualTo("staging")
        assertThat(bindings.binding(provider, environmentOverride = "prod").environment).isEqualTo("prod")
    }

    fun `test application configuration copy never touches the original`() {
        val configuration = ApplicationConfiguration("App", project, ApplicationConfigurationType.getInstance())
        configuration.envs = linkedMapOf("DB" to "infisical://DB_PASSWORD", "PLAIN" to "x")
        val (patcher, envs) = EnvPatcher.find(configuration)!!
        assertThat(patcher).isInstanceOf(CommonParametersEnvPatcher::class.java)
        assertThat(envs).containsEntry("DB", "infisical://DB_PASSWORD")

        val copy = patcher.copyWith(configuration, mapOf("DB" to "dev-pass", "PLAIN" to "x")) as ApplicationConfiguration
        assertThat(copy).isNotSameAs(configuration)
        assertThat(copy.envs).containsEntry("DB", "dev-pass")
        assertThat(configuration.envs).containsEntry("DB", "infisical://DB_PASSWORD")
        assertThat(configuration.envs.values).doesNotContain("dev-pass")
    }

    fun `test gradle configuration copy never touches the original`() {
        val type = GradleExternalTaskConfigurationType.getInstance()
        val configuration = type.factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration
        configuration.settings.env = linkedMapOf("DB" to "infisical://DB_PASSWORD")
        val (patcher, _) = EnvPatcher.find(configuration)!!
        assertThat(patcher).isInstanceOf(ExternalSystemEnvPatcher::class.java)
        val copy = patcher.copyWith(configuration, mapOf("DB" to "dev-pass")) as ExternalSystemRunConfiguration
        assertThat(copy.settings.env).containsEntry("DB", "dev-pass")
        assertThat(configuration.settings.env).containsEntry("DB", "infisical://DB_PASSWORD")
    }

    fun `test maven configuration copy never touches the original or the project defaults`() {
        val configuration = MavenRunConfigurationType.getInstance().configurationFactories.first()
            .createTemplateConfiguration(project) as MavenRunConfiguration
        configuration.runnerSettings = MavenRunnerSettings().apply { environmentProperties = linkedMapOf("DB" to "infisical://DB_PASSWORD") }
        val (patcher, envs) = EnvPatcher.find(configuration)!!
        assertThat(patcher).isInstanceOf(MavenEnvPatcher::class.java)
        assertThat(envs).containsEntry("DB", "infisical://DB_PASSWORD")
        val copy = patcher.copyWith(configuration, mapOf("DB" to "dev-pass")) as MavenRunConfiguration
        assertThat(copy.runnerSettings!!.environmentProperties).containsEntry("DB", "dev-pass")
        assertThat(configuration.runnerSettings!!.environmentProperties).containsEntry("DB", "infisical://DB_PASSWORD")
    }

    fun `test runner claims only configurations with references`() {
        val runner = SecretsProgramRunner()
        val configuration = ApplicationConfiguration("App", project, ApplicationConfigurationType.getInstance())
        configuration.mainClassName = "Main"
        assertThat(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration)).isFalse()
        configuration.envs = mapOf("DB" to "infisical://DB_PASSWORD")
        assertThat(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration)).isTrue()
    }
}
