package cz.xprees.secrets

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.auth.TokenSet
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.doppler.DopplerAuth
import cz.xprees.secrets.doppler.DopplerAuthMethods
import cz.xprees.secrets.doppler.DopplerProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.nio.file.Path

class DopplerPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockDoppler
    private val connections = mutableListOf<SecretConnection>()
    private var projectFileDir: Path? = null

    override fun setUp() {
        super.setUp()
        mock = MockDoppler()
        DopplerAuth.pollIntervalOverrideMs = 10
    }

    override fun tearDown() {
        try {
            connections.forEach { CredentialVault.removeAll(it.id, listOf(DopplerAuthMethods.OPTION_TOKEN)) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            SecretsAppSettings.getInstance().state.readOnly = false
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null; useProjectFile = true; restrictToBoundProject = true }
            SecretsService.getInstance().invalidate()
            DopplerAuth.pollIntervalOverrideMs = null
            DopplerAuth.timeoutMs = 5 * 60_000L
            projectFileDir?.toFile()?.deleteRecursively()
            mock.close()
        } finally {
            super.tearDown()
        }
    }

    private fun connection(method: String, token: String? = null): SecretConnection {
        val connection = SecretConnection(
            id = "doppler-${System.nanoTime()}",
            providerId = DopplerProvider.ID,
            name = "Doppler ${connections.size}",
            baseUrl = mock.baseUrl,
            authMethodId = method,
        )
        token?.let { CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(DopplerAuthMethods.OPTION_TOKEN), it) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun personal() = connection(DopplerAuthMethods.TOKEN, "dp.pt.personal")

    private fun client(connection: SecretConnection) = SecretsService.getInstance().client(connection)

    private class Interaction(val cancelled: () -> Boolean = { false }, val browser: (String) -> Unit = {}) : SignInInteraction {
        val statuses = mutableListOf<String>()
        override fun openBrowser(url: String) = browser(url)
        override fun isCancelled(): Boolean = cancelled()
        override fun status(text: String) {
            statuses += text
        }
    }

    private fun kind(call: () -> Unit): Kind = try {
        call()
        error("no exception")
    } catch (e: SecretProviderException) {
        e.kind
    }

    fun `test provider is registered with its grammar and the hosted instance`() {
        val provider = SecretProvider.byScheme("doppler")
        assertThat(provider).isInstanceOf(DopplerProvider::class.java)
        assertThat(provider!!.supportsWrites).isTrue()
        assertThat(provider.instancePresets.single().second).isEqualTo("https://api.doppler.com")
        assertThat(provider.authMethods.map { it.id }).containsExactly(DopplerAuthMethods.BROWSER, DopplerAuthMethods.TOKEN)
        assertThat(SecretReference.parse("doppler://prd:DB_PASSWORD", listOf("doppler"))!!.environment).isEqualTo("prd")
        val folder = "doppler://app/KEY"
        assertThatThrownBy { SecretReference.parse(folder, listOf(provider.referenceScheme)) }.hasMessageContaining("no folders")
    }

    fun `test personal token lists projects with their configs, and secrets with computed values`() {
        val connection = personal()
        val client = client(connection)
        assertThat(client.hasCredentials()).isTrue()
        assertThat(client.signIn(Interaction())).isEqualTo("alice@example.com (Acme)")

        val projects = client.listProjects()
        assertThat(projects.map { it.id }).containsExactly("backend", "secret-project", "web")
        val backend = projects.first { it.id == "backend" }
        assertThat(backend.environments.map { it.slug }).containsExactly("dev", "dev_personal", "stg", "prd")
        assertThat(backend.environments.first { it.slug == "dev_personal" }.name).isEqualTo("dev_personal (branch of dev)")
        assertThat(backend.organizationId).isEqualTo("wp-1")
        // configs of a project the token can't see: listed without configs
        assertThat(projects.first { it.id == "secret-project" }.environments).isEmpty()

        assertThat(client.listFolders(SecretScope("backend", "dev"))).isEmpty()
        val secrets = client.listSecrets(SecretScope("backend", "dev"))
        // Doppler's own DOPPLER_* metadata is not a secret
        assertThat(secrets.map { it.key }).containsExactly("DB_HOST", "DB_PASSWORD", "DB_URL")
        assertThat(secrets.first { it.key == "DB_URL" }.value.reveal()).isEqualTo("postgres://localhost:5432")
        assertThat(secrets.first { it.key == "DB_PASSWORD" }.comment).isEqualTo("rotated monthly")
        assertThatThrownBy { client.listSecrets(SecretScope("backend", "dev", "/app")) }.hasMessageContaining("no folders")

        // the token only ever travels in the Authorization header
        val read = mock.requests.last { it.path == "/v3/configs/config/secrets" }
        assertThat(read.authorization).isEqualTo("Bearer dp.pt.personal")
        assertThat(read.query).containsEntry("project", "backend").containsEntry("config", "dev")
        assertThat(mock.requests.flatMap { it.query.values }).noneMatch { it.contains("dp.") }

        val provider = DopplerProvider()
        assertThat(provider.dashboardUrl(connection, SecretScope("backend", "dev_personal"), "wp-1"))
            .isEqualTo("${mock.baseUrl}/workplace/wp-1/projects/backend/configs/dev_personal")
        assertThat(provider.dashboardUrl(connection.copy(baseUrl = DopplerProvider.API_URL), SecretScope("backend", "prd"), "wp-1"))
            .isEqualTo("https://dashboard.doppler.com/workplace/wp-1/projects/backend/configs/prd")
        assertThat(provider.dashboardUrl(connection.copy(baseUrl = DopplerProvider.API_URL), null)).isEqualTo("https://dashboard.doppler.com")
    }

    fun `test an endpoint pasted as the connection address reaches the API`() {
        val connection = personal().let { it.copy(baseUrl = it.baseUrl + "/v3/workplace") }
        SecretsAppSettings.getInstance().setConnections(listOf(connection))
        assertThat(client(connection).listProjects().map { it.id }).contains("backend")
        assertThat(mock.requests.none { it.path.startsWith("/v3/workplace/") }).isTrue()
    }

    fun `test a service token offers just its project and config`() {
        val connection = connection(DopplerAuthMethods.TOKEN, "dp.st.backend-prd")
        val client = client(connection)
        val project = client.listProjects().single()
        assertThat(project.id).isEqualTo("backend")
        assertThat(project.environments.map { it.slug }).containsExactly("prd")
        assertThat(client.listSecrets(SecretScope("backend", "prd")).associate { it.key to it.value.reveal() })
            .containsExactly(java.util.Map.entry("API_KEY", "prd-api"), java.util.Map.entry("DB_PASSWORD", "prd-pass"))
        assertThat(mock.requests.none { it.path == "/v3/projects" }).isTrue()
        assertThat(kind { client.listSecrets(SecretScope("backend", "dev")) }).isEqualTo(Kind.FORBIDDEN)
        // read-only token or not, a write to another config is refused by Doppler
        assertThat(kind { client.createSecret(SecretScope("backend", "dev"), "NEW", SecretValue("x")) }).isEqualTo(Kind.FORBIDDEN)

        // signing out forgets the pasted token but doesn't revoke it
        client.signOut()
        assertThat(client.hasCredentials()).isFalse()
        assertThat(mock.revoked).isEmpty()
    }

    fun `test a service account token lists what it can see`() {
        val client = client(connection(DopplerAuthMethods.TOKEN, "dp.sa.robot"))
        assertThat(client.signIn(Interaction())).isEqualTo("robot (Acme)")
        assertThat(client.listProjects().map { it.id }).contains("backend", "web")
    }

    fun `test browser sign-in shows the code, polls until approved, stores and revokes the token`() {
        val connection = connection(DopplerAuthMethods.BROWSER)
        val client = client(connection)
        assertThat(client.hasCredentials()).isFalse()
        assertThat(kind { client.listProjects() }).isEqualTo(Kind.AUTH_REQUIRED)

        var opened: String? = null
        val interaction = Interaction(browser = { opened = it })
        assertThat(client.signIn(interaction)).isEqualTo("alice@example.com (Acme)")
        assertThat(opened).startsWith(mock.baseUrl + "/workplace/auth/cli?code=")
        // the user compares the code in the IDE with the one in the browser
        assertThat(interaction.statuses).anyMatch { it.contains(mock.lastCode!!) }
        assertThat(mock.polls.get()).isEqualTo(3)
        val generate = mock.requests.single { it.path == "/v3/auth/cli/generate/2" }
        assertThat(generate.method).isEqualTo("GET")
        assertThat(generate.query.getValue("hostname")).startsWith("EnvBridge")
        assertThat(generate.authorization).isNull()
        // the polling code goes in the body only
        assertThat(mock.requests.filter { it.path == "/v3/auth/cli/authorize" }).allMatch { it.query.isEmpty() && it.body.contains("polling-secret-") }

        val session = TokenSet.fromJson(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)!!)!!
        val token = session.accessToken
        assertThat(token).startsWith("dp.ct.cli-")
        assertThat(session.toString()).doesNotContain(token)
        assertThat(client.hasCredentials()).isTrue()
        assertThat(client.listSecrets(SecretScope("web", "dev")).single().key).isEqualTo("PUBLIC_URL")
        assertThat(mock.requests.last().authorization).isEqualTo("Bearer $token")

        // errors never carry the token
        val failure = runCatching { client.listSecrets(SecretScope("backend", "missing")) }.exceptionOrNull() as SecretProviderException
        assertThat(failure.kind).isEqualTo(Kind.NOT_FOUND)
        assertThat(failure.message).contains("Could not find requested config").doesNotContain(token)
        assertThat(failure.toString()).doesNotContain(token)

        client.signOut()
        assertThat(mock.revoked).containsExactly(token)
        assertThat(client.hasCredentials()).isFalse()
        assertThat(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)).isNull()
    }

    fun `test browser sign-in can be declined, cancelled or time out`() {
        val connection = connection(DopplerAuthMethods.BROWSER)
        mock.declineSignIn = true
        val declined = runCatching { client(connection).signIn(Interaction()) }.exceptionOrNull() as SecretProviderException
        assertThat(declined.kind).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(declined.message).contains("declined").doesNotContain("polling-secret")

        mock.declineSignIn = false
        mock.approveAfterPolls = Int.MAX_VALUE
        var polled = 0
        assertThat(kind { client(connection).signIn(Interaction(cancelled = { ++polled > 3 })) }).isEqualTo(Kind.CANCELLED)

        DopplerAuth.timeoutMs = 100
        assertThat(kind { client(connection).signIn(Interaction()) }).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(client(connection).hasCredentials()).isFalse()
    }

    fun `test a revoked browser session asks to sign in again`() {
        val connection = connection(DopplerAuthMethods.BROWSER)
        client(connection).signIn(Interaction())
        mock.tokens.keys.removeIf { it.startsWith("dp.ct.") }
        assertThat(kind { client(connection).listSecrets(SecretScope("web", "dev")) }).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(client(connection).hasCredentials()).isFalse()
    }

    fun `test rate limits and wrong tokens map to clear errors`() {
        val client = client(personal())
        mock.rateLimitRetryAfter = 7
        val limited = runCatching { client.listSecrets(SecretScope("backend", "dev")) }.exceptionOrNull() as SecretProviderException
        assertThat(limited.kind).isEqualTo(Kind.SERVER)
        assertThat(limited.message).contains("rate limited").contains("retry in 7 s")

        val wrong = client(connection(DopplerAuthMethods.TOKEN, "dp.pt.unknown"))
        assertThat(kind { wrong.signIn(Interaction()) }).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(kind { client(connection(DopplerAuthMethods.TOKEN)).listProjects() }).isEqualTo(Kind.CONFIGURATION)
    }

    fun `test create, update with note, and delete secrets`() {
        val connection = personal()
        val service = SecretsService.getInstance()
        val dev = SecretScope("backend", "dev")
        assertThat(service.createSecret(connection, dev, "NEW_KEY", SecretValue("v1"), "first note")).isEqualTo(WriteOutcome.APPLIED)
        assertThat(mock.secrets.getValue("backend/dev")).containsEntry("NEW_KEY", "v1")
        assertThat(mock.notes.getValue("backend")).containsEntry("NEW_KEY", "first note")
        assertThat(mock.requests.last { it.path == "/v3/configs/config/secrets" && it.method == "POST" }.body)
            .contains("\"project\":\"backend\"").contains("\"config\":\"dev\"").contains("\"NEW_KEY\":\"v1\"")

        // an existing key is not created again, a missing one is not updated
        assertThatThrownBy { service.createSecret(connection, dev, "NEW_KEY", SecretValue("v2")) }.hasMessageContaining("already exists")
        assertThat(kind { service.updateSecret(connection, dev, "MISSING", SecretValue("v")) }).isEqualTo(Kind.NOT_FOUND)

        service.updateSecret(connection, dev, "NEW_KEY", SecretValue("v2"), null)
        assertThat(mock.secrets.getValue("backend/dev")).containsEntry("NEW_KEY", "v2")
        assertThat(mock.notes.getValue("backend")).containsEntry("NEW_KEY", "first note")
        service.updateSecret(connection, dev, "NEW_KEY", SecretValue("v3"), "second note")
        assertThat(mock.notes.getValue("backend")).containsEntry("NEW_KEY", "second note")
        assertThat(service.secrets(connection, dev).first { it.key == "NEW_KEY" }.value.reveal()).isEqualTo("v3")

        val deleted = service.deleteSecrets(connection, dev, listOf("NEW_KEY", "NOT_THERE"))
        assertThat(deleted.getValue("NEW_KEY").getOrThrow()).isEqualTo(WriteOutcome.APPLIED)
        assertThat((deleted.getValue("NOT_THERE").exceptionOrNull() as SecretProviderException).kind).isEqualTo(Kind.NOT_FOUND)
        assertThat(mock.secrets.getValue("backend/dev")).doesNotContainKey("NEW_KEY")
        // deleting is the update call with a null value, as `doppler secrets delete` does
        val delete = mock.requests.last { it.method == "POST" && it.path == "/v3/configs/config/secrets" }
        assertThat(delete.body).contains("\"NOT_THERE\":null")
        assertThat(delete.query).containsEntry("project", "backend").containsEntry("config", "dev")
        assertThat(mock.requests.none { it.method == "DELETE" }).isTrue()
    }

    fun `test secret names must be uppercase`() {
        val connection = personal()
        val dev = SecretScope("backend", "dev")
        for (name in listOf("lower", "With-Dash", "1STARTS_WITH_DIGIT", "SPACE KEY")) {
            assertThatThrownBy { SecretsService.getInstance().createSecret(connection, dev, name, SecretValue("x")) }
                .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("uppercase")
        }
        assertThatThrownBy { SecretsService.getInstance().createSecret(connection, dev, "db-url", SecretValue("x")) }.hasMessageContaining("DB_URL")
        assertThat(mock.requests.none { it.method == "POST" && it.path == "/v3/configs/config/secrets" }).isTrue()
    }

    fun `test a locked config refuses writes with a clear message`() {
        val connection = personal()
        mock.lockedConfigs += "backend/prd"
        val failure = runCatching {
            SecretsService.getInstance().updateSecret(connection, SecretScope("backend", "prd"), "DB_PASSWORD", SecretValue("x"))
        }.exceptionOrNull() as SecretProviderException
        assertThat(failure.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(failure.message).contains("locked")
        assertThat(mock.secrets.getValue("backend/prd")).containsEntry("DB_PASSWORD", "prd-pass")
    }

    fun `test read-only setting blocks writes`() {
        val connection = personal()
        assertThat(SecretsService.getInstance().writesAllowed(connection)).isTrue()
        SecretsAppSettings.getInstance().state.readOnly = true
        assertThat(SecretsService.getInstance().writesAllowed(connection)).isFalse()
        assertThatThrownBy { SecretsService.getInstance().createSecret(connection, SecretScope("backend", "dev"), "K", SecretValue("v")) }
            .hasMessageContaining("Read-only")
        assertThat(mock.requests.none { it.method != "GET" }).isTrue()
    }

    fun `test moving copies raw values, then deletes, and reports what could not be removed`() {
        val connection = personal()
        val service = SecretsService.getInstance()
        val dev = SecretScope("backend", "dev")
        val stg = SecretScope("backend", "stg")
        val entries = service.secrets(connection, dev, fresh = true).filter { it.key in setOf("DB_URL", "DB_HOST") }
        mock.undeletable += "backend/dev/DB_HOST"

        val result = service.moveSecrets(connection, dev, entries, stg, overwrite = false)
        assertThat(result.moved).containsExactly("DB_URL")
        assertThat(result.notRemoved).containsOnlyKeys("DB_HOST")
        // both copies exist for the one that couldn't be removed; nothing is lost
        assertThat(mock.secrets.getValue("backend/stg")).containsEntry("DB_URL", "postgres://\${DB_HOST}:5432").containsEntry("DB_HOST", "localhost")
        assertThat(mock.secrets.getValue("backend/dev")).doesNotContainKey("DB_URL").containsKey("DB_HOST")

        // conflicts need overwrite
        val password = service.secrets(connection, dev, fresh = true).filter { it.key == "DB_PASSWORD" }
        assertThatThrownBy { service.moveSecrets(connection, dev, password, stg, overwrite = false) }.hasMessageContaining("Already in backend/stg")
        assertThat(mock.secrets.getValue("backend/stg")).containsEntry("DB_PASSWORD", "stg-pass")

        // to another project, the note goes along
        val moved = service.moveSecrets(connection, dev, password, SecretScope("web", "dev"), overwrite = false)
        assertThat(moved.moved).containsExactly("DB_PASSWORD")
        assertThat(mock.secrets.getValue("web/dev")).containsEntry("DB_PASSWORD", "dev-pass")
        assertThat(mock.notes.getValue("web")).containsEntry("DB_PASSWORD", "rotated monthly")
    }

    fun `test new environments are branch configs`() {
        val connection = personal()
        val created = SecretsService.getInstance().createEnvironment(connection, "backend", "Feature", "dev-feature-x")
        assertThat(created.slug).isEqualTo("dev_feature_x")
        assertThat(created.name).isEqualTo("dev_feature_x (branch of dev)")
        assertThat(mock.configs.getValue("backend").map { it.name }).contains("dev_feature_x")
        assertThat(mock.requests.last { it.path == "/v3/configs" && it.method == "POST" }.body).contains("\"environment\":\"dev\"")
        assertThatThrownBy { SecretsService.getInstance().createEnvironment(connection, "backend", "QA", "qa") }
            .hasMessageContaining("start with their environment")
    }

    fun `test launches resolve doppler references against the bound project and config`() {
        val connection = personal()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "backend"
            environment = "dev"
            path = null
            useProjectFile = false
            restrictToBoundProject = false
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(
                linkedMapOf(
                    "DB_PASSWORD" to "doppler://DB_PASSWORD",
                    "PRD_PASSWORD" to "doppler://prd:DB_PASSWORD",
                    "WEB" to "doppler://PUBLIC_URL?project=web",
                    "ALL" to "doppler://*?project=web",
                    "PLAIN" to "x",
                ),
            ),
        )
        assertThat(result.environment)
            .containsEntry("DB_PASSWORD", "dev-pass")
            .containsEntry("PUBLIC_URL", "http://localhost:3000")
            .containsEntry("PRD_PASSWORD", "prd-pass")
            .containsEntry("WEB", "http://localhost:3000")
            .containsEntry("PLAIN", "x")
            .doesNotContainKey("ALL")
            .doesNotContainKey("DOPPLER_CONFIG")

        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "doppler://MISSING"))) }.isInstanceOf(SecretProviderException::class.java)

        // "Only use the bound project" keeps references in the bound Doppler project
        ProjectSecretsSettings.getInstance(project).update { restrictToBoundProject = true }
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("WEB" to "doppler://PUBLIC_URL?project=web"))) }.hasMessageContaining("bound to backend")
    }

    fun `test doppler yaml names the project and config`() {
        val dir = Files.createTempDirectory("doppler-project")
        projectFileDir = dir
        Files.writeString(dir.resolve("doppler.yaml"), "setup:\n  project: backend\n  config: stg\n")
        val file = DopplerProvider().readProjectFile(dir)!!
        assertThat(file.projectId).isEqualTo("backend")
        assertThat(file.environment).isEqualTo("stg")
        assertThat(file.domain).isNull()
        // the CLI's user file (with its tokens) is not a project file
        Files.createDirectories(dir.resolve(".doppler"))
        Files.delete(dir.resolve("doppler.yaml"))
        Files.writeString(dir.resolve(".doppler/.doppler.yaml"), "scoped:\n  /:\n    token: dp.ct.x\n")
        assertThat(DopplerProvider().readProjectFile(dir.resolve(".doppler"))).isNull()
    }

    fun `test browser sign-in rides out passing server problems`() {
        val connection = connection(DopplerAuthMethods.BROWSER)
        mock.failingPolls = 2
        assertThat(client(connection).signIn(Interaction(browser = {}))).isEqualTo("alice@example.com (Acme)")
        assertThat(mock.failingPolls).isZero()
    }
}
