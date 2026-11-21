package cz.xprees.secrets.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The Infisical provider against a real self-hosted instance seeded by `scripts/integration/setup.sh`: machine identity
 * sign-in, projects, environments, folders, secrets, launches, and the write paths (create, update, move, delete, new
 * environment) that otherwise only run against the mock.
 */
class InfisicalIntegrationTest : BasePlatformTestCase() {
    private lateinit var connection: SecretConnection
    private val projectId get() = IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_PROJECT_ID")

    override fun setUp() {
        super.setUp()
        connection = SecretConnection(
            id = "it-infisical-${System.nanoTime()}",
            providerId = InfisicalProvider.ID,
            name = "Infisical IT",
            baseUrl = IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_URL"),
            authMethodId = InfisicalAuthMethods.MACHINE_IDENTITY,
            options = mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_CLIENT_ID")),
        )
        CredentialVault.set(
            connection.id,
            CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET),
            IntegrationEnv.require("ENVBRIDGE_IT_INFISICAL_CLIENT_SECRET"),
        )
        SecretsAppSettings.getInstance().setConnections(listOf(connection))
        SecretsService.getInstance().invalidate()
    }

    override fun tearDown() {
        try {
            CredentialVault.removeAll(connection.id, listOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET))
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
        } finally {
            super.tearDown()
        }
    }

    private val client get() = SecretsService.getInstance().client(connection)

    fun `test machine identity lists projects, environments, folders and secrets`() {
        val project = client.listProjects().single { it.id == projectId }
        assertThat(project.environments.map { it.slug }).contains("dev", "staging", "prod")

        assertThat(client.listFolders(SecretScope(projectId, "dev")).map { it.path }).contains("/backend")
        val dev = client.listSecrets(SecretScope(projectId, "dev")).associate { it.key to it.value.reveal() }
        assertThat(dev).containsEntry("DB_PASSWORD", "dev-db-pass").containsEntry("API_KEY", "dev-api-key")
        assertThat(client.listSecrets(SecretScope(projectId, "prod")).associate { it.key to it.value.reveal() })
            .containsEntry("DB_PASSWORD", "prod-db-pass")

        val everywhere = client.listSecrets(SecretScope(projectId, "dev", "/", recursive = true))
        assertThat(everywhere.map { "${it.key}@${it.path}" }).contains("DB_PASSWORD@/", "BACKEND_ONLY@/backend")
    }

    fun `test launches resolve infisical references`() {
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = this@InfisicalIntegrationTest.projectId
            environment = null
            path = null
            useProjectFile = false
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(
                linkedMapOf(
                    "ALL" to "infisical://*",
                    "PROD_DB" to "infisical://prod:DB_PASSWORD",
                    "BACKEND" to "infisical://backend/BACKEND_ONLY",
                ),
            ),
        )
        assertThat(result.environment)
            .containsEntry("DB_PASSWORD", "dev-db-pass")
            .containsEntry("API_KEY", "dev-api-key")
            .containsEntry("PROD_DB", "prod-db-pass")
            .containsEntry("BACKEND", "backend-value")
            .doesNotContainKey("ALL")
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "infisical://NOT_THERE"))) }
            .isInstanceOf(SecretProviderException::class.java)
    }

    fun `test secrets are created, changed, moved and deleted on the instance`() {
        val service = SecretsService.getInstance()
        val key = "IT_${System.currentTimeMillis()}"
        val root = SecretScope(projectId, "dev", "/")
        val backend = SecretScope(projectId, "dev", "/backend")

        assertThat(service.createSecret(connection, root, key, SecretValue("first"), "created by the integration test")).isEqualTo(WriteOutcome.APPLIED)
        assertThat(service.secrets(connection, root, fresh = true).single { it.key == key }.let { it.value.reveal() to it.comment })
            .isEqualTo("first" to "created by the integration test")

        assertThat(service.updateSecret(connection, root, key, SecretValue("second"))).isEqualTo(WriteOutcome.APPLIED)
        val updated = service.secrets(connection, root, fresh = true).single { it.key == key }
        assertThat(updated.value.reveal()).isEqualTo("second")
        // no comment given: the comment stays
        assertThat(updated.comment).isEqualTo("created by the integration test")

        val moved = service.moveSecrets(connection, root, listOf(updated), backend, overwrite = false)
        assertThat(moved.moved).containsExactly(key)
        assertThat(service.secrets(connection, root, fresh = true).map { it.key }).doesNotContain(key)
        assertThat(service.secrets(connection, backend, fresh = true).single { it.key == key }.value.reveal()).isEqualTo("second")

        val deleted = service.deleteSecrets(connection, backend, listOf(key))
        assertThat(deleted.getValue(key).getOrThrow()).isEqualTo(WriteOutcome.APPLIED)
        assertThat(service.secrets(connection, backend, fresh = true).map { it.key }).doesNotContain(key)
    }

    fun `test a secret is renamed on the instance, keeping its value and comment, and a taken name is refused`() {
        val service = SecretsService.getInstance()
        val key = "IT_RENAME_${System.currentTimeMillis()}"
        val root = SecretScope(projectId, "dev", "/")
        service.createSecret(connection, root, key, SecretValue("kept"), "renamed by the integration test")
        try {
            // the value as listed: not sent, only the new name
            assertThat(service.updateSecret(connection, root, key, SecretValue("kept"), null, "${key}_NEW")).isEqualTo(WriteOutcome.APPLIED)
            val listed = service.secrets(connection, root, fresh = true)
            assertThat(listed.map { it.key }).doesNotContain(key)
            assertThat(listed.single { it.key == "${key}_NEW" }.let { it.value.reveal() to it.comment }).isEqualTo("kept" to "renamed by the integration test")
            assertThatThrownBy { service.updateSecret(connection, root, "${key}_NEW", SecretValue("x"), null, "DB_PASSWORD") }
                .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("already has a secret DB_PASSWORD")
        } finally {
            runCatching { service.deleteSecrets(connection, root, listOf(key, "${key}_NEW")) }
        }
    }

    fun `test a duplicate secret is refused`() {
        val service = SecretsService.getInstance()
        assertThatThrownBy { service.createSecret(connection, SecretScope(projectId, "dev", "/"), "DB_PASSWORD", SecretValue("x")) }
            .isInstanceOf(SecretProviderException::class.java)
    }

    fun `test environments are created`() {
        val slug = "it-${System.currentTimeMillis() % 1_000_000}"
        val created = SecretsService.getInstance().createEnvironment(connection, projectId, "Integration $slug", slug)
        assertThat(created.slug).isEqualTo(slug)
        assertThat(client.listProjects().single { it.id == projectId }.environments.map { it.slug }).contains(slug)
    }

    fun `test a wrong client secret is refused`() {
        CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET), "not-the-secret")
        SecretsService.getInstance().invalidate()
        assertThatThrownBy { client.listProjects() }.isInstanceOf(SecretProviderException::class.java)
    }
}
