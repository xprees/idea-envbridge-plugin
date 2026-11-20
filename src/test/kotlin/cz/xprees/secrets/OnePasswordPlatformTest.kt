package cz.xprees.secrets

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.OnePasswordFixtures.DATABASE
import cz.xprees.secrets.OnePasswordFixtures.DEV
import cz.xprees.secrets.OnePasswordFixtures.DUP1
import cz.xprees.secrets.OnePasswordFixtures.DUP2
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.onepassword.OnePasswordAuthMethods
import cz.xprees.secrets.onepassword.OnePasswordCli
import cz.xprees.secrets.onepassword.OnePasswordClient
import cz.xprees.secrets.onepassword.OnePasswordProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert

class OnePasswordPlatformTest : BasePlatformTestCase() {
    private var op: FakeOp? = null
    private lateinit var connect: MockOnePasswordConnect
    private val connections = mutableListOf<SecretConnection>()

    private val fake: FakeOp get() = op ?: FakeOp().also { op = it }

    override fun setUp() {
        super.setUp()
        connect = MockOnePasswordConnect()
    }

    override fun tearDown() {
        try {
            val secretKeys = listOf(OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN, OnePasswordAuthMethods.OPTION_CONNECT_TOKEN)
            connections.forEach { CredentialVault.removeAll(it.id, secretKeys) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update {
                connectionId = null; projectId = null; environment = null; path = null; restrictToBoundProject = true
            }
            SecretsService.getInstance().invalidate()
            op?.close()
            connect.close()
        } finally {
            super.tearDown()
        }
    }

    /** The fake `op` is a POSIX shell script (CI runs on Linux). */
    private val canRunFakeOp: Boolean get() = !SystemInfo.isWindows

    private fun connection(method: String, options: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap()): SecretConnection {
        val connection = SecretConnection(
            id = "op-${System.nanoTime()}",
            providerId = OnePasswordProvider.ID,
            name = "1Password ${connections.size}",
            baseUrl = if (method == OnePasswordAuthMethods.CONNECT) connect.baseUrl else "http://localhost:8080",
            authMethodId = method,
            options = options,
        )
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun desktop(options: Map<String, String> = emptyMap()) =
        connection(OnePasswordAuthMethods.DESKTOP_APP, mapOf(OnePasswordAuthMethods.OPTION_OP_PATH to fake.executable.toString()) + options)

    private fun serviceAccount(token: String? = "ops_token") = connection(
        OnePasswordAuthMethods.SERVICE_ACCOUNT,
        mapOf(OnePasswordAuthMethods.OPTION_OP_PATH to fake.executable.toString()),
        token?.let { mapOf(OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN to it) }.orEmpty(),
    )

    private fun connectServer(token: String = connect.token) =
        connection(OnePasswordAuthMethods.CONNECT, secrets = mapOf(OnePasswordAuthMethods.OPTION_CONNECT_TOKEN to token))

    private fun client(connection: SecretConnection) = SecretsService.getInstance().client(connection)

    private object Interaction : SignInInteraction {
        override fun openBrowser(url: String) = Unit
        override fun isCancelled(): Boolean = false
    }

    private fun kindOf(call: ThrowableAssert.ThrowingCallable): Kind? =
        (runCatching { call.call() }.exceptionOrNull() as? SecretProviderException)?.kind

    private fun fields(connection: SecretConnection, path: String, vault: String = DEV) =
        client(connection).listSecrets(SecretScope(vault, "default", path)).map { it.key to it.value.reveal() }

    fun `test provider is registered with its grammar, one environment and no writes`() {
        val provider = SecretProvider.byScheme("op")
        assertThat(provider).isInstanceOf(OnePasswordProvider::class.java)
        assertThat(provider!!.supportsWrites).isFalse()
        assertThat(provider.defaultEnvironment(connectServer())).isEqualTo("default")
        assertThat(SecretReference.parse("op://Dev/Database/Extra/api key", listOf("op"))!!.path).isEqualTo("/Database/Extra")
        assertThat(provider.authMethods.filter { !it.usesBaseUrl }.map { it.id })
            .containsExactly(OnePasswordAuthMethods.DESKTOP_APP, OnePasswordAuthMethods.SERVICE_ACCOUNT)
    }

    fun `test desktop app lists vaults, items and fields through the CLI`() {
        if (!canRunFakeOp) return
        val connection = desktop(mapOf(OnePasswordAuthMethods.OPTION_ACCOUNT to "my.1password.com"))
        val client = client(connection)
        assertThat(client.hasCredentials()).isFalse()
        assertThat(client.signIn(Interaction)).isEqualTo("alice@example.com (my.1password.com)")
        assertThat(client.hasCredentials()).isTrue()

        val projects = client.listProjects()
        assertThat(projects.map { it.name }).containsExactly("Big", "Dev", "Shared")
        val dev = projects.single { it.name == "Dev" }
        assertThat(dev.id).isEqualTo(DEV)
        assertThat(dev.environments.map { it.slug }).containsExactly("default")

        // items are folders; items sharing a title are named by their IDs
        val folders = client.listFolders(SecretScope(DEV, "default"))
        assertThat(folders.map { it.path }).containsExactlyInAnyOrder("/Database", "/Stripe", "/$DUP1", "/$DUP2")
        assertThat(folders.map { it.name }).contains("Dup ($DUP1)", "Database")
        assertThat(client.listFolders(SecretScope(DEV, "default", "/Database"))).isEmpty()

        // top-level fields first (a section without a label counts as top level), empty ones left out
        val database = client.listSecrets(SecretScope(DEV, "default", "/Database"))
        assertThat(database.map { it.key to it.value.reveal() })
            .containsExactly("username" to "app", "password" to "db-pass", "port" to "5432", "api key" to "extra-key")
        assertThat(database.single { it.key == "api key" }.path).isEqualTo("/Database/Extra")
        assertThat(database.single { it.key == "password" }.path).isEqualTo("/Database")
        // a section by label or ID
        assertThat(fields(connection, "/Database/Extra")).containsExactly("api key" to "extra-key")
        assertThat(fields(connection, "/Database/s1")).containsExactly("api key" to "extra-key")
        assertThat(fields(connection, "/$DUP2")).containsExactly("password" to "dup-2")
        assertThat(fields(connection, "/", vault = "Dev")).isEmpty()

        val calls = fake.calls()
        assertThat(calls).contains("vault list --format json --account my.1password.com")
        assertThat(calls).anyMatch { it.startsWith("item get Database --vault $DEV --reveal --format json") }
        // no service account token for the app method, even if the IDE had inherited one
        assertThat(fake.tokens()).containsOnly("<unset>")

        client.signOut()
        assertThat(client(connection).hasCredentials()).isFalse()
    }

    fun `test whole-vault imports read every item, within a cap`() {
        if (!canRunFakeOp) return
        val connection = desktop()
        val entries = client(connection).listSecrets(SecretScope("Dev", "default", "/", recursive = true))
        assertThat(entries.map { "${it.path}#${it.key}" }).containsExactlyInAnyOrder(
            "/Database#username", "/Database#password", "/Database#port", "/Database/Extra#api key",
            "/Stripe#credential", "/Stripe/Webhooks#secret", "/$DUP1#password", "/$DUP2#password",
        )
        // items are read by ID, one CLI call each
        assertThat(fake.calls().count { it.startsWith("item get") }).isEqualTo(4)

        assertThatThrownBy { client(connection).listSecrets(SecretScope("Big", "default", "/", recursive = true)) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("more than ${OnePasswordClient.MAX_RECURSIVE_ITEMS} items")
        assertThat(fake.calls().count { it.contains("--vault Big") && it.startsWith("item get") }).isZero()
    }

    fun `test service account token goes only into the CLI's environment`() {
        if (!canRunFakeOp) return
        fake.requiredToken = "ops_secret_token"
        fake.whoami("""{"url":"https://acme.1password.com","user_uuid":"S1","account_uuid":"A1","user_type":"SERVICE_ACCOUNT"}""")
        val connection = serviceAccount("ops_secret_token")
        val client = client(connection)
        assertThat(client.hasCredentials()).isTrue()
        assertThat(client.signIn(Interaction)).isEqualTo("Service account (acme.1password.com)")
        assertThat(fields(connection, "/Infra", vault = "Shared")).containsExactly("password" to "shared-pass")
        assertThat(fake.tokens()).isNotEmpty.containsOnly("ops_secret_token")
        assertThat(fake.calls()).isNotEmpty.noneMatch { it.contains("ops_secret_token") }

        // a wrong token is "sign in again", a missing one a configuration problem
        assertThat(kindOf { client(serviceAccount("ops_rotated")).listProjects() }).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(kindOf { client(serviceAccount(token = null)).listProjects() }).isEqualTo(Kind.CONFIGURATION)

        // signing out forgets the stored token
        client.signOut()
        assertThat(client(connection).hasCredentials()).isFalse()
    }

    fun `test CLI errors map to the right kinds without leaking values`() {
        if (!canRunFakeOp) return
        val missing = connection(OnePasswordAuthMethods.DESKTOP_APP, mapOf(OnePasswordAuthMethods.OPTION_OP_PATH to fake.dir.resolve("nope").toString()))
        assertThatThrownBy { client(missing).listProjects() }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("not found").hasMessageContaining("Install")
        assertThat(kindOf { client(missing).listProjects() }).isEqualTo(Kind.CONFIGURATION)

        val connection = desktop()
        assertThat(kindOf { client(connection).listSecrets(SecretScope(DEV, "default", "/Nope")) }).isEqualTo(Kind.NOT_FOUND)
        assertThat(kindOf { client(connection).listSecrets(SecretScope(DEV, "default", "/Database/Nope")) }).isEqualTo(Kind.NOT_FOUND)
        assertThatThrownBy { client(connection).listSecrets(SecretScope(DEV, "default", "/Dup")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("More than one item").hasMessageNotContaining("[ERROR]")

        fake.signedOut = true
        assertThatThrownBy { client(connection).listProjects() }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("not currently signed in")
        assertThat(kindOf { client(connection).signIn(Interaction) }).isEqualTo(Kind.AUTH_REQUIRED)

        assertThat(OnePasswordCli.failure("[ERROR] 2026/11/20 10:00:00 authorization prompt dismissed, please try again", "vault list").kind)
            .isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(OnePasswordCli.failure("[ERROR] 2026/11/20 10:00:00 \"Prod\" isn't a vault in this account", "item list").kind)
            .isEqualTo(Kind.NOT_FOUND)
        assertThat(OnePasswordCli.failure("x".repeat(5000), "item get").message!!.length).isLessThan(400)
    }

    fun `test Connect lists vaults, items and fields with the bearer token`() {
        val connection = connectServer()
        val client = client(connection)
        assertThat(client.hasCredentials()).isTrue()
        assertThat(client.signIn(Interaction)).startsWith("1Password Connect (127.0.0.1:")
        assertThat(client.listProjects().map { it.name }).containsExactly("Big", "Dev", "Forbidden", "Shared")
        assertThat(client.listFolders(SecretScope(DEV, "default")).map { it.path }).contains("/Database", "/$DUP1")

        // Connect gives only section IDs on fields: labels come from the item's sections
        assertThat(fields(connection, "/Database")).containsExactly("username" to "app", "password" to "db-pass", "port" to "5432", "api key" to "extra-key")
        assertThat(fields(connection, "/Database/Extra")).containsExactly("api key" to "extra-key")
        // vaults and items by name, as references write them
        assertThat(fields(connection, "/Stripe", vault = "Dev")).containsExactly("credential" to "sk_test_123", "secret" to "whsec_1")
        assertThat(fields(connection, "/$DATABASE", vault = "dev")).hasSize(4)
        assertThat(connect.requests).isNotEmpty.allMatch { it.authorization == "Bearer ${connect.token}" }
        assertThat(connect.requests).noneMatch { it.path.contains(connect.token) }

        assertThat(kindOf { client.listSecrets(SecretScope("Dev", "default", "/Dup")) }).isEqualTo(Kind.CONFIGURATION)
        assertThat(kindOf { client.listSecrets(SecretScope("Dev", "default", "/Nope")) }).isEqualTo(Kind.NOT_FOUND)
        assertThat(kindOf { client.listFolders(SecretScope(OnePasswordFixtures.FORBIDDEN, "default")) }).isEqualTo(Kind.FORBIDDEN)
        assertThat(kindOf { client(connectServer("wrong")).listProjects() }).isEqualTo(Kind.AUTH_REQUIRED)
        val recursive = client.listSecrets(SecretScope("Dev", "default", "/", recursive = true))
        assertThat(recursive).hasSize(8)
    }

    fun `test launches resolve op references against the bound vault`() {
        if (!canRunFakeOp) return
        val connection = desktop()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = DEV
            environment = null
            path = null
            useProjectFile = false
        }
        assertThat(SecretBindingService.getInstance(project).binding(OnePasswordProvider()).environment).isEqualTo("default")

        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(
                linkedMapOf(
                    // the vault by name passes "only the bound project": it is the bound vault
                    "DB_PASSWORD" to "op://Dev/Database/password",
                    "API_KEY" to "op://$DEV/Database/Extra/api key",
                    "API_KEY_NO_SECTION" to "op://Dev/Database/api key",
                    "STRIPE" to "op://Dev/Stripe/*",
                    "PLAIN" to "x",
                ),
            ),
        )
        assertThat(result.environment)
            .containsEntry("DB_PASSWORD", "db-pass")
            .containsEntry("API_KEY", "extra-key")
            .containsEntry("API_KEY_NO_SECTION", "extra-key")
            .containsEntry("credential", "sk_test_123")
            .containsEntry("secret", "whsec_1")
            .containsEntry("PLAIN", "x")
            .doesNotContainKey("STRIPE")

        // fail closed on a missing field
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "op://Dev/Database/missing"))) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("no secret missing")
        // another vault is refused while the project is restricted to the bound one
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "op://Shared/Infra/password"))) }
            .isInstanceOf(SecretProviderException::class.java)
        ProjectSecretsSettings.getInstance(project).update { restrictToBoundProject = false }
        assertThat(resolver.resolve(resolver.plan(mapOf("X" to "op://Shared/Infra/password"))).environment).containsEntry("X", "shared-pass")
    }

    fun `test writes are refused for 1Password connections`() {
        val connection = connectServer()
        assertThat(SecretsService.getInstance().writesAllowed(connection)).isFalse()
        assertThatThrownBy { SecretsService.getInstance().createSecret(connection, SecretScope(DEV, "default", "/Database"), "K", SecretValue("v")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("read-only")
        assertThat(connect.requests).isEmpty()
    }
}
