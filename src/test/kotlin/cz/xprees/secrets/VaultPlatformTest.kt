package cz.xprees.secrets

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.vault.VaultAuth
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class VaultPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockVault
    private val connections = mutableListOf<SecretConnection>()

    override fun setUp() {
        super.setUp()
        mock = MockVault()
        VaultAuth.cliTokenFile = null
    }

    override fun tearDown() {
        try {
            val secretKeys = listOf(VaultAuthMethods.OPTION_TOKEN, VaultAuthMethods.OPTION_SECRET_ID, VaultAuthMethods.OPTION_PASSWORD)
            connections.forEach { CredentialVault.removeAll(it.id, secretKeys) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
            VaultAuth.cliTokenFile = null
            mock.close()
        } finally {
            super.tearDown()
        }
    }

    private fun connection(method: String, options: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap()): SecretConnection {
        val connection = SecretConnection(
            id = "vault-${System.nanoTime()}",
            providerId = VaultProvider.ID,
            name = "Vault ${connections.size}",
            baseUrl = mock.baseUrl,
            authMethodId = method,
            options = options,
        )
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun appRole(options: Map<String, String> = emptyMap()) = connection(
        VaultAuthMethods.APPROLE,
        mapOf(VaultAuthMethods.OPTION_ROLE_ID to mock.roleId) + options,
        mapOf(VaultAuthMethods.OPTION_SECRET_ID to mock.secretId),
    )

    private fun client(connection: SecretConnection) = SecretsService.getInstance().client(connection)

    private class Interaction(val browser: (String) -> Unit = {}) : SignInInteraction {
        override fun openBrowser(url: String) = browser(url)
        override fun isCancelled(): Boolean = false
    }

    fun `test provider is registered with its grammar`() {
        val provider = SecretProvider.byScheme("vault")
        assertThat(provider).isInstanceOf(VaultProvider::class.java)
        assertThat(provider!!.supportsWrites).isTrue()
        assertThat(cz.xprees.secrets.core.reference.SecretReference.parse("vault://secret/app/db#password", listOf("vault"))!!.path).isEqualTo("/app/db")
    }

    fun `test AppRole lists KV v2 mounts, paths and fields`() {
        val connection = appRole(mapOf(VaultAuthMethods.OPTION_NAMESPACES to "team-a"))
        val client = client(connection)
        assertThat(client.hasCredentials()).isTrue()

        val project = client.listProjects().single()
        // kv1/ (KV version 1) and cubbyhole/ are not KV v2
        assertThat(project.id).isEqualTo("secret")
        assertThat(project.environments.map { it.slug }).containsExactly("root", "team-a")

        assertThat(client.listFolders(SecretScope("secret", "root")).map { it.path }).containsExactly("/app", "/other")
        assertThat(client.listFolders(SecretScope("secret", "root", "/app")).map { it.path }).containsExactly("/app/api", "/app/db")

        val fields = client.listSecrets(SecretScope("secret", "root", "/app/api")).associate { it.key to it.value.reveal() }
        // non-string values come as JSON
        assertThat(fields).containsEntry("key", "root-api-key").containsEntry("limits", """{"rps":10}""")
        // a folder with no secret of its own has no fields
        assertThat(client.listSecrets(SecretScope("secret", "root", "/"))).isEmpty()

        val read = mock.requests.last { it.path == "/v1/secret/data/app/api" }
        assertThat(read.token).startsWith("hvs.mock-")
        assertThat(read.namespace).isNull()
        assertThat(mock.requests.single { it.path == "/v1/auth/approle/login" }.body).contains(mock.roleId)
        assertThat(mock.issuedTokens()).isEqualTo(1)
    }

    fun `test recursive listing walks every secret below the path`() {
        val client = client(appRole())
        val entries = client.listSecrets(SecretScope("secret", "root", "/app", recursive = true))
        assertThat(entries.map { "${it.path}#${it.key}" })
            .containsExactlyInAnyOrder("/app#name", "/app/api#key", "/app/api#limits", "/app/db#password", "/app/db#user")
    }

    fun `test namespaces go in the namespace header`() {
        val client = client(appRole(mapOf(VaultAuthMethods.OPTION_NAMESPACE to "team-a")))
        assertThat(client.listSecrets(SecretScope("secret", "team-a", "/app/db")).single().value.reveal()).isEqualTo("team-a-db-pass")
        assertThat(mock.requests.single { it.path == "/v1/auth/approle/login" }.namespace).isEqualTo("team-a")
        assertThat(mock.requests.last { it.path.startsWith("/v1/secret/data/") }.namespace).isEqualTo("team-a")
        assertThat(client.listProjects().single().environments.map { it.slug }).containsExactly("team-a")
    }

    fun `test refused tokens lead to one new login and a retry`() {
        val client = client(appRole())
        client.listProjects()
        mock.expireTokens()
        assertThat(client.listSecrets(SecretScope("secret", "root", "/app/db"))).hasSize(2)
        assertThat(mock.issuedTokens()).isEqualTo(2)
    }

    fun `test a dead token answered only with permission denied (OpenBao) still leads to a new login`() {
        mock.openBaoErrors = true
        val client = client(appRole())
        client.listProjects()
        mock.expireTokens()
        assertThat(client.listSecrets(SecretScope("secret", "root", "/app/db"))).hasSize(2)
        assertThat(mock.issuedTokens()).isEqualTo(2)
        // a real permission problem with a valid token stays one
        assertThatThrownBy { client.listSecrets(SecretScope("forbidden", "root", "/x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)
        assertThat(mock.issuedTokens()).isEqualTo(2)
    }

    fun `test renewable tokens are renewed before they expire`() {
        mock.tokenTtl = 30 // inside the renewal margin, so the next call renews
        val client = client(appRole())
        client.listProjects()
        client.listProjects()
        assertThat(mock.requests.count { it.path == "/v1/auth/token/renew-self" }).isGreaterThanOrEqualTo(1)
        assertThat(mock.issuedTokens()).isEqualTo(1)

        mock.renewable = false
        val other = client(appRole())
        other.listProjects()
        other.listProjects()
        // not renewable: signed in again instead
        assertThat(mock.issuedTokens()).isEqualTo(3)
    }

    fun `test wrong AppRole secret is an authentication error`() {
        val connection = appRole()
        mock.secretId = "rotated"
        assertThatThrownBy { client(connection).listProjects() }
            .isInstanceOf(SecretProviderException::class.java)
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.AUTH_REQUIRED)
    }

    fun `test userpass and LDAP sign in with the stored password`() {
        for (method in listOf(VaultAuthMethods.USERPASS, VaultAuthMethods.LDAP)) {
            val connection = connection(
                method,
                mapOf(VaultAuthMethods.OPTION_USERNAME to mock.username),
                mapOf(VaultAuthMethods.OPTION_PASSWORD to mock.password),
            )
            assertThat(client(connection).signIn(Interaction())).isEqualTo("$method-${mock.username}")
            assertThat(mock.requests.last { it.path.startsWith("/v1/auth/$method/") }.path).isEqualTo("/v1/auth/$method/login/${mock.username}")
        }
    }

    fun `test token connections use the stored token or the Vault CLI's and never revoke it`() {
        mock.staticTokens["hvs.static"] = "token-user"
        val stored = connection(VaultAuthMethods.TOKEN, secrets = mapOf(VaultAuthMethods.OPTION_TOKEN to "hvs.static"))
        assertThat(client(stored).signIn(Interaction())).isEqualTo("token-user")
        assertThat(client(stored).listSecrets(SecretScope("secret", "root", "/other/x")).single().key).isEqualTo("y")
        client(stored).signOut()
        assertThat(mock.requests.none { it.path == "/v1/auth/token/revoke-self" }).isTrue()

        val file = Files.createTempFile("vault-token", "")
        try {
            Files.writeString(file, "hvs.static\n")
            VaultAuth.cliTokenFile = file
            val cli = connection(VaultAuthMethods.TOKEN)
            assertThat(client(cli).hasCredentials()).isTrue()
            assertThat(client(cli).listProjects().single().id).isEqualTo("secret")
            assertThat(mock.requests.last { it.path == "/v1/sys/internal/ui/mounts" }.token).isEqualTo("hvs.static")
        } finally {
            Files.deleteIfExists(file)
        }
        VaultAuth.cliTokenFile = null
        assertThat(client(connection(VaultAuthMethods.TOKEN)).hasCredentials()).isFalse()
    }

    fun `test OIDC signs in through the browser and the loopback callback`() {
        val port = ServerSocket(0).use { it.localPort }
        val connection = connection(
            VaultAuthMethods.OIDC,
            mapOf(VaultAuthMethods.OPTION_ROLE to "dev", VaultAuthMethods.OPTION_CALLBACK_PORT to port.toString()),
        )
        var opened: String? = null
        val http = HttpClient.newHttpClient()
        val account = client(connection).signIn(
            Interaction { url ->
                opened = url
                val query = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                assertThat(query["redirect_uri"]).isEqualTo("http://localhost:$port/oidc/callback")
                assertThat(query["role"]).isEqualTo("dev")
                // what the identity provider does: redirect the browser to the IDE (127.0.0.1: the test's resolver may prefer ::1)
                val callback = query.getValue("redirect_uri").replace("localhost", "127.0.0.1")
                Thread {
                    // a forged callback with another state is refused, the real one completes the sign-in
                    val forged = http.send(HttpRequest.newBuilder(URI("$callback?code=idp-code&state=forged")).build(), HttpResponse.BodyHandlers.ofString())
                    assertThat(forged.statusCode()).isEqualTo(400)
                    http.send(
                        HttpRequest.newBuilder(URI("$callback?code=idp-code&state=${java.net.URLEncoder.encode(query["state"], Charsets.UTF_8)}")).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }.start()
            },
        )
        assertThat(opened).startsWith(mock.baseUrl + "/idp/authorize")
        assertThat(account).isEqualTo("oidc-alice")
        assertThat(client(connection).listSecrets(SecretScope("secret", "root", "/app/db"))).hasSize(2)
        // the nonce sent with auth_url came back with the callback (the mock checks it)
        assertThat(mock.requests.single { it.path == "/v1/auth/oidc/oidc/callback" }.query).containsKey("client_nonce")

        // OIDC can't sign in again without the browser: an expired token means "sign in"
        mock.expireTokens()
        assertThatThrownBy { client(connection).listProjects() }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.AUTH_REQUIRED)
    }

    fun `test mounts come from the connection when the server can't list them`() {
        mock.mountsEndpoint = false
        assertThatThrownBy { client(appRole()).listProjects() }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("enter the mounts")
        assertThat(client(appRole(mapOf(VaultAuthMethods.OPTION_MOUNTS to "secret, kv/team/"))).listProjects().map { it.id })
            .containsExactly("secret", "kv/team")
    }

    fun `test permission denied is not mistaken for an expired token`() {
        assertThatThrownBy { client(appRole()).listSecrets(SecretScope("forbidden", "root", "/x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)
        assertThat(mock.issuedTokens()).isEqualTo(1)
    }

    fun `test launches resolve vault references against the bound connection`() {
        val connection = appRole()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "secret"
            environment = null
            path = null
            useProjectFile = false
        }
        val bindings = SecretBindingService.getInstance(project)
        assertThat(bindings.binding(VaultProvider()).environment).isEqualTo("root")

        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(
                linkedMapOf(
                    "DB_PASSWORD" to "vault://secret/app/db#password",
                    "TEAM_DB" to "vault://secret/app/db#password?namespace=team-a",
                    "ALL" to "vault://secret/app/api#*",
                    "PLAIN" to "x",
                ),
            ),
        )
        assertThat(result.environment)
            .containsEntry("DB_PASSWORD", "root-db-pass")
            .containsEntry("TEAM_DB", "team-a-db-pass")
            .containsEntry("key", "root-api-key")
            .containsEntry("PLAIN", "x")
            .doesNotContainKey("ALL")

        // fail closed on a missing field
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "vault://secret/app/db#missing"))) }
            .isInstanceOf(SecretProviderException::class.java)
    }

    fun `test an Infisical binding doesn't lend its environment to vault references`() {
        val vault = appRole()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = "some-infisical-connection"
            projectId = "p1"
            environment = "staging"
        }
        val binding = SecretBindingService.getInstance(project).binding(VaultProvider())
        assertThat(binding.connection.id).isEqualTo(vault.id)
        assertThat(binding.environment).isEqualTo("root")
        assertThat(binding.projectId).isNull()
    }

    fun `test fields are created, changed and deleted with check-and-set, keeping the other fields`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val api = SecretScope("secret", "root", "/app/api")

        service.createSecret(connection, api, "token", SecretValue("t1"))
        var fields = client(connection).listSecrets(api).associate { it.key to it.value.reveal() }
        // the JSON field stays JSON (not turned into text by the rewrite)
        assertThat(fields).containsEntry("token", "t1").containsEntry("key", "root-api-key").containsEntry("limits", """{"rps":10}""")
        val written = mock.requests.last { it.method == "POST" && it.path == "/v1/secret/data/app/api" }
        assertThat(written.body).contains("\"cas\":1").contains("\"limits\":{\"rps\":10")

        service.updateSecret(connection, api, "token", SecretValue("t2"))
        assertThat(mock.requests.last { it.method == "POST" && it.path == "/v1/secret/data/app/api" }.body).contains("\"cas\":2")
        fields = client(connection).listSecrets(api).associate { it.key to it.value.reveal() }
        assertThat(fields).containsEntry("token", "t2")

        assertThat(service.deleteSecrets(connection, api, listOf("token")).getValue("token").isSuccess).isTrue()
        assertThat(client(connection).listSecrets(api).map { it.key }).containsExactlyInAnyOrder("key", "limits")

        // a new secret path: check-and-set 0 (only if it doesn't exist)
        service.createSecret(connection, SecretScope("secret", "root", "/app/new"), "first", SecretValue("1"))
        assertThat(mock.requests.last { it.method == "POST" && it.path == "/v1/secret/data/app/new" }.body).contains("\"cas\":0")
    }

    fun `test a field is renamed in one check-and-set write, keeping its place, type and the other fields`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val api = SecretScope("secret", "root", "/app/api")

        // "limits" is JSON: renamed with its value left as listed, it stays JSON
        service.updateSecret(connection, api, "limits", SecretValue("""{"rps":10}"""), null, "rate_limits")
        val written = mock.requests.last { it.method == "POST" && it.path == "/v1/secret/data/app/api" }
        assertThat(written.body).contains("\"cas\":1").contains("\"rate_limits\":{\"rps\":10").doesNotContain("\"limits\"")
        assertThat(mock.secrets.getValue("root").getValue("secret/app/api").keys).containsExactly("key", "rate_limits")
        assertThat(client(connection).listSecrets(api).associate { it.key to it.value.reveal() })
            .containsEntry("key", "root-api-key").containsEntry("rate_limits", """{"rps":10}""").doesNotContainKey("limits")

        // with a new value: renamed and changed in the same version
        service.updateSecret(connection, api, "key", SecretValue("rotated"), null, "api_key")
        assertThat(mock.requests.last { it.method == "POST" && it.path == "/v1/secret/data/app/api" }.body).contains("\"cas\":2")
        assertThat(client(connection).listSecrets(api).associate { it.key to it.value.reveal() }).containsOnly(
            org.assertj.core.api.Assertions.entry("api_key", "rotated"), org.assertj.core.api.Assertions.entry("rate_limits", """{"rps":10}"""),
        )

        // a name the secret already has: refused, nothing written
        val writes = mock.writes.size
        assertThatThrownBy { service.updateSecret(connection, api, "api_key", SecretValue("x"), null, "rate_limits") }
            .hasMessageContaining("already has a field rate_limits")
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.CONFIGURATION)
        assertThatThrownBy { service.updateSecret(connection, api, "api_key", SecretValue("x"), null, "") }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.CONFIGURATION)
        assertThatThrownBy { service.updateSecret(connection, api, "missing", SecretValue("x"), null, "other") }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.NOT_FOUND)
        assertThat(mock.writes).hasSize(writes)
    }

    fun `test refused writes say why`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        assertThatThrownBy { service.createSecret(connection, SecretScope("secret", "root", "/app/db"), "password", SecretValue("x")) }
            .hasMessageContaining("already has a field password")
        assertThatThrownBy { service.updateSecret(connection, SecretScope("secret", "root", "/app/db"), "missing", SecretValue("x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.NOT_FOUND)
        assertThatThrownBy { service.createSecret(connection, SecretScope("secret", "root", "/"), "K", SecretValue("x")) }
            .hasMessageContaining("Choose a secret path")
        mock.readOnlyMounts += "secret"
        assertThatThrownBy { service.createSecret(connection, SecretScope("secret", "root", "/app/db"), "other", SecretValue("x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)
        assertThatThrownBy { service.createEnvironment(connection, "secret", "Team B", "team-b") }.hasMessageContaining("Vault administrator")
    }

    fun `test a change made meanwhile is refused, not overwritten`() {
        val connection = appRole()
        val scope = SecretScope("secret", "root", "/app/db")
        client(connection).listProjects()
        // Someone else writes a new version between our read and our write
        mock.versions["root:secret/app/db"] = 5
        val api = cz.xprees.secrets.vault.VaultApi(cz.xprees.secrets.vault.VaultHttp(mock.baseUrl))
        val token = TokenSetAccess.token(connection)
        val stale = api.readVersioned(token, "root", "secret", "/app/db").copy(version = 4)
        assertThatThrownBy { api.write(token, "root", "secret", "/app/db", stale.fields, stale.version) }
            .hasMessageContaining("changed meanwhile")
        // the client itself reads the current version, so its own writes go through
        SecretsService.getInstance().updateSecret(connection, scope, "user", SecretValue("other-user"))
        assertThat(client(connection).listSecrets(scope).first { it.key == "user" }.value.reveal()).isEqualTo("other-user")
    }

    fun `test moving fields between secrets and namespaces`() {
        val connection = appRole(mapOf(VaultAuthMethods.OPTION_NAMESPACES to "team-a"))
        val service = SecretsService.getInstance()
        val source = SecretScope("secret", "root", "/app/db")
        val entries = client(connection).listSecrets(source)

        val moved = service.moveSecrets(connection, source, entries.filter { it.key == "user" }, SecretScope("secret", "team-a", "/app/db"), overwrite = false)
        assertThat(moved.moved).containsExactly("user")
        assertThat(client(connection).listSecrets(source).map { it.key }).containsExactly("password")
        assertThat(client(connection).listSecrets(SecretScope("secret", "team-a", "/app/db")).map { it.key }).containsExactlyInAnyOrder("password", "user")

        // an existing field at the destination is kept unless overwriting
        val writesBefore = mock.writes.size
        assertThatThrownBy { service.moveSecrets(connection, source, client(connection).listSecrets(source), SecretScope("secret", "team-a", "/app/db"), overwrite = false) }
            .hasMessageContaining("already has \"password\"").hasMessageContaining("nothing was moved")
        assertThat(mock.writes.size).isEqualTo(writesBefore)
        // the same secret as source and destination is refused, not reported as copied
        assertThatThrownBy { service.moveSecrets(connection, source, client(connection).listSecrets(source), source, overwrite = true) }
            .hasMessageContaining("nothing was moved")
        assertThat(client(connection).listSecrets(source).single().value.reveal()).isEqualTo("root-db-pass")

        // moving the last field away deletes the source's latest version (restorable in Vault)
        val all = service.moveSecrets(connection, source, client(connection).listSecrets(source), SecretScope("secret", "root", "/app/archive"), overwrite = false)
        assertThat(all.moved).containsExactly("password")
        assertThat(client(connection).listSecrets(source)).isEmpty()
        assertThat(mock.requests.any { it.method == "DELETE" && it.path == "/v1/secret/data/app/db" }).isTrue()
        // and the path can be written again (check-and-set names the deleted version)
        service.createSecret(connection, source, "password", SecretValue("again"))
        assertThat(client(connection).listSecrets(source).single().value.reveal()).isEqualTo("again")
    }

    fun `test a failed removal is reported and the copy kept`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val source = SecretScope("secret", "root", "/app/db")
        val entries = client(connection).listSecrets(source).filter { it.key == "user" }
        // The destination accepts the copy; the source refuses the removal
        mock.readOnlyMounts.clear()
        val result = run {
            mock.versions.remove("root:secret/app/db")
            val destination = SecretScope("secret", "root", "/app/copy")
            // make the source read-only only after the copy: a second mount stands in for a read-only source
            mock.secrets.getValue("root")["ro/app/db"] = mapOf("user" to "app")
            mock.readOnlyMounts += "ro"
            service.moveSecrets(connection, SecretScope("ro", "root", "/app/db"), entries, destination, overwrite = false)
        }
        assertThat(result.moved).isEmpty()
        assertThat(result.notRemoved.getValue("user")).contains("copied, but not removed")
        assertThat(client(connection).listSecrets(SecretScope("secret", "root", "/app/copy")).single().key).isEqualTo("user")
        assertThat(client(connection).listSecrets(SecretScope("ro", "root", "/app/db")).single().key).isEqualTo("user")
    }

    fun `test read-only in the settings stops vault writes before they are sent`() {
        val connection = appRole()
        cz.xprees.secrets.core.settings.SecretsAppSettings.getInstance().state.readOnly = true
        try {
            assertThat(SecretsService.getInstance().writesAllowed(connection)).isFalse()
            assertThatThrownBy { SecretsService.getInstance().createSecret(connection, SecretScope("secret", "root", "/app/db"), "K", SecretValue("v")) }
                .hasMessageContaining("Read-only")
            assertThat(mock.requests.none { it.method == "POST" && it.path.contains("/data/") }).isTrue()
        } finally {
            cz.xprees.secrets.core.settings.SecretsAppSettings.getInstance().state.readOnly = false
        }
        assertThat(SecretsService.getInstance().writesAllowed(connection)).isTrue()
    }

    private object TokenSetAccess {
        fun token(connection: SecretConnection): String =
            cz.xprees.secrets.core.auth.TokenSet.fromJson(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)!!)!!.accessToken
    }
}
