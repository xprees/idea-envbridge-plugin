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
import org.assertj.core.api.Assertions.entry
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
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null; restrictToBoundProject = true }
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

    fun `test AppRole lists KV mounts, paths and fields`() {
        val connection = appRole(mapOf(VaultAuthMethods.OPTION_NAMESPACES to "team-a"))
        val client = client(connection)
        assertThat(client.hasCredentials()).isTrue()

        // KV version 1 and 2 mounts; cubbyhole/ is not a KV engine
        assertThat(client.listProjects().map { it.id }).containsExactly("kv1", "secret")
        val project = client.listProjects().first { it.id == "secret" }
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
        assertThat(client.listProjects().first().environments.map { it.slug }).containsExactly("team-a")
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
            assertThat(client(cli).listProjects().map { it.id }).containsExactly("kv1", "secret")
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
            mock.mounts["ro"] = "2"
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

    // ---- KV version 1 ----------------------------------------------------------------------------------------------

    private fun fields(connection: SecretConnection, scope: SecretScope) = client(connection).listSecrets(scope).associate { it.key to it.value.reveal() }

    fun `test KV v1 mounts are read, listed and recognised by the CLI's preflight`() {
        val connection = appRole(mapOf(VaultAuthMethods.OPTION_NAMESPACES to "team-a", VaultAuthMethods.OPTION_MOUNTS to "kv1"))
        val client = client(connection)
        // the mount is configured, not listed: its version comes from the preflight
        assertThat(client.listProjects().map { it.id }).containsExactly("kv1")
        assertThat(client.listFolders(SecretScope("kv1", "root")).map { it.path }).containsExactly("/app", "/top")
        assertThat(client.listFolders(SecretScope("kv1", "root", "/app")).map { it.path }).containsExactly("/app/api", "/app/db")
        assertThat(fields(connection, SecretScope("kv1", "root", "/app/api"))).containsOnly(
            org.assertj.core.api.Assertions.entry("key", "v1-api-key"), org.assertj.core.api.Assertions.entry("limits", """{"rps":5}"""),
        )
        assertThat(client.listSecrets(SecretScope("kv1", "root", "/"))).isEmpty()
        assertThat(client.listSecrets(SecretScope("kv1", "root", "/app/missing"))).isEmpty()
        assertThat(client.listSecrets(SecretScope("kv1", "root", "/", recursive = true)).map { "${it.path}#${it.key}" })
            .containsExactlyInAnyOrder("/top#t", "/app/api#key", "/app/api#limits", "/app/db#password", "/app/db#user")
        assertThat(fields(connection, SecretScope("kv1", "team-a", "/app/db"))).containsOnly(org.assertj.core.api.Assertions.entry("password", "team-a-v1-pass"))

        // KV v1 paths have no data/ or metadata/ prefix; one preflight per mount and namespace, then remembered
        assertThat(mock.requests.filter { it.path.startsWith("/v1/kv1") }.map { it.path }).allMatch { !it.contains("/data/") && !it.contains("/metadata") }
        assertThat(mock.requests.last { it.path == "/v1/kv1/app/api" }.method).isEqualTo("GET")
        assertThat(mock.requests.filter { it.path == "/v1/kv1/" }.map { it.query["list"] }).contains("true")
        assertThat(mock.requests.filter { it.path == "/v1/sys/internal/ui/mounts/kv1" }.map { it.namespace }).containsExactly(null, "team-a")
        // the KV v2 mount is unaffected
        assertThat(fields(connection, SecretScope("secret", "root", "/app/db"))).containsEntry("password", "root-db-pass")
    }

    fun `test listing the mounts remembers their versions, so reads need no preflight`() {
        val connection = appRole()
        client(connection).listProjects()
        assertThat(fields(connection, SecretScope("kv1", "root", "/app/db"))).containsEntry("password", "v1-db-pass")
        assertThat(fields(connection, SecretScope("secret", "root", "/app/db"))).containsEntry("password", "root-db-pass")
        assertThat(mock.requests.none { it.path.startsWith("/v1/sys/internal/ui/mounts/") }).isTrue()
    }

    fun `test a mount without options is KV v1, as for the Vault CLI`() {
        mock.mounts["legacy"] = ""
        mock.secrets.getValue("root")["legacy/app"] = mapOf("old" to "value")
        val connection = appRole()
        assertThat(client(connection).listProjects().map { it.id }).contains("legacy")
        assertThat(fields(connection, SecretScope("legacy", "root", "/app"))).containsOnly(org.assertj.core.api.Assertions.entry("old", "value"))
    }

    fun `test without the preflight the version is probed at the path`() {
        mock.mountPreflight = false
        val connection = appRole()
        // KV v1: the path read the KV v1 way answers
        assertThat(fields(connection, SecretScope("kv1", "root", "/app/db"))).containsEntry("password", "v1-db-pass")
        // KV v2: the KV v1-style probe gets KV v2's "invalid path" warning
        assertThat(fields(connection, SecretScope("secret", "root", "/app/db"))).containsEntry("password", "root-db-pass")
        assertThat(mock.requests.filter { it.path == "/v1/secret/app/db" }).hasSize(1)
        // a new KV v1 secret: no such secret (a plain 404) also means KV v1
        SecretsService.getInstance().createSecret(connection, SecretScope("kv1", "root", "/fresh"), "k", SecretValue("v"))
        assertThat(mock.secrets.getValue("root")["kv1/fresh"]).isEqualTo(mapOf("k" to "v"))
        // a probe of a name KV v2 reserves lists the mount's root instead
        val other = appRole()
        assertThat(client(other).listSecrets(SecretScope("secret", "root", "/config"))).isEmpty()
        assertThat(mock.requests.none { it.path == "/v1/secret/config" }).isTrue()
        assertThat(mock.requests.last { it.path == "/v1/secret/" }.query).containsEntry("list", "true")
    }

    fun `test KV v1 fields are created, changed, renamed and deleted by rewriting the whole secret`() {
        val connection = appRole()
        val service = SecretsService.getInstance()
        val api = SecretScope("kv1", "root", "/app/api")

        service.createSecret(connection, api, "token", SecretValue("t1"))
        val created = mock.requests.last { it.method == "POST" && it.path == "/v1/kv1/app/api" }
        // the whole secret, as fields (no data/options envelope, no check-and-set); the JSON field stays JSON
        val body = com.google.gson.JsonParser.parseString(created.body).asJsonObject
        assertThat(body.keySet()).containsExactly("key", "limits", "token")
        assertThat(body.getAsJsonObject("limits").get("rps").asInt).isEqualTo(5)
        assertThat(fields(connection, api)).containsEntry("token", "t1").containsEntry("key", "v1-api-key").containsEntry("limits", """{"rps":5}""")

        service.updateSecret(connection, api, "token", SecretValue("t2"))
        assertThat(fields(connection, api)).containsEntry("token", "t2")

        service.updateSecret(connection, api, "token", SecretValue("t3"), null, "api_token")
        assertThat(mock.secrets.getValue("root").getValue("kv1/app/api").keys).containsExactly("key", "limits", "api_token")
        assertThat(fields(connection, api)).containsEntry("api_token", "t3").doesNotContainKey("token")

        // conflicts and missing fields are refused before anything is written
        val writes = mock.writes.size
        assertThatThrownBy { service.updateSecret(connection, api, "api_token", SecretValue("x"), null, "key") }
            .hasMessageContaining("already has a field key")
        assertThatThrownBy { service.createSecret(connection, api, "key", SecretValue("x")) }.hasMessageContaining("already has a field key")
        assertThatThrownBy { service.updateSecret(connection, api, "missing", SecretValue("x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.NOT_FOUND)
        assertThatThrownBy { service.createSecret(connection, SecretScope("kv1", "root", "/"), "K", SecretValue("x")) }
            .hasMessageContaining("Choose a secret path")
        assertThat(mock.writes).hasSize(writes)

        assertThat(service.deleteSecrets(connection, api, listOf("api_token")).getValue("api_token").isSuccess).isTrue()
        assertThat(fields(connection, api).keys).containsExactlyInAnyOrder("key", "limits")

        // removing the last field deletes the secret
        val top = SecretScope("kv1", "root", "/top")
        service.deleteSecrets(connection, top, listOf("t")).getValue("t").getOrThrow()
        assertThat(mock.secrets.getValue("root")).doesNotContainKey("kv1/top")
        assertThat(mock.requests.last { it.path == "/v1/kv1/top" }.method).isEqualTo("DELETE")
        assertThat(mock.requests.none { it.path.startsWith("/v1/kv1/") && it.body.contains("\"cas\"") }).isTrue()

        mock.readOnlyMounts += "kv1"
        assertThatThrownBy { service.createSecret(connection, SecretScope("kv1", "root", "/app/db"), "other", SecretValue("x")) }
            .extracting { (it as SecretProviderException).kind }.isEqualTo(Kind.FORBIDDEN)
    }

    fun `test KV v1 fields move between secrets, namespaces and KV versions without losing one`() {
        val connection = appRole(mapOf(VaultAuthMethods.OPTION_NAMESPACES to "team-a"))
        val service = SecretsService.getInstance()
        val source = SecretScope("kv1", "root", "/app/db")

        val user = client(connection).listSecrets(source).filter { it.key == "user" }
        assertThat(service.moveSecrets(connection, source, user, SecretScope("kv1", "team-a", "/app/db"), overwrite = false).moved).containsExactly("user")
        assertThat(fields(connection, source).keys).containsExactly("password")
        assertThat(fields(connection, SecretScope("kv1", "team-a", "/app/db"))).containsEntry("user", "app1").containsEntry("password", "team-a-v1-pass")
        // the destination was written before the source
        val writeOrder = mock.writes.toList()
        assertThat(writeOrder.indexOf("team-a:kv1/app/db")).isLessThan(writeOrder.indexOf("root:kv1/app/db"))

        // a conflict refuses the whole move before anything is written
        val writes = mock.writes.size
        assertThatThrownBy { service.moveSecrets(connection, source, client(connection).listSecrets(source), SecretScope("kv1", "team-a", "/app/db"), overwrite = false) }
            .hasMessageContaining("already has \"password\"").hasMessageContaining("nothing was moved")
        assertThat(mock.writes).hasSize(writes)

        // KV v1 -> KV v2: the last field leaves, the KV v1 secret is deleted
        val toV2 = service.moveSecrets(connection, source, client(connection).listSecrets(source), SecretScope("secret", "root", "/moved"), overwrite = false)
        assertThat(toV2.moved).containsExactly("password")
        assertThat(mock.secrets.getValue("root")).doesNotContainKey("kv1/app/db")
        assertThat(fields(connection, SecretScope("secret", "root", "/moved"))).containsOnly(org.assertj.core.api.Assertions.entry("password", "v1-db-pass"))
        assertThat(mock.requests.last { it.method == "POST" && it.path == "/v1/secret/data/moved" }.body).contains("\"cas\":0")

        // KV v2 -> KV v1, overwriting
        mock.secrets.getValue("root")["kv1/app/api"] = mapOf("key" to "old")
        val api = SecretScope("secret", "root", "/app/api")
        val toV1 = service.moveSecrets(connection, api, client(connection).listSecrets(api).filter { it.key == "key" }, SecretScope("kv1", "root", "/app/api"), overwrite = true)
        assertThat(toV1.moved).containsExactly("key")
        assertThat(fields(connection, SecretScope("kv1", "root", "/app/api"))).containsOnly(org.assertj.core.api.Assertions.entry("key", "root-api-key"))
    }

    fun `test a KV v1 source that refuses the removal keeps both copies`() {
        val connection = appRole()
        mock.mounts["kv1ro"] = "1"
        mock.secrets.getValue("root")["kv1ro/app"] = mapOf("user" to "app")
        mock.readOnlyMounts += "kv1ro"
        val source = SecretScope("kv1ro", "root", "/app")
        val result = SecretsService.getInstance().moveSecrets(connection, source, client(connection).listSecrets(source), SecretScope("kv1", "root", "/copy"), overwrite = false)
        assertThat(result.moved).isEmpty()
        assertThat(result.notRemoved.getValue("user")).contains("copied, but not removed")
        assertThat(fields(connection, SecretScope("kv1", "root", "/copy"))).containsEntry("user", "app")
        assertThat(fields(connection, source)).containsEntry("user", "app")
    }

    fun `test a mount upgraded to KV v2 is detected again`() {
        val connection = appRole()
        val scope = SecretScope("kv1", "root", "/app/db")
        assertThat(fields(connection, scope)).containsEntry("password", "v1-db-pass")
        // vault kv enable-versioning: the remembered KV v1 is refused by the server, then forgotten
        mock.mounts["kv1"] = "2"
        assertThatThrownBy { client(connection).listSecrets(scope) }.hasMessageContaining("KV version 2 engine now")
        assertThat(fields(connection, scope)).containsEntry("password", "v1-db-pass")
        assertThat(mock.requests.last { it.path.startsWith("/v1/kv1/") }.path).isEqualTo("/v1/kv1/data/app/db")
    }

    fun `test the dashboard link follows the mount's KV version`() {
        val connection = appRole()
        val provider = VaultProvider()
        // not known yet: KV v2 routes
        assertThat(provider.dashboardUrl(connection, SecretScope("kv1", "root", "/app/db"))).isEqualTo("${mock.baseUrl}/ui/vault/secrets/kv1/kv/app%2Fdb/details")
        client(connection).listProjects()
        assertThat(provider.dashboardUrl(connection, SecretScope("kv1", "root", "/app/db"))).isEqualTo("${mock.baseUrl}/ui/vault/secrets/kv1/show/app/db")
        assertThat(provider.dashboardUrl(connection, SecretScope("kv1", "root"))).isEqualTo("${mock.baseUrl}/ui/vault/secrets/kv1/list")
        assertThat(provider.dashboardUrl(connection, SecretScope("secret", "root", "/app/db"))).isEqualTo("${mock.baseUrl}/ui/vault/secrets/secret/kv/app%2Fdb/details")
    }

    fun `test KV v1 references resolve in launches`() {
        val connection = appRole()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "kv1"
            environment = null
            path = null
            useProjectFile = false
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(linkedMapOf("DB" to "vault://kv1/app/db#password", "ALL" to "vault://kv1/app/api#*", "TEAM" to "vault://kv1/app/db#password?namespace=team-a")),
        )
        assertThat(result.environment).containsEntry("DB", "v1-db-pass").containsEntry("key", "v1-api-key")
            .containsEntry("limits", """{"rps":5}""").containsEntry("TEAM", "team-a-v1-pass").doesNotContainKey("ALL")
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "vault://kv1/app/db#missing"))) }
            .isInstanceOf(SecretProviderException::class.java)
    }

    // ---- Environment layout ------------------------------------------------------------------------------------------

    /** Paths laid out as mount/project/env/… in a KV v2 mount `apps` and a KV v1 mount `apps1`, and env/project/… in `byenv`. */
    private fun seedLayout() {
        mock.mounts["apps"] = "2"
        mock.mounts["apps1"] = "1"
        mock.mounts["byenv"] = "2"
        mock.secrets.getValue("root").putAll(
            mapOf(
                "apps/myapp/dev" to mapOf("MODE" to "development"),
                "apps/myapp/dev/db" to mapOf("password" to "dev-db-pass", "user" to "app"),
                "apps/myapp/prod" to mapOf("MODE" to "production"),
                "apps/myapp/prod/db" to mapOf("password" to "prod-db-pass", "user" to "app"),
                "apps/web/staging/api" to mapOf("key" to "web-key"),
                "apps1/myapp/dev/db" to mapOf("password" to "v1-dev-pass"),
                "apps1/myapp/staging/db" to mapOf("password" to "v1-staging-pass"),
                "byenv/dev/myapp/db" to mapOf("password" to "by-env-dev"),
                "byenv/prod/myapp/db" to mapOf("password" to "by-env-prod"),
                "byenv/prod/other" to mapOf("x" to "y"),
            ),
        )
        // the same paths in a namespace: a layout reads in the connection's namespace only
        mock.secrets.getValue("team-a")["apps/myapp/dev/db"] = mapOf("password" to "team-a-dev-pass")
    }

    private fun layoutConnection(layout: String = "{project}/{env}", mounts: String = "apps, apps1", options: Map<String, String> = emptyMap()) =
        appRole(mapOf(VaultAuthMethods.OPTION_ENVIRONMENT_LAYOUT to layout, VaultAuthMethods.OPTION_MOUNTS to mounts) + options)

    private fun bind(connection: SecretConnection, projectId: String, environment: String?) {
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            this.projectId = projectId
            this.environment = environment
            path = null
            useProjectFile = false
        }
    }

    fun `test an environment layout lists projects and environments from paths, KV v1 and v2`() {
        seedLayout()
        val connection = layoutConnection(options = mapOf(VaultAuthMethods.OPTION_NAMESPACES to "team-a"))
        val client = client(connection)
        val before = mock.requests.size
        val projects = client.listProjects()
        assertThat(projects.map { it.id }).containsExactly("apps/myapp/{env}", "apps/web/{env}", "apps1/myapp/{env}")
        assertThat(projects.map { it.name }).containsExactly("apps/myapp", "apps/web", "apps1/myapp")
        // environments are secrets or folders at the {env} level; More namespaces aren't environments any more
        assertThat(projects[0].environments.map { it.slug }).containsExactly("dev", "prod")
        assertThat(projects[1].environments.map { it.slug }).containsExactly("staging")
        assertThat(projects[2].environments.map { it.slug }).containsExactly("dev", "staging")
        // one LIST per mount and one per project (the mounts are configured; KV v1's version from the preflight)
        val lists = mock.requests.drop(before).filter { it.query["list"] == "true" }.map { it.path }
        assertThat(lists).containsExactlyInAnyOrder(
            "/v1/apps/metadata/", "/v1/apps/metadata/myapp", "/v1/apps/metadata/web", "/v1/apps1/", "/v1/apps1/myapp",
        )

        val dev = SecretScope("apps/myapp/{env}", "dev", "/")
        // the environment's own path holds keys; folders are the paths below it
        assertThat(client.listSecrets(dev).associate { it.key to it.value.reveal() }).containsExactly(entry("MODE", "development"))
        assertThat(client.listFolders(dev).map { it.path }).containsExactly("/db")
        assertThat(client.listSecrets(dev.copy(path = "/db")).map { "${it.path}#${it.key}=${it.value.reveal()}" })
            .containsExactlyInAnyOrder("/db#password=dev-db-pass", "/db#user=app")
        assertThat(client.listSecrets(dev.copy(environment = "prod", recursive = true)).map { "${it.path}#${it.key}" })
            .containsExactlyInAnyOrder("/#MODE", "/db#password", "/db#user")
        assertThat(client.listSecrets(SecretScope("apps1/myapp/{env}", "staging", "/db")).single().value.reveal()).isEqualTo("v1-staging-pass")
        // read in the connection's namespace (root: no header), never in a namespace named like the environment
        assertThat(mock.requests.last { it.path == "/v1/apps/data/myapp/dev/db" }.namespace).isNull()
        assertThat(mock.requests.none { it.namespace == "dev" || it.namespace == "prod" }).isTrue()

        // {env}/{project}, and {env} alone (the project is the mount)
        val byEnv = client(layoutConnection("{env}/{project}", "byenv")).listProjects()
        assertThat(byEnv.associate { it.id to it.environments.map { e -> e.slug } })
            .containsExactly(entry("byenv/{env}/myapp", listOf("dev", "prod")), entry("byenv/{env}/other", listOf("prod")))
        val envOnly = client(layoutConnection("{env}", "apps1"))
        assertThat(envOnly.listProjects().single().let { it.id to it.environments.map { e -> e.slug } }).isEqualTo("apps1/{env}" to listOf("myapp"))

        // the namespace of the connection is where a layout reads
        val team = client(layoutConnection(options = mapOf(VaultAuthMethods.OPTION_NAMESPACE to "team-a")))
        assertThat(team.listSecrets(SecretScope("apps/myapp/{env}", "dev", "/db")).single().value.reveal()).isEqualTo("team-a-dev-pass")
        assertThat(mock.requests.last { it.path == "/v1/apps/data/myapp/dev/db" }.namespace).isEqualTo("team-a")
    }

    fun `test the environment layout option is validated and switches environments per connection`() {
        val provider = VaultProvider()
        val option = cz.xprees.secrets.vault.VaultAuthMethods.ALL.first().options.single { it.key == VaultAuthMethods.OPTION_ENVIRONMENT_LAYOUT }
        assertThat(option.validate!!("{project}/{env}")).isNull()
        assertThat(option.validate!!("apps/{project}/{env}")).isNull()
        assertThat(option.validate!!("{project}")).contains("{env} exactly once")
        assertThat(option.validate!!("{project}-{env}")).contains("whole segment")
        assertThat(option.validate!!("{env}/../{project}")).contains(". or ..")
        assertThat(option.validate!!("{project}/{env}/{project}")).contains("at most once")

        val plain = appRole()
        val layered = layoutConnection()
        assertThat(provider.switchesEnvironments(plain)).isFalse()
        assertThat(provider.switchesEnvironments(layered)).isTrue()
        assertThat(provider.defaultEnvironment(plain)).isEqualTo("root")
        assertThat(provider.defaultEnvironment(layered)).isEqualTo("dev")
        fun ref(text: String) = cz.xprees.secrets.core.reference.SecretReference.parse(text, listOf("vault"))!!
        assertThat(provider.followsSelection(layered, ref("vault://./db#password"))).isTrue()
        assertThat(provider.followsSelection(layered, ref("vault://./db#password?env=prod"))).isFalse()
        assertThat(provider.followsSelection(layered, ref("vault://apps/myapp/dev/db#password"))).isFalse()
        assertThat(provider.followsSelection(plain, ref("vault://./db#password"))).isFalse()
        // a full path reads in the connection's namespace, not in the selected environment
        val defaults = cz.xprees.secrets.core.reference.SecretReference.Defaults("apps", "dev")
        assertThat(provider.referenceDefaults(layered, ref("vault://apps/myapp/dev/db#password"), defaults).environment).isEqualTo("root")
        assertThat(provider.referenceDefaults(layered, ref("vault://./db#password"), defaults)).isEqualTo(defaults)
        assertThat(provider.referenceDefaults(plain, ref("vault://apps/myapp/dev/db#password"), defaults)).isEqualTo(defaults)
        assertThat(provider.equivalentScope(layered, SecretScope("apps/myapp/{env}", "prod", "/db")))
            .isEqualTo(SecretScope("apps", "root", "/myapp/prod/db"))
        assertThat(provider.equivalentScope(plain, SecretScope("secret", "root", "/app"))).isNull()

        // the keys index: like Infisical with a layout (every environment's root and the bound folder), else the bound folder
        val environments = listOf("dev", "prod").map { cz.xprees.secrets.core.model.ProviderEnvironment(it, it) }
        val layoutPlan = provider.keysIndexScope(SecretScope("apps/myapp/{env}", "dev", "/db"), environments)
        assertThat(layoutPlan.environments).containsExactly("dev", "prod")
        assertThat(layoutPlan.folders).isEqualTo(SecretScope("apps/myapp/{env}", "dev", "/db", recursive = true))
        val mountPlan = provider.keysIndexScope(SecretScope("secret", "root", "/app"), environments)
        assertThat(mountPlan.environments).isEmpty()

        // the dashboard opens the full path
        assertThat(provider.dashboardUrl(layered, SecretScope("apps/myapp/{env}", "prod", "/db")))
            .isEqualTo("${mock.baseUrl}/ui/vault/secrets/apps/kv/myapp%2Fprod%2Fdb/details")
    }

    fun `test launches resolve short references in the selected environment and full ones as written`() {
        seedLayout()
        val connection = layoutConnection()
        bind(connection, "apps/myapp/{env}", "dev")
        val bindings = SecretBindingService.getInstance(project)
        assertThat(bindings.binding(VaultProvider()).environment).isEqualTo("dev")
        val resolver = LaunchSecretResolver.getInstance(project)
        val envs = linkedMapOf(
            "DB" to "vault://./db#password",
            "MODE" to "vault://#MODE",
            "PROD_DB" to "vault://./db#password?env=prod",
            "FULL" to "vault://apps/myapp/prod/db#user",
            "ALL" to "vault://./db#*",
        )
        // "Only use the bound project" (on by default) accepts a full path into the bound layout project
        val dev = resolver.resolve(resolver.plan(envs)).environment
        assertThat(dev).containsEntry("DB", "dev-db-pass").containsEntry("MODE", "development").containsEntry("PROD_DB", "prod-db-pass")
            .containsEntry("FULL", "app").containsEntry("password", "dev-db-pass").doesNotContainKey("ALL")
        val other = mapOf("OTHER" to "vault://./api#key?project=apps/web/{env}&env=staging")
        assertThatThrownBy { resolver.resolve(resolver.plan(other)) }.hasMessageContaining("names another project")
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "vault://apps/web/staging/api#key"))) }.hasMessageContaining("names another project")
        ProjectSecretsSettings.getInstance(project).update { restrictToBoundProject = false }
        assertThat(resolver.resolve(resolver.plan(other)).environment).containsEntry("OTHER", "web-key")
        // the full reference was read without a namespace header (the environment "dev" is no namespace)
        assertThat(mock.requests.filter { it.path.startsWith("/v1/apps/data/") }.map { it.namespace }.distinct()).containsExactly(null)

        // switching the environment in the IDE, ENVBRIDGE_ENVIRONMENT and Run with environment… move the short references only
        bind(connection, "apps/myapp/{env}", "prod")
        val prod = resolver.resolve(resolver.plan(envs)).environment
        assertThat(prod).containsEntry("DB", "prod-db-pass").containsEntry("MODE", "production").containsEntry("FULL", "app")
        bind(connection, "apps/myapp/{env}", "dev")
        val overridden = resolver.resolve(resolver.plan(envs + ("ENVBRIDGE_ENVIRONMENT" to "prod"))).environment
        assertThat(overridden).containsEntry("DB", "prod-db-pass").containsEntry("PROD_DB", "prod-db-pass")
        assertThat(resolver.resolve(resolver.plan(mapOf("DB" to "vault://./db#password")), environment = "prod").environment).containsEntry("DB", "prod-db-pass")
        // an environment without the secret fails closed
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("DB" to "vault://./db#password")), environment = "staging") }
            .isInstanceOf(SecretProviderException::class.java)

        // KV v1 the same way
        bind(connection, "apps1/myapp/{env}", "staging")
        assertThat(resolver.resolve(resolver.plan(mapOf("DB" to "vault://./db#password"))).environment).containsEntry("DB", "v1-staging-pass")
    }

    fun `test writes with a layout go to the environment's path, and new environments appear once written`() {
        seedLayout()
        val connection = layoutConnection()
        val service = SecretsService.getInstance()
        val dev = SecretScope("apps/myapp/{env}", "dev", "/")

        // the environment's own path is a secret path (unlike a mount's root)
        service.createSecret(connection, dev, "LOG_LEVEL", SecretValue("debug"))
        assertThat(mock.requests.last { it.method == "POST" }.path).isEqualTo("/v1/apps/data/myapp/dev")
        assertThat(mock.secrets.getValue("root").getValue("apps/myapp/dev")).containsEntry("LOG_LEVEL", "debug").containsEntry("MODE", "development")
        service.updateSecret(connection, dev.copy(path = "/db"), "password", SecretValue("new-dev-pass"))
        assertThat(mock.secrets.getValue("root").getValue("apps/myapp/dev/db")).containsEntry("password", "new-dev-pass").containsEntry("user", "app")
        assertThat(mock.secrets.getValue("root").getValue("apps/myapp/prod/db")).containsEntry("password", "prod-db-pass")
        // KV v1: the whole secret as the body
        service.createSecret(connection, SecretScope("apps1/myapp/{env}", "dev", "/db"), "user", SecretValue("v1-user"))
        assertThat(mock.requests.last { it.method == "POST" }.path).isEqualTo("/v1/apps1/myapp/dev/db")
        assertThat(mock.secrets.getValue("root").getValue("apps1/myapp/dev/db")).containsEntry("user", "v1-user").containsEntry("password", "v1-dev-pass")

        // a move between environments is a move between their paths
        val entries = client(connection).listSecrets(dev).filter { it.key == "LOG_LEVEL" }
        assertThat(service.moveSecrets(connection, dev, entries, dev.copy(environment = "prod"), overwrite = false).moved).containsExactly("LOG_LEVEL")
        assertThat(mock.secrets.getValue("root").getValue("apps/myapp/prod")).containsEntry("LOG_LEVEL", "debug")
        assertThat(mock.secrets.getValue("root").getValue("apps/myapp/dev")).doesNotContainKey("LOG_LEVEL")

        // a new environment writes nothing, is shown until it holds a secret, and its first secret creates its path
        val writes = mock.writes.size
        assertThat(service.createEnvironment(connection, "apps/myapp/{env}", "Staging", "staging").slug).isEqualTo("staging")
        assertThat(mock.writes.size).isEqualTo(writes)
        assertThat(service.projects(connection).first { it.id == "apps/myapp/{env}" }.environments.map { it.slug }).containsExactly("dev", "prod", "staging")
        assertThatThrownBy { service.createEnvironment(connection, "apps/myapp/{env}", "Prod", "prod") }.hasMessageContaining("already has an environment prod")
        assertThatThrownBy { service.createEnvironment(connection, "apps/myapp/{env}", "x", "a/b") }.hasMessageContaining("one path segment")
        service.createSecret(connection, SecretScope("apps/myapp/{env}", "staging", "/db"), "password", SecretValue("staging-pass"))
        assertThat(mock.secrets.getValue("root").getValue("apps/myapp/staging/db")).containsEntry("password", "staging-pass")
        assertThat(service.projects(connection, fresh = true).first { it.id == "apps/myapp/{env}" }.environments.map { it.slug }).containsExactly("dev", "prod", "staging")
        // without a layout, environments are namespaces, which an administrator creates
        assertThatThrownBy { service.createEnvironment(appRole(), "secret", "x", "x") }.hasMessageContaining("environment layout")
    }

    fun `test renames and moves update short references through the layout and full ones through the path`() {
        seedLayout()
        val connection = layoutConnection()
        bind(connection, "apps/myapp/{env}", "dev")
        val env = myFixture.addFileToProject(
            ".env",
            "A=vault://./db#password\nB=vault://./db#password?env=dev\nC=vault://apps/myapp/dev/db#password\nD=vault://apps/myapp/prod/db#password\n" +
                "E=vault://./db#user\n",
        ).virtualFile
        val runManager = com.intellij.execution.RunManager.getInstance(project)
        val app = runManager.createConfiguration("App", com.intellij.execution.application.ApplicationConfigurationType.getInstance().configurationFactories[0])
        // reads prod: its short reference isn't to the renamed field
        (app.configuration as com.intellij.execution.application.ApplicationConfiguration).envs =
            linkedMapOf("DB" to "vault://./db#password", "ENVBRIDGE_ENVIRONMENT" to "prod")
        runManager.addConfiguration(app)
        try {
            val updater = cz.xprees.secrets.ui.secrets.ReferenceUpdater(project)
            val dev = SecretScope("apps/myapp/{env}", "dev", "/db")
            val cautious = updater.planRename(connection, dev, "password", "pass")
            // B (environment fixed) and C (full path) are rewritten; A follows the selection and is left unless asked
            assertThat(cautious.references).isEqualTo(2)
            assertThat(cautious.following).isEqualTo(1)
            updater.apply(cautious)
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(env)!!
            assertThat(document.text).isEqualTo(
                "A=vault://./db#password\nB=vault://./db#pass?env=dev\nC=vault://apps/myapp/dev/db#pass\nD=vault://apps/myapp/prod/db#password\n" +
                    "E=vault://./db#user\n",
            )
            assertThat(updater.planRename(connection, dev, "password", "pass", includeFollowing = true).references).isEqualTo(1)

            // a move to another environment: the full reference keeps naming its full path, the short one names the environment
            val moved = updater.plan(connection, listOf("user"), dev, dev.copy(environment = "prod", path = "/db2"), includeFollowing = true)
            updater.apply(moved)
            assertThat(document.text).contains("E=vault://./db2#user?env=prod")
            // to another project: the full path
            val elsewhere = updater.plan(connection, listOf("pass"), dev, SecretScope("apps/web/{env}", "staging", "/api"))
            updater.apply(elsewhere)
            assertThat(document.text).contains("B=vault://apps/web/staging/api#pass\n").contains("C=vault://apps/web/staging/api#pass\n")
            assertThat((app.configuration as com.intellij.execution.application.ApplicationConfiguration).envs).containsEntry("DB", "vault://./db#password")
        } finally {
            runManager.removeConfiguration(app)
        }
    }

    private object TokenSetAccess {
        fun token(connection: SecretConnection): String =
            cz.xprees.secrets.core.auth.TokenSet.fromJson(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)!!)!!.accessToken
    }
}
