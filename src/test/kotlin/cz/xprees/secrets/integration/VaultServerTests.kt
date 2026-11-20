package cz.xprees.secrets.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.vault.VaultApi
import cz.xprees.secrets.vault.VaultAuth
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultHttp
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/** Real HashiCorp Vault (dev server). */
class VaultIntegrationTest : VaultServerTests("VAULT")

/** Real OpenBao (dev server); also covers namespaces, which open-source Vault doesn't have. */
class OpenBaoIntegrationTest : VaultServerTests("OPENBAO")

/**
 * The Vault provider against a real server seeded by `scripts/integration/setup.sh`: KV v2 mounts, AppRole, userpass and token
 * sign-in, permissions, token renewal and revocation, namespaces, and launches resolving `vault://` references.
 */
abstract class VaultServerTests(private val server: String) : BasePlatformTestCase() {
    private val address get() = IntegrationEnv.require("ENVBRIDGE_IT_${server}_ADDR")
    private val connections = mutableListOf<SecretConnection>()

    override fun setUp() {
        super.setUp()
        VaultAuth.cliTokenFile = null
    }

    override fun tearDown() {
        try {
            val keys = listOf(VaultAuthMethods.OPTION_TOKEN, VaultAuthMethods.OPTION_SECRET_ID, VaultAuthMethods.OPTION_PASSWORD)
            connections.forEach { CredentialVault.removeAll(it.id, keys) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
        } finally {
            super.tearDown()
        }
    }

    private fun connection(method: String, options: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap()): SecretConnection {
        val connection = SecretConnection(
            id = "it-${server.lowercase()}-${System.nanoTime()}",
            providerId = VaultProvider.ID,
            name = "$server ${connections.size}",
            baseUrl = address,
            authMethodId = method,
            options = options,
        )
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    // Test-only credentials set by scripts/integration/setup.sh
    private fun appRole(secretId: String = "envbridge-secret", options: Map<String, String> = emptyMap()) = connection(
        VaultAuthMethods.APPROLE,
        mapOf(VaultAuthMethods.OPTION_ROLE_ID to "envbridge-role") + options,
        mapOf(VaultAuthMethods.OPTION_SECRET_ID to secretId),
    )

    private fun client(connection: SecretConnection) = SecretsService.getInstance().client(connection)

    private val interaction = object : SignInInteraction {
        override fun openBrowser(url: String) = Unit
        override fun isCancelled(): Boolean = false
    }

    private fun session(connection: SecretConnection): TokenSet =
        TokenSet.fromJson(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)!!)!!

    fun `test AppRole lists KV v2 mounts, paths and fields`() {
        val client = client(appRole())
        assertThat(client.signIn(interaction)).contains("approle")

        val mounts = client.listProjects().map { it.id }
        // KV v1 (legacy) and other engines are not offered
        assertThat(mounts).contains("secret", "team-kv").doesNotContain("legacy", "cubbyhole", "sys")

        assertThat(client.listFolders(SecretScope("secret", "root", "/envbridge")).map { it.path }).containsExactly("/envbridge/app")
        assertThat(client.listFolders(SecretScope("secret", "root", "/envbridge/app")).map { it.path }).containsExactly("/envbridge/app/api", "/envbridge/app/db")

        val fields = client.listSecrets(SecretScope("secret", "root", "/envbridge/app/api")).associate { it.key to it.value.reveal() }
        assertThat(fields).containsEntry("key", "api-key-$server").containsEntry("limits", """{"rps":10}""")
        assertThat(client.listSecrets(SecretScope("team-kv", "root", "/svc")).single().value.reveal()).isEqualTo("team-kv-token")
    }

    fun `test recursive listing reads every secret below a path`() {
        val entries = client(appRole()).listSecrets(SecretScope("secret", "root", "/envbridge", recursive = true))
        assertThat(entries.map { "${it.path}#${it.key}" })
            .contains("/envbridge/app#name", "/envbridge/app/db#password", "/envbridge/app/db#user", "/envbridge/app/api#key")
    }

    fun `test userpass and token sign-in`() {
        val userpass = connection(VaultAuthMethods.USERPASS, mapOf(VaultAuthMethods.OPTION_USERNAME to "alice"), mapOf(VaultAuthMethods.OPTION_PASSWORD to "alice-pass"))
        assertThat(client(userpass).signIn(interaction)).contains("alice")
        assertThat(client(userpass).listSecrets(SecretScope("secret", "root", "/envbridge/app/db")).map { it.key }).contains("password")

        val token = connection(VaultAuthMethods.TOKEN, secrets = mapOf(VaultAuthMethods.OPTION_TOKEN to IntegrationEnv.require("ENVBRIDGE_IT_${server}_TOKEN")))
        assertThat(client(token).signIn(interaction)).contains("envbridge-it")
        assertThat(client(token).listProjects().map { it.id }).contains("secret")
    }

    fun `test wrong credentials and missing permissions are told apart`() {
        assertThatThrownBy { client(appRole(secretId = "wrong")).signIn(interaction) }
            .isInstanceOf(SecretProviderException::class.java)
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.AUTH_REQUIRED)

        // A valid token without access to the path: permission denied, not "sign in again"
        val connection = appRole()
        assertThatThrownBy { client(connection).listSecrets(SecretScope("secret", "root", "/forbidden/x")) }
            .isInstanceOf(SecretProviderException::class.java)
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)
        assertThat(client(connection).listSecrets(SecretScope("secret", "root", "/envbridge/app/db"))).isNotEmpty()
    }

    fun `test a revoked token is replaced by a new AppRole login`() {
        val connection = appRole()
        val client = client(connection)
        client.listProjects()
        val first = session(connection).accessToken
        // Revoke the plugin's token on the server, as an operator or an expiry would
        val api = VaultApi(VaultHttp(address))
        api.revokeSelf(first, VaultApi.ROOT_NAMESPACE)
        assertThat(client.listSecrets(SecretScope("secret", "root", "/envbridge/app/db")).map { it.key }).contains("password")
        assertThat(session(connection).accessToken).isNotEqualTo(first)
    }

    fun `test renewable tokens are renewed by the server`() {
        val connection = appRole()
        client(connection).signIn(interaction)
        val before = session(connection)
        assertThat(before.extra[VaultApi.EXTRA_RENEWABLE]).isEqualTo("true")
        val renewed = VaultApi(VaultHttp(address)).renewSelf(before, VaultApi.ROOT_NAMESPACE)
        assertThat(renewed.accessToken).isEqualTo(before.accessToken)
        assertThat(renewed.expiresAt).isNotNull()
    }

    fun `test sign-out revokes tokens the plugin obtained`() {
        val connection = appRole()
        client(connection).signIn(interaction)
        val token = session(connection).accessToken
        client(connection).signOut()
        assertThatThrownBy { VaultApi(VaultHttp(address)).lookupSelf(token, VaultApi.ROOT_NAMESPACE) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.AUTH_REQUIRED)
    }

    fun `test namespaces`() {
        if (IntegrationEnv.get("ENVBRIDGE_IT_${server}_NAMESPACES") != "true") return // open-source Vault has none
        val client = client(appRole(options = mapOf(VaultAuthMethods.OPTION_NAMESPACES to "team-a")))
        assertThat(client.listProjects().first { it.id == "secret" }.environments.map { it.slug }).containsExactly("root", "team-a")
        assertThat(client.listSecrets(SecretScope("secret", "team-a", "/envbridge/app/db")).single().value.reveal()).isEqualTo("team-a-db-pass")
        assertThat(client.listSecrets(SecretScope("secret", "root", "/envbridge/app/db")).first { it.key == "password" }.value.reveal())
            .isEqualTo("db-pass-$server")
    }

    fun `test launches resolve vault references`() {
        val connection = appRole()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "secret"
            environment = null
            path = null
            useProjectFile = false
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val values = linkedMapOf(
            "DB_PASSWORD" to "vault://secret/envbridge/app/db#password",
            "API" to "vault://secret/envbridge/app/api#*",
            "PLAIN" to "plain",
        )
        if (IntegrationEnv.get("ENVBRIDGE_IT_${server}_NAMESPACES") == "true") values["TEAM_DB"] = "vault://secret/envbridge/app/db#password?namespace=team-a"
        val result = resolver.resolve(resolver.plan(values))
        assertThat(result.environment)
            .containsEntry("DB_PASSWORD", "db-pass-$server")
            .containsEntry("key", "api-key-$server")
            .containsEntry("PLAIN", "plain")
            .doesNotContainKey("API")
        if ("TEAM_DB" in values) assertThat(result.environment).containsEntry("TEAM_DB", "team-a-db-pass")

        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "vault://secret/envbridge/app/db#missing"))) }
            .isInstanceOf(SecretProviderException::class.java)
    }
}
