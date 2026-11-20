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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.onepassword.OnePasswordConnect
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
            SecretsAppSettings.getInstance().state.readOnly = false
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

    fun `test provider is registered with its grammar, one environment and writes`() {
        val provider = SecretProvider.byScheme("op")
        assertThat(provider).isInstanceOf(OnePasswordProvider::class.java)
        assertThat(provider!!.supportsWrites).isTrue()
        // a secret created at a vault's root is a new password item
        assertThat(provider.createdSecretLocation(SecretScope(DEV, "default", "/"), "API_KEY")).isEqualTo("/API_KEY" to "password")
        assertThat(provider.createdSecretLocation(SecretScope(DEV, "default", "/Database/Extra"), "K")).isEqualTo("/Database/Extra" to "K")
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

    // ---- Writes ----

    private val service: SecretsService get() = SecretsService.getInstance()

    private fun scope(path: String, vault: String = DEV) = SecretScope(vault, "default", path)

    private fun fieldOf(item: JsonObject, label: String): JsonObject? =
        item.getAsJsonArray("fields").map { it.asJsonObject }.firstOrNull { it.get("label")?.asString == label }

    private fun notesOf(item: JsonObject): String? =
        item.getAsJsonArray("fields").map { it.asJsonObject }.firstOrNull { it.get("purpose")?.asString == "NOTES" }?.get("value")?.asString

    /** Every value written must reach `op` only on stdin. */
    private fun assertNotInArgv(vararg values: String) {
        val calls = fake.calls()
        assertThat(calls).isNotEmpty
        values.forEach { value -> assertThat(calls).noneMatch { it.contains(value) } }
    }

    fun `test read-only setting blocks every write before anything is sent`() {
        val connection = connectServer()
        SecretsAppSettings.getInstance().state.readOnly = true
        assertThat(service.writesAllowed(connection)).isFalse()
        val calls = listOf<ThrowableAssert.ThrowingCallable>(
            ThrowableAssert.ThrowingCallable { service.createSecret(connection, scope("/Database"), "K", SecretValue("v")) },
            ThrowableAssert.ThrowingCallable { service.createSecret(connection, scope("/"), "K", SecretValue("v")) },
            ThrowableAssert.ThrowingCallable { service.updateSecret(connection, scope("/Database"), "password", SecretValue("v")) },
            ThrowableAssert.ThrowingCallable { service.deleteSecrets(connection, scope("/Database"), listOf("password")) },
            ThrowableAssert.ThrowingCallable {
                service.moveSecrets(connection, scope("/Database"), service.secrets(connection, scope("/Database")), scope("/Stripe"), false)
            },
        )
        connect.requests.clear()
        calls.forEach { call ->
            assertThatThrownBy(call).isInstanceOf(SecretProviderException::class.java).hasMessageContaining("Read-only")
        }
        assertThat(connect.requests).noneMatch { it.method != "GET" }
        assertThat(connect.requests.filter { it.method == "GET" }.map { it.path }).allMatch { it.endsWith("/items/$DATABASE") || it.endsWith("/items") || it == "/v1/vaults" }

        if (!canRunFakeOp) return
        val cli = desktop()
        assertThatThrownBy { service.createSecret(cli, scope("/Database"), "K", SecretValue("v")) }.hasMessageContaining("Read-only")
        assertThat(fake.calls()).isEmpty()
        SecretsAppSettings.getInstance().state.readOnly = false
        assertThat(service.writesAllowed(cli)).isTrue()
    }

    fun `test CLI creates fields with values only on stdin`() {
        if (!canRunFakeOp) return
        val connection = desktop()
        // top level, then into a section by label, with a comment added to the item's notes
        assertThat(service.createSecret(connection, scope("/Database"), "API_TOKEN", SecretValue("tok-1-secret"))).isEqualTo(WriteOutcome.APPLIED)
        service.createSecret(connection, scope("/Database/Extra"), "SIGNING_KEY", SecretValue("sig-2-secret"), comment = "rotate yearly")
        val item = fake.item(DEV, DATABASE)
        val token = fieldOf(item, "API_TOKEN")!!
        assertThat(token.get("type").asString).isEqualTo("CONCEALED")
        assertThat(token.get("section")).isNull()
        assertThat(OnePasswordConnect.looksLikeId(token.get("id").asString)).isTrue()
        assertThat(fieldOf(item, "SIGNING_KEY")!!.getAsJsonObject("section").get("id").asString).isEqualTo("s1")
        assertThat(notesOf(item)).isEqualTo("SIGNING_KEY: rotate yearly")
        // what was there is kept
        assertThat(fieldOf(item, "password")!!.get("value").asString).isEqualTo("db-pass")

        assertThat(fields(connection, "/Database")).contains("API_TOKEN" to "tok-1-secret", "SIGNING_KEY" to "sig-2-secret", "password" to "db-pass")
        assertThat(client(connection).listSecrets(scope("/Database")).single { it.key == "SIGNING_KEY" }.let { it.path to it.comment })
            .isEqualTo("/Database/Extra" to "SIGNING_KEY: rotate yearly")
        assertThat(fake.calls()).anyMatch { it.startsWith("item edit $DATABASE --vault $DEV --format json") }
        assertNotInArgv("tok-1-secret", "sig-2-secret", "rotate yearly", "db-pass")

        // a field with that label anywhere in the item is refused, and nothing is written
        val edits = fake.calls().count { it.startsWith("item edit") }
        assertThatThrownBy { service.createSecret(connection, scope("/Database"), "api key", SecretValue("x")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("already has a field \"api key\"")
        assertThat(kindOf { service.createSecret(connection, scope("/Database"), "API_TOKEN", SecretValue("x")) }).isEqualTo(Kind.CONFIGURATION)
        assertThat(kindOf { service.createSecret(connection, scope("/Database/Nope"), "X", SecretValue("x")) }).isEqualTo(Kind.NOT_FOUND)
        assertThat(kindOf { service.createSecret(connection, scope("/Nope"), "X", SecretValue("x")) }).isEqualTo(Kind.NOT_FOUND)
        assertThat(fake.calls().count { it.startsWith("item edit") }).isEqualTo(edits)
    }

    fun `test CLI creates a password item at the vault root`() {
        if (!canRunFakeOp) return
        val connection = serviceAccount()
        service.createSecret(connection, scope("/"), "GITHUB_TOKEN", SecretValue("ghp_rootsecret"), comment = "CI token")
        val template = fake.created().single()
        assertThat(template.get("title").asString).isEqualTo("GITHUB_TOKEN")
        assertThat(template.get("category").asString).isEqualTo("PASSWORD")
        assertThat(fieldOf(template, "password")!!.let { it.get("value").asString to it.get("purpose").asString }).isEqualTo("ghp_rootsecret" to "PASSWORD")
        assertThat(notesOf(template)).isEqualTo("CI token")
        assertThat(fake.calls()).anyMatch { it.startsWith("item create --vault $DEV --format json") }
        assertNotInArgv("ghp_rootsecret", "CI token")
        assertThat(fake.tokens()).containsOnly("ops_token")
        // where the New Secret dialog then points: the item's password field
        assertThat(fields(connection, "/GITHUB_TOKEN")).contains("password" to "ghp_rootsecret")

        // an existing title is refused (choose the item as folder instead)
        assertThatThrownBy { service.createSecret(connection, scope("/"), "stripe", SecretValue("x")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("already has an item called \"stripe\"")
        assertThat(fake.created()).hasSize(1)
    }

    fun `test CLI updates values and notes, and deletes fields but never items`() {
        if (!canRunFakeOp) return
        val connection = desktop()
        service.updateSecret(connection, scope("/Database/Extra"), "api key", SecretValue("new-extra-key"))
        service.updateSecret(connection, scope("/Database"), "password", SecretValue("new-db-pass"), comment = "Owned by the platform team")
        var item = fake.item(DEV, DATABASE)
        assertThat(fieldOf(item, "api key")!!.get("value").asString).isEqualTo("new-extra-key")
        assertThat(fieldOf(item, "password")!!.get("value").asString).isEqualTo("new-db-pass")
        assertThat(notesOf(item)).isEqualTo("Owned by the platform team")
        assertThat(fields(connection, "/Database")).contains("password" to "new-db-pass", "api key" to "new-extra-key")
        assertNotInArgv("new-extra-key", "new-db-pass", "Owned by")
        assertThat(kindOf { service.updateSecret(connection, scope("/Database"), "missing", SecretValue("x")) }).isEqualTo(Kind.NOT_FOUND)
        assertThat(kindOf { service.updateSecret(connection, scope("/"), "Database", SecretValue("x")) }).isEqualTo(Kind.CONFIGURATION)

        // a custom field is removed; a built-in one (the password) is emptied, so it is no longer listed
        val results = service.deleteSecrets(connection, scope("/Database"), listOf("port", "password"))
        assertThat(results.values).allMatch { it.getOrNull() == WriteOutcome.APPLIED }
        item = fake.item(DEV, DATABASE)
        assertThat(fieldOf(item, "port")).isNull()
        assertThat(fieldOf(item, "password")!!.get("value").asString).isEmpty()
        assertThat(fields(connection, "/Database").map { it.first }).containsExactly("notesPlain", "username", "api key")

        // at the root there are only items, which EnvBridge doesn't delete
        assertThatThrownBy { service.deleteSecrets(connection, scope("/"), listOf("Stripe")).values.single().getOrThrow() }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("doesn't delete 1Password items")
        assertThat(fake.calls()).noneMatch { it.startsWith("item delete") }
    }

    fun `test CLI moves fields between items and reports a source it could not change`() {
        if (!canRunFakeOp) return
        val connection = desktop()
        val database = client(connection).listSecrets(scope("/Database"))
        val result = service.moveSecrets(connection, scope("/Database"), database.filter { it.key == "port" }, scope("/Stripe/Webhooks"), false)
        assertThat(result.moved).containsExactly("port")
        assertThat(result.notRemoved).isEmpty()
        assertThat(fieldOf(fake.item(DEV, OnePasswordFixtures.STRIPE), "port")!!.let { it.get("value").asString to it.getAsJsonObject("section").get("id").asString })
            .isEqualTo("5432" to "s2")
        assertThat(fieldOf(fake.item(DEV, DATABASE), "port")).isNull()

        // the source can't be changed: the copy stays, the original too, and the result says so
        fake.refuseEditOf = DATABASE
        val partial = service.moveSecrets(connection, scope("/Database/Extra"), database.filter { it.key == "api key" }, scope("/Stripe"), false)
        assertThat(partial.moved).isEmpty()
        assertThat(partial.notRemoved).containsOnlyKeys("api key")
        assertThat(partial.notRemoved["api key"]).contains("permission")
        assertThat(fieldOf(fake.item(DEV, OnePasswordFixtures.STRIPE), "api key")!!.get("value").asString).isEqualTo("extra-key")
        assertThat(fieldOf(fake.item(DEV, DATABASE), "api key")).isNotNull()
        assertNotInArgv("5432", "extra-key")
    }

    fun `test CLI that ignores the piped item is not taken for success`() {
        if (!canRunFakeOp) return
        val connection = desktop()
        fake.ignoreEdits = true
        assertThatThrownBy { service.createSecret(connection, scope("/Database"), "NEW_KEY", SecretValue("v")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("didn't apply the change")
        // masked values (an op that didn't reveal them) are never written back
        val masked = JsonParser.parseString("""{"id":"x","fields":[{"id":"password","value":"[use 'op item get x --reveal' to reveal]"}]}""").asJsonObject
        assertThat(kindOf { OnePasswordCli.refuseMaskedValues(masked) }).isEqualTo(Kind.SERVER)
    }

    fun `test Connect creates, updates and deletes with the item's version`() {
        val connection = connectServer()
        service.createSecret(connection, scope("/Database/Extra"), "API_TOKEN", SecretValue("tok-connect"))
        var item = connect.item(DEV, DATABASE)!!
        assertThat(item.get("version").asInt).isEqualTo(2)
        assertThat(fieldOf(item, "API_TOKEN")!!.getAsJsonObject("section").get("id").asString).isEqualTo("s1")
        val put = connect.requests.single { it.method == "PUT" }
        assertThat(put.path).isEqualTo("/v1/vaults/$DEV/items/$DATABASE")
        assertThat(JsonParser.parseString(put.body).asJsonObject.get("version").asInt).isEqualTo(1)
        assertThat(fields(connection, "/Database/Extra")).containsExactly("api key" to "extra-key", "API_TOKEN" to "tok-connect")

        service.updateSecret(connection, scope("/Database"), "api key", SecretValue("rotated"), comment = "notes here")
        item = connect.item(DEV, DATABASE)!!
        assertThat(fieldOf(item, "api key")!!.get("value").asString).isEqualTo("rotated")
        assertThat(notesOf(item)).isEqualTo("notes here")
        assertThat(item.get("version").asInt).isEqualTo(3)
        assertThat(kindOf { service.createSecret(connection, scope("/Database"), "API_TOKEN", SecretValue("x")) }).isEqualTo(Kind.CONFIGURATION)

        service.deleteSecrets(connection, scope("/Database/Extra"), listOf("API_TOKEN")).values.single().getOrThrow()
        assertThat(fieldOf(connect.item(DEV, DATABASE)!!, "API_TOKEN")).isNull()
        // items are only ever saved, never deleted
        assertThat(connect.requests.map { it.method }.toSet()).containsOnly("GET", "PUT")
        assertThat(connect.requests).allMatch { it.authorization == "Bearer ${connect.token}" }
    }

    fun `test Connect creates a password item at the root and refuses a taken title`() {
        val connection = connectServer()
        service.createSecret(connection, scope("/", vault = "Dev"), "NEW_API_KEY", SecretValue("root-value"), comment = "from the IDE")
        val post = connect.requests.single { it.method == "POST" }
        assertThat(post.path).isEqualTo("/v1/vaults/$DEV/items")
        val item = connect.itemByTitle(DEV, "NEW_API_KEY")!!
        assertThat(item.get("category").asString).isEqualTo("PASSWORD")
        assertThat(fieldOf(item, "password")!!.get("value").asString).isEqualTo("root-value")
        assertThat(notesOf(item)).isEqualTo("from the IDE")
        assertThat(fields(connection, "/NEW_API_KEY")).containsExactly("password" to "root-value", "notesPlain" to "from the IDE")
        assertThat(client(connection).listFolders(scope("/")).map { it.path }).contains("/NEW_API_KEY")

        assertThat(kindOf { service.createSecret(connection, scope("/"), "Database", SecretValue("x")) }).isEqualTo(Kind.CONFIGURATION)
        assertThat(connect.requests.count { it.method == "POST" }).isEqualTo(1)
    }

    fun `test Connect reports an item changed meanwhile as a conflict`() {
        val connection = connectServer()
        connect.beforePut = { id -> connect.touch(DEV, id) }
        assertThatThrownBy { service.updateSecret(connection, scope("/Database"), "password", SecretValue("lost")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("changed meanwhile").hasMessageContaining("refresh")
        assertThat(kindOf { service.updateSecret(connection, scope("/Database"), "password", SecretValue("lost")) }).isEqualTo(Kind.CONFIGURATION)
        assertThat(fieldOf(connect.item(DEV, DATABASE)!!, "password")!!.get("value").asString).isEqualTo("db-pass")
        connect.beforePut = null
        service.updateSecret(connection, scope("/Database"), "password", SecretValue("saved"))
        assertThat(fieldOf(connect.item(DEV, DATABASE)!!, "password")!!.get("value").asString).isEqualTo("saved")
    }

    fun `test Connect moves fields, refuses to overwrite unless asked, and keeps the original when its item can't be saved`() {
        val connection = connectServer()
        val database = client(connection).listSecrets(scope("/Database"))

        // Stripe has no "username": moved; within one item a field just changes section
        assertThat(service.moveSecrets(connection, scope("/Database"), database.filter { it.key == "username" }, scope("/Stripe"), false).moved)
            .containsExactly("username")
        assertThat(fieldOf(connect.item(DEV, OnePasswordFixtures.STRIPE)!!, "username")!!.get("value").asString).isEqualTo("app")
        // a built-in field is emptied at the source, not removed
        assertThat(fieldOf(connect.item(DEV, DATABASE)!!, "username")!!.get("value").asString).isEmpty()
        val putsBefore = connect.requests.count { it.method == "PUT" }
        assertThat(service.moveSecrets(connection, scope("/Database"), database.filter { it.key == "port" }, scope("/Database/Extra"), false).moved)
            .containsExactly("port")
        assertThat(connect.requests.count { it.method == "PUT" }).isEqualTo(putsBefore + 1)
        assertThat(fieldOf(connect.item(DEV, DATABASE)!!, "port")!!.getAsJsonObject("section").get("id").asString).isEqualTo("s1")

        // Stripe has a "password" too: nothing happens without Overwrite, its value is replaced with it
        service.createSecret(connection, scope("/Stripe"), "password", SecretValue("old-stripe"))
        val password = database.filter { it.key == "password" }
        val puts = connect.requests.count { it.method == "PUT" }
        assertThatThrownBy { service.moveSecrets(connection, scope("/Database"), password, scope("/Stripe"), false) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("already has \"password\"").hasMessageContaining("nothing was moved")
        assertThat(connect.requests.count { it.method == "PUT" }).isEqualTo(puts)
        assertThat(service.moveSecrets(connection, scope("/Database"), password, scope("/Stripe"), true).moved).containsExactly("password")
        assertThat(fields(connection, "/Stripe")).contains("password" to "db-pass").doesNotContain("password" to "old-stripe")
        assertThat(fields(connection, "/Database").map { it.first }).doesNotContain("password")

        // the source item can't be saved: the copy stays at the destination and the result names the key
        connect.refusePutOf = setOf(DATABASE)
        val partial = service.moveSecrets(connection, scope("/Database/Extra"), client(connection).listSecrets(scope("/Database/Extra")).filter { it.key == "api key" }, scope("/Stripe/Webhooks"), false)
        assertThat(partial.moved).isEmpty()
        assertThat(partial.notRemoved).containsOnlyKeys("api key")
        assertThat(fieldOf(connect.item(DEV, OnePasswordFixtures.STRIPE)!!, "api key")!!.get("value").asString).isEqualTo("extra-key")
        assertThat(fieldOf(connect.item(DEV, DATABASE)!!, "api key")).isNotNull()

        // the vault's root isn't a destination
        assertThat(kindOf { service.moveSecrets(connection, scope("/Stripe"), database.take(1), scope("/"), false) }).isEqualTo(Kind.CONFIGURATION)
    }
}
