package cz.xprees.secrets.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.doppler.DopplerAuthMethods
import cz.xprees.secrets.doppler.DopplerProvider
import cz.xprees.secrets.onepassword.OnePasswordAuthMethods
import cz.xprees.secrets.onepassword.OnePasswordProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.TimeUnit

/**
 * Tests against a real account of a hosted secret manager, which can't run in Docker. They run only when the account's
 * credentials are given (CI: repository secrets; locally: environment variables) and otherwise skip, saying what is missing.
 * Use a dedicated test project / vault: the tests create and delete secrets there.
 */
abstract class OptionalAccountTests : BasePlatformTestCase() {
    protected val connections = mutableListOf<SecretConnection>()

    /** The variables this account needs; the tests skip when any is missing. */
    protected abstract val required: List<String>

    protected val configured: Boolean get() = required.all { IntegrationEnv.get(it) != null }

    /** Skips (returns false) with a note when the account isn't configured. */
    protected fun available(): Boolean {
        if (configured) return true
        println("SKIPPED ${javaClass.simpleName}.$name: set ${required.filter { IntegrationEnv.get(it) == null }.joinToString()} to run it")
        return false
    }

    protected fun register(connection: SecretConnection, secrets: Map<String, String>): SecretConnection {
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    protected abstract val secretKeys: List<String>

    override fun tearDown() {
        try {
            connections.forEach { CredentialVault.removeAll(it.id, secretKeys) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
        } finally {
            super.tearDown()
        }
    }

    protected fun bind(connection: SecretConnection, projectId: String) {
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            this.projectId = projectId
            environment = null
            path = null
            useProjectFile = false
        }
    }

    protected fun uniqueName(prefix: String) = "${prefix}_${System.currentTimeMillis()}_${(1000..9999).random()}"
}

/**
 * Doppler (doppler.com). Needs a token that can read and write a test project:
 * - `ENVBRIDGE_IT_DOPPLER_TOKEN`: a personal (`dp.pt.`) or service account (`dp.sa.`) token
 * - `ENVBRIDGE_IT_DOPPLER_PROJECT` (default `envbridge-it`) and `ENVBRIDGE_IT_DOPPLER_CONFIG` (default `dev`)
 */
class DopplerIntegrationTest : OptionalAccountTests() {
    override val required = listOf("ENVBRIDGE_IT_DOPPLER_TOKEN")
    override val secretKeys = listOf(DopplerAuthMethods.OPTION_TOKEN)

    private val dopplerProject get() = IntegrationEnv.get("ENVBRIDGE_IT_DOPPLER_PROJECT") ?: "envbridge-it"
    private val config get() = IntegrationEnv.get("ENVBRIDGE_IT_DOPPLER_CONFIG") ?: "dev"

    private fun connection() = register(
        SecretConnection(
            id = "it-doppler-${System.nanoTime()}",
            providerId = DopplerProvider.ID,
            name = "Doppler IT",
            baseUrl = DopplerProvider.API_URL,
            authMethodId = DopplerAuthMethods.TOKEN,
        ),
        mapOf(DopplerAuthMethods.OPTION_TOKEN to IntegrationEnv.require("ENVBRIDGE_IT_DOPPLER_TOKEN")),
    )

    fun `test lists the project, its configs and secrets`() {
        if (!available()) return
        val client = SecretsService.getInstance().client(connection())
        val project = client.listProjects().firstOrNull { it.id == dopplerProject }
        assertThat(project).describedAs("Doppler project $dopplerProject (create it, or set ENVBRIDGE_IT_DOPPLER_PROJECT)").isNotNull()
        assertThat(project!!.environments.map { it.slug }).contains(config)
        // Doppler's own metadata secrets are left out
        assertThat(client.listSecrets(SecretScope(dopplerProject, config)).map { it.key }).doesNotContain("DOPPLER_PROJECT", "DOPPLER_CONFIG")
    }

    fun `test a secret is created, read, changed, resolved in a launch and deleted`() {
        if (!available()) return
        val connection = connection()
        val service = SecretsService.getInstance()
        val scope = SecretScope(dopplerProject, config)
        val key = uniqueName("ENVBRIDGE_IT")
        try {
            service.createSecret(connection, scope, key, SecretValue("first"), "created by the EnvBridge integration test")
            assertThat(service.secrets(connection, scope, fresh = true).single { it.key == key }.value.reveal()).isEqualTo("first")
            service.updateSecret(connection, scope, key, SecretValue("second"))
            assertThat(service.secrets(connection, scope, fresh = true).single { it.key == key }.value.reveal()).isEqualTo("second")
            assertThatThrownBy { service.createSecret(connection, scope, key, SecretValue("x")) }.isInstanceOf(SecretProviderException::class.java)

            bind(connection, dopplerProject)
            val resolver = LaunchSecretResolver.getInstance(project)
            val result = resolver.resolve(resolver.plan(mapOf("VALUE" to "doppler://$config:$key")))
            assertThat(result.environment).containsEntry("VALUE", "second")
        } finally {
            runCatching { service.deleteSecrets(connection, scope, listOf(key)) }
        }
        assertThat(service.secrets(connection, scope, fresh = true).map { it.key }).doesNotContain(key)
    }
}

/**
 * 1Password with a service account and the `op` CLI. Needs:
 * - `ENVBRIDGE_IT_OP_SERVICE_ACCOUNT_TOKEN`: a service account token with read and write access to the test vault
 * - `ENVBRIDGE_IT_OP_VAULT` (default `EnvBridge IT`): the test vault's name
 * - the `op` CLI on PATH (or `ENVBRIDGE_IT_OP_PATH`)
 */
class OnePasswordIntegrationTest : OptionalAccountTests() {
    override val required = listOf("ENVBRIDGE_IT_OP_SERVICE_ACCOUNT_TOKEN")
    override val secretKeys = listOf(OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN)

    private val vaultName get() = IntegrationEnv.get("ENVBRIDGE_IT_OP_VAULT") ?: "EnvBridge IT"

    private fun connection() = register(
        SecretConnection(
            id = "it-op-${System.nanoTime()}",
            providerId = OnePasswordProvider.ID,
            name = "1Password IT",
            baseUrl = "http://localhost:8080",
            authMethodId = OnePasswordAuthMethods.SERVICE_ACCOUNT,
            options = listOfNotNull(IntegrationEnv.get("ENVBRIDGE_IT_OP_PATH")?.let { OnePasswordAuthMethods.OPTION_OP_PATH to it }).toMap(),
        ),
        mapOf(OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN to IntegrationEnv.require("ENVBRIDGE_IT_OP_SERVICE_ACCOUNT_TOKEN")),
    )

    fun `test lists the vault and its items`() {
        if (!available()) return
        val client = SecretsService.getInstance().client(connection())
        val vault = client.listProjects().firstOrNull { it.name == vaultName }
        assertThat(vault).describedAs("1Password vault $vaultName (create it, or set ENVBRIDGE_IT_OP_VAULT)").isNotNull()
        assertThat(vault!!.environments.map { it.slug }).containsExactly(OnePasswordProvider.ENVIRONMENT)
        client.listFolders(SecretScope(vault.id, OnePasswordProvider.ENVIRONMENT))
    }

    fun `test an item and its fields are created, changed, resolved in a launch and removed`() {
        if (!available()) return
        val connection = connection()
        val service = SecretsService.getInstance()
        val client = service.client(connection)
        val vault = client.listProjects().first { it.name == vaultName }
        val item = uniqueName("EnvBridge IT")
        val root = SecretScope(vault.id, OnePasswordProvider.ENVIRONMENT)
        try {
            // A new secret at the vault's root is a new Password item with the value as its password
            service.createSecret(connection, root, item, SecretValue("first"), "created by the EnvBridge integration test")
            val itemScope = SecretScope(vault.id, OnePasswordProvider.ENVIRONMENT, "/$item")
            assertThat(service.secrets(connection, itemScope, fresh = true).single { it.key == "password" }.value.reveal()).isEqualTo("first")

            service.createSecret(connection, itemScope, "api key", SecretValue("k1"))
            service.updateSecret(connection, itemScope, "api key", SecretValue("k2"))
            assertThat(service.secrets(connection, itemScope, fresh = true).single { it.key == "api key" }.value.reveal()).isEqualTo("k2")

            bind(connection, vault.id)
            val resolver = LaunchSecretResolver.getInstance(project)
            val result = resolver.resolve(resolver.plan(mapOf("PASSWORD" to "op://$vaultName/$item/password", "KEY" to "op://$vaultName/$item/api key")))
            assertThat(result.environment).containsEntry("PASSWORD", "first").containsEntry("KEY", "k2")

            service.deleteSecrets(connection, itemScope, listOf("api key")).getValue("api key").getOrThrow()
            assertThat(service.secrets(connection, itemScope, fresh = true).map { it.key }).doesNotContain("api key")
        } finally {
            // EnvBridge never deletes items: remove the test item with the CLI itself
            deleteItem(item)
        }
    }

    private fun deleteItem(title: String) {
        val op = IntegrationEnv.get("ENVBRIDGE_IT_OP_PATH") ?: "op"
        runCatching {
            val process = ProcessBuilder(op, "item", "delete", title, "--vault", vaultName)
                .apply { environment()["OP_SERVICE_ACCOUNT_TOKEN"] = IntegrationEnv.require("ENVBRIDGE_IT_OP_SERVICE_ACCOUNT_TOKEN") }
                .redirectErrorStream(true)
                .start()
            process.inputStream.readAllBytes()
            process.waitFor(60, TimeUnit.SECONDS)
        }
    }
}
