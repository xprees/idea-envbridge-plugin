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
 * The Vault provider against a real server seeded by `scripts/integration/setup.sh`: KV v2 and KV v1 mounts, AppRole, userpass and token
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

    fun `test AppRole lists KV mounts, paths and fields`() {
        val client = client(appRole())
        assertThat(client.signIn(interaction)).contains("approle")

        val mounts = client.listProjects().map { it.id }
        // KV v2 and KV v1 (legacy) mounts; other engines are not offered
        assertThat(mounts).contains("secret", "team-kv", "legacy").doesNotContain("cubbyhole", "sys")

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

    fun `test a field is renamed in one check-and-set write`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val app = SecretScope("secret", "root", "/envbridge-rw/rename-${System.nanoTime()}")
        service.createSecret(connection, app, "token", cz.xprees.secrets.core.model.SecretValue("t1"))
        service.createSecret(connection, app, "url", cz.xprees.secrets.core.model.SecretValue("https://example.test"))
        service.updateSecret(connection, app, "token", cz.xprees.secrets.core.model.SecretValue("t1"), null, "api_token")
        assertThat(client(connection).listSecrets(app).associate { it.key to it.value.reveal() })
            .containsOnlyKeys("api_token", "url").containsEntry("api_token", "t1")
        assertThat(VaultApi(VaultHttp(address)).readVersioned(session(connection).accessToken, VaultApi.ROOT_NAMESPACE, "secret", app.path).version).isEqualTo(3)
        assertThatThrownBy { service.updateSecret(connection, app, "api_token", cz.xprees.secrets.core.model.SecretValue("x"), null, "url") }
            .hasMessageContaining("already has a field url")
    }

    fun `test fields are written, moved and deleted with check-and-set`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val base = "/envbridge-rw/run-${System.nanoTime()}"
        val app = SecretScope("secret", "root", "$base/app")

        service.createSecret(connection, app, "token", cz.xprees.secrets.core.model.SecretValue("t1"))
        service.createSecret(connection, app, "url", cz.xprees.secrets.core.model.SecretValue("https://example.test"))
        service.updateSecret(connection, app, "token", cz.xprees.secrets.core.model.SecretValue("t2"))
        assertThat(client(connection).listSecrets(app).associate { it.key to it.value.reveal() })
            .containsEntry("token", "t2").containsEntry("url", "https://example.test")
        assertThatThrownBy { service.createSecret(connection, app, "token", cz.xprees.secrets.core.model.SecretValue("x")) }
            .hasMessageContaining("already has a field token")

        // the server enforces check-and-set: a stale version is refused
        val api = VaultApi(VaultHttp(address))
        val token = session(connection).accessToken
        val current = api.readVersioned(token, VaultApi.ROOT_NAMESPACE, "secret", "$base/app")
        assertThat(current.version).isEqualTo(3)
        assertThatThrownBy { api.write(token, VaultApi.ROOT_NAMESPACE, "secret", "$base/app", current.fields, current.version - 1) }
            .hasMessageContaining("changed meanwhile")

        val moved = service.moveSecrets(connection, app, client(connection).listSecrets(app).filter { it.key == "url" }, SecretScope("secret", "root", "$base/other"), overwrite = false)
        assertThat(moved.moved).containsExactly("url")
        assertThat(client(connection).listSecrets(app).map { it.key }).containsExactly("token")
        assertThat(client(connection).listSecrets(SecretScope("secret", "root", "$base/other")).single().value.reveal()).isEqualTo("https://example.test")

        // deleting the last field deletes the latest version; the path can be written again
        service.deleteSecrets(connection, app, listOf("token")).getValue("token").getOrThrow()
        assertThat(client(connection).listSecrets(app)).isEmpty()
        service.createSecret(connection, app, "token", cz.xprees.secrets.core.model.SecretValue("again"))
        assertThat(client(connection).listSecrets(app).single().value.reveal()).isEqualTo("again")

        // no write permission outside envbridge-rw/
        assertThatThrownBy { service.createSecret(connection, SecretScope("secret", "root", "/envbridge/app/db"), "new", cz.xprees.secrets.core.model.SecretValue("x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)

        if (IntegrationEnv.get("ENVBRIDGE_IT_${server}_NAMESPACES") == "true") {
            val toTeam = service.moveSecrets(connection, app, client(connection).listSecrets(app), SecretScope("secret", "team-a", "$base/app"), overwrite = false)
            assertThat(toTeam.moved).containsExactly("token")
            assertThat(client(connection).listSecrets(SecretScope("secret", "team-a", "$base/app")).single().value.reveal()).isEqualTo("again")
        }
    }

    // ---- KV version 1 ----------------------------------------------------------------------------------------------

    private fun fields(connection: SecretConnection, scope: SecretScope) = client(connection).listSecrets(scope).associate { it.key to it.value.reveal() }

    private fun value(text: String) = cz.xprees.secrets.core.model.SecretValue(text)

    fun `test KV v1 secrets are listed and read`() {
        // a configured mount: the version comes from the server's preflight, as for the Vault CLI
        val connection = appRole(options = mapOf(VaultAuthMethods.OPTION_MOUNTS to "legacy"))
        val client = client(connection)
        assertThat(client.listProjects().map { it.id }).containsExactly("legacy")
        assertThat(client.listFolders(SecretScope("legacy", "root", "/envbridge")).map { it.path }).containsExactly("/envbridge/app")
        assertThat(client.listFolders(SecretScope("legacy", "root", "/envbridge/app")).map { it.path }).containsExactly("/envbridge/app/api", "/envbridge/app/db")
        assertThat(fields(connection, SecretScope("legacy", "root", "/envbridge/app/api")))
            .containsOnlyKeys("key", "limits").containsEntry("key", "v1-api-key-$server").containsEntry("limits", """{"rps":5}""")
        assertThat(client.listSecrets(SecretScope("legacy", "root", "/envbridge/app/missing"))).isEmpty()
        assertThat(client.listSecrets(SecretScope("legacy", "root", "/envbridge", recursive = true)).map { "${it.path}#${it.key}" })
            .containsExactlyInAnyOrder("/envbridge/app/api#key", "/envbridge/app/api#limits", "/envbridge/app/db#password", "/envbridge/app/db#user")
        assertThatThrownBy { client.listSecrets(SecretScope("legacy", "root", "/forbidden/x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)

        // listed mounts carry their version too
        val listed = appRole()
        client(listed).listProjects()
        assertThat(fields(listed, SecretScope("legacy", "root", "/envbridge/app/db"))).containsEntry("password", "v1-db-pass-$server")
    }

    fun `test KV v1 version probe agrees with the server's preflight`() {
        val api = VaultApi(VaultHttp(address))
        val connection = appRole()
        client(connection).signIn(interaction)
        val token = session(connection).accessToken
        assertThat(api.kvVersion(token, VaultApi.ROOT_NAMESPACE, "legacy")).isEqualTo(1)
        assertThat(api.kvVersion(token, VaultApi.ROOT_NAMESPACE, "secret")).isEqualTo(2)
        // the fallback, when the preflight can't be used: the path read the KV v1 way
        assertThat(api.probeKvVersion(token, VaultApi.ROOT_NAMESPACE, "legacy", "/envbridge/app/db")).isEqualTo(1)
        assertThat(api.probeKvVersion(token, VaultApi.ROOT_NAMESPACE, "legacy", "/envbridge-rw/none-${System.nanoTime()}")).isEqualTo(1)
        // a KV v1-style read of a KV v2 mount (the policy allows secret/envbridge-probe) reaches the engine, which warns
        assertThat(api.probeKvVersion(token, VaultApi.ROOT_NAMESPACE, "secret", "/envbridge-probe")).isEqualTo(2)
        // refused by the policy: the probe says nothing (the client then uses KV v2, as before KV v1 support)
        assertThat(api.probeKvVersion(token, VaultApi.ROOT_NAMESPACE, "secret", "/envbridge/app/db")).isNull()
    }

    fun `test KV v1 fields are created, changed, renamed and deleted`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val app = SecretScope("legacy", "root", "/envbridge-rw/run-${System.nanoTime()}/app")

        service.createSecret(connection, app, "token", value("t1"))
        service.createSecret(connection, app, "url", value("https://example.test"))
        service.updateSecret(connection, app, "token", value("t2"))
        assertThat(fields(connection, app)).containsOnly(
            org.assertj.core.api.Assertions.entry("token", "t2"), org.assertj.core.api.Assertions.entry("url", "https://example.test"),
        )
        assertThatThrownBy { service.createSecret(connection, app, "token", value("x")) }.hasMessageContaining("already has a field token")

        service.updateSecret(connection, app, "token", value("t2"), null, "api_token")
        assertThat(fields(connection, app)).containsOnlyKeys("api_token", "url").containsEntry("api_token", "t2")
        assertThatThrownBy { service.updateSecret(connection, app, "api_token", value("x"), null, "url") }.hasMessageContaining("already has a field url")
        assertThat(fields(connection, app)).containsEntry("api_token", "t2").containsEntry("url", "https://example.test")

        service.deleteSecrets(connection, app, listOf("url")).getValue("url").getOrThrow()
        assertThat(fields(connection, app).keys).containsExactly("api_token")
        // the last field: the secret is deleted (and can be created again)
        service.deleteSecrets(connection, app, listOf("api_token")).getValue("api_token").getOrThrow()
        assertThat(client(connection).listSecrets(app)).isEmpty()
        assertThat(client(connection).listFolders(SecretScope("legacy", "root", app.path.substringBeforeLast('/')))).isEmpty()
        service.createSecret(connection, app, "token", value("again"))
        assertThat(fields(connection, app)).containsOnly(org.assertj.core.api.Assertions.entry("token", "again"))

        // no write permission outside envbridge-rw/
        assertThatThrownBy { service.createSecret(connection, SecretScope("legacy", "root", "/envbridge/app/db"), "new", value("x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)
        assertThat(fields(connection, SecretScope("legacy", "root", "/envbridge/app/db"))).doesNotContainKey("new")
    }

    fun `test KV v1 fields move between secrets, KV versions and namespaces`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val base = "/envbridge-rw/move-${System.nanoTime()}"
        val app = SecretScope("legacy", "root", "$base/app")
        val other = SecretScope("legacy", "root", "$base/other")
        service.createSecret(connection, app, "token", value("t1"))
        service.createSecret(connection, app, "url", value("https://example.test"))
        service.createSecret(connection, other, "url", value("kept"))

        // a conflict refuses the whole move
        assertThatThrownBy { service.moveSecrets(connection, app, client(connection).listSecrets(app), other, overwrite = false) }
            .hasMessageContaining("already has \"url\"").hasMessageContaining("nothing was moved")
        assertThat(fields(connection, app).keys).containsExactlyInAnyOrder("token", "url")
        assertThat(fields(connection, other)).containsOnly(org.assertj.core.api.Assertions.entry("url", "kept"))

        val moved = service.moveSecrets(connection, app, client(connection).listSecrets(app).filter { it.key == "url" }, other, overwrite = true)
        assertThat(moved.moved).containsExactly("url")
        assertThat(fields(connection, app).keys).containsExactly("token")
        assertThat(fields(connection, other)).containsOnly(org.assertj.core.api.Assertions.entry("url", "https://example.test"))

        // KV v1 -> KV v2 (the KV v1 secret is deleted with its last field), and back
        val v2 = SecretScope("secret", "root", "$base/app")
        assertThat(service.moveSecrets(connection, app, client(connection).listSecrets(app), v2, overwrite = false).moved).containsExactly("token")
        assertThat(client(connection).listSecrets(app)).isEmpty()
        assertThat(fields(connection, v2)).containsOnly(org.assertj.core.api.Assertions.entry("token", "t1"))
        assertThat(service.moveSecrets(connection, v2, client(connection).listSecrets(v2), app, overwrite = false).moved).containsExactly("token")
        assertThat(fields(connection, app)).containsOnly(org.assertj.core.api.Assertions.entry("token", "t1"))
        assertThat(client(connection).listSecrets(v2)).isEmpty()

        if (IntegrationEnv.get("ENVBRIDGE_IT_${server}_NAMESPACES") == "true") {
            val team = SecretScope("legacy", "team-a", "$base/app")
            assertThat(service.moveSecrets(connection, app, client(connection).listSecrets(app), team, overwrite = false).moved).containsExactly("token")
            assertThat(fields(connection, team)).containsOnly(org.assertj.core.api.Assertions.entry("token", "t1"))
            assertThat(client(connection).listSecrets(app)).isEmpty()
        }
    }

    fun `test KV v1 references resolve in launches`() {
        val connection = appRole()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "legacy"
            environment = null
            path = null
            useProjectFile = false
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(resolver.plan(linkedMapOf("DB" to "vault://legacy/envbridge/app/db#password", "API" to "vault://legacy/envbridge/app/api#*")))
        assertThat(result.environment).containsEntry("DB", "v1-db-pass-$server").containsEntry("key", "v1-api-key-$server").doesNotContainKey("API")
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
