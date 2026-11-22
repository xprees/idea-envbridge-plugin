package cz.xprees.secrets.integration

import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.run.EnvFileReferences
import cz.xprees.secrets.run.LaunchChecks
import cz.xprees.secrets.run.VarlockLaunch
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files

/**
 * The varlock mode against real servers: a `.env.schema` with `infisical(…)` and `vaultSecret(…)` (two instances of Infisical,
 * one of Vault matched by its URL) is resolved through EnvBridge's own connections, as the program runner does it.
 */
class VarlockIntegrationTest : BasePlatformTestCase() {
    private lateinit var infisical: SecretConnection
    private lateinit var vault: SecretConnection

    override fun setUp() {
        super.setUp()
        infisical = SecretConnection(
            id = "it-varlock-infisical-${System.nanoTime()}",
            providerId = InfisicalProvider.ID,
            name = "Infisical",
            baseUrl = IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_URL"),
            authMethodId = InfisicalAuthMethods.MACHINE_IDENTITY,
            options = mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_CLIENT_ID")),
        )
        CredentialVault.set(infisical.id, CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET), IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_CLIENT_SECRET"))
        vault = SecretConnection(
            id = "it-varlock-vault-${System.nanoTime()}",
            providerId = VaultProvider.ID,
            name = "Vault",
            baseUrl = IntegrationEnv.require("ENVBRIDGE_IT_VAULT_ADDR"),
            authMethodId = VaultAuthMethods.APPROLE,
            options = mapOf(VaultAuthMethods.OPTION_ROLE_ID to "envbridge-role"),
        )
        CredentialVault.set(vault.id, CredentialVault.secretOptionSlot(VaultAuthMethods.OPTION_SECRET_ID), "envbridge-secret")
        SecretsAppSettings.getInstance().setConnections(listOf(infisical, vault))
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = infisical.id
            projectId = IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_PROJECT_ID")
            environment = null
            path = null
            useProjectFile = false
        }
        SecretsService.getInstance().invalidate()
        SecretsAppSettings.getInstance().state.varlockCompatibility = true
    }

    override fun tearDown() {
        try {
            SecretsAppSettings.getInstance().state.varlockCompatibility = false
            CredentialVault.removeAll(infisical.id, listOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET))
            CredentialVault.removeAll(vault.id, listOf(VaultAuthMethods.OPTION_SECRET_ID))
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
        } finally {
            super.tearDown()
        }
    }

    fun `test a schema with infisical and vaultSecret functions resolves against real servers`() {
        val projectId = IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_PROJECT_ID")
        val dir = Files.createTempDirectory("varlock-it")
        val d = "$"
        Files.writeString(
            dir.resolve(".env.schema"),
            """
            # @initInfisical(projectId=$projectId, environment=dev, clientId=${d}INFISICAL_CLIENT_ID, clientSecret=${d}INFISICAL_CLIENT_SECRET)
            # @initInfisical(id=prod, projectId=$projectId, environment=prod, clientId=${d}INFISICAL_CLIENT_ID, clientSecret=${d}INFISICAL_CLIENT_SECRET)
            # @initHcpVault(url="${vault.baseUrl}")
            # @defaultRequired=infer @defaultSensitive=false
            # ---
            INFISICAL_CLIENT_ID=
            INFISICAL_CLIENT_SECRET=
            DB_PASSWORD=infisical()
            PROD_DB=infisical(prod, "DB_PASSWORD")
            VAULT_DB=vaultSecret("secret/envbridge/app/db#password")
            URL="postgres://app:${d}{DB_PASSWORD}@db/app"
            """.trimIndent() + "\n",
        )
        Files.writeString(dir.resolve("package.json"), "{}")
        val type = ConfigurationTypeUtil.findConfigurationType("js.build_tools.npm")!!
        val template = type.configurationFactories.first().createTemplateConfiguration(project)
        val configuration = RunManager.getInstance(project).createConfiguration(template, template.factory!!).configuration as NpmRunConfiguration
        configuration.runSettings = configuration.runSettings.toBuilder()
            .setEnvData(EnvironmentVariablesData.create(emptyMap(), true, null))
            .setPackageJsonPath(dir.resolve("package.json").toString())
            .build()

        // What SecretsProgramRunner does in its background task
        val resolver = LaunchSecretResolver.getInstance(project)
        val prepared = EnvFileReferences.prepare(configuration, emptyMap(), resolver)
        VarlockLaunch.checkProblems(prepared.varlock)
        val resolved = resolver.resolve(resolver.plan(prepared.envs))
        val result = VarlockLaunch.resolve(project, prepared, resolved, resolver, null)
        val outcome = LaunchChecks.apply(prepared, result, emptyMap())

        assertThat(outcome.missingRequired).isEmpty()
        assertThat(outcome.environment)
            .containsEntry("DB_PASSWORD", "dev-db-pass")
            .containsEntry("PROD_DB", "prod-db-pass")
            .containsEntry("VAULT_DB", "db-pass-VAULT")
            .containsEntry("URL", "postgres://app:dev-db-pass@db/app")
        assertThat(outcome.maskedValues).contains("dev-db-pass", "prod-db-pass", "db-pass-VAULT")
        assertThat(result.environments).contains("dev", "prod", "root")
    }
}
