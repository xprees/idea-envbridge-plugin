package cz.xprees.secrets.integration

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.run.EnvPatcher
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat

/**
 * The whole launch path against real servers: a run configuration whose environment mixes `infisical://` and `vault://`
 * references (two providers in one launch) is resolved and copied the way the plugin's program runner does it, a real
 * process started with the copy's environment sees the values, and the stored configuration keeps only the references.
 */
class LaunchIntegrationTest : BasePlatformTestCase() {
    private lateinit var infisical: SecretConnection
    private lateinit var vault: SecretConnection

    override fun setUp() {
        super.setUp()
        infisical = SecretConnection(
            id = "it-launch-infisical-${System.nanoTime()}",
            providerId = InfisicalProvider.ID,
            name = "Infisical",
            baseUrl = IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_URL"),
            authMethodId = InfisicalAuthMethods.MACHINE_IDENTITY,
            options = mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_CLIENT_ID")),
        )
        CredentialVault.set(infisical.id, CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET), IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_CLIENT_SECRET"))
        vault = SecretConnection(
            id = "it-launch-vault-${System.nanoTime()}",
            providerId = VaultProvider.ID,
            name = "Vault",
            baseUrl = IntegrationEnv.require("ENVBRIDGE_IT_VAULT_ADDR"),
            authMethodId = VaultAuthMethods.APPROLE,
            options = mapOf(VaultAuthMethods.OPTION_ROLE_ID to "envbridge-role"),
        )
        CredentialVault.set(vault.id, CredentialVault.secretOptionSlot(VaultAuthMethods.OPTION_SECRET_ID), "envbridge-secret")
        SecretsAppSettings.getInstance().setConnections(listOf(infisical, vault))
        // The project is bound to Infisical; vault:// references use the (only) Vault connection
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = infisical.id
            projectId = IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_PROJECT_ID")
            environment = null
            path = null
            useProjectFile = false
        }
        SecretsService.getInstance().invalidate()
    }

    override fun tearDown() {
        try {
            CredentialVault.removeAll(infisical.id, listOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET))
            CredentialVault.removeAll(vault.id, listOf(VaultAuthMethods.OPTION_SECRET_ID))
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
        } finally {
            super.tearDown()
        }
    }

    fun `test a process gets values from both providers and the configuration keeps the references`() {
        val references = linkedMapOf(
            "DB_PASSWORD" to "infisical://DB_PASSWORD",
            "PROD_DB" to "infisical://prod:DB_PASSWORD",
            "VAULT_DB" to "vault://secret/envbridge/app/db#password",
            "PLAIN" to "plain-value",
        )
        val configuration = ApplicationConfiguration("Launch", project, ApplicationConfigurationType.getInstance())
        configuration.envs = references

        // What SecretsProgramRunner does: plan, resolve, copy
        val (patcher, configured) = EnvPatcher.find(configuration)!!
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(resolver.plan(configured))
        val copy = patcher.copyWith(configuration, result.environment) as ApplicationConfiguration

        val output = ExecUtil.execAndGetOutput(
            GeneralCommandLine("sh", "-c", "printf '%s|%s|%s|%s' \"\$DB_PASSWORD\" \"\$PROD_DB\" \"\$VAULT_DB\" \"\$PLAIN\"")
                .withEnvironment(copy.envs),
        )
        assertThat(output.exitCode).isEqualTo(0)
        assertThat(output.stdout).isEqualTo("dev-db-pass|prod-db-pass|db-pass-VAULT|plain-value")

        // Nothing secret reached the stored configuration
        assertThat(configuration.envs).isEqualTo(references)
        // The prod guard sees where the values came from: Infisical dev and prod, Vault's root namespace
        assertThat(result.environments).contains("dev", "prod", "root")
    }
}
