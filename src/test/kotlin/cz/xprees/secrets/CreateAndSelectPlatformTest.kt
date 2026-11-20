package cz.xprees.secrets

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.DotEnvLines
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.ui.toolwindow.SecretLocation
import cz.xprees.secrets.ui.toolwindow.SecretNode
import cz.xprees.secrets.ui.toolwindow.SecretsToolWindowPanel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/** Creating secrets, locating secrets from the editor, and selecting them in the tool window. */
class CreateAndSelectPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockInfisical
    private lateinit var connection: SecretConnection

    override fun setUp() {
        super.setUp()
        mock = MockInfisical()
        connection = SecretConnection(
            "create-${System.nanoTime()}", InfisicalProvider.ID, "Test", mock.baseUrl, InfisicalAuthMethods.MACHINE_IDENTITY,
            mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to mock.clientId),
        )
        CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET), mock.clientSecret)
        SecretsAppSettings.getInstance().setConnections(listOf(connection))
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "p1"
            environment = "dev"
            path = null
            useProjectFile = false
        }
        SecretsService.getInstance().invalidate()
        assertNotNull(KeysIndex.getInstance(project).refreshNow())
    }

    override fun tearDown() {
        try {
            CredentialVault.removeAll(connection.id, listOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET))
            SecretsAppSettings.getInstance().setConnections(emptyList())
            mock.close()
        } finally {
            super.tearDown()
        }
    }

    fun `test secrets are created, then listed`() {
        val service = SecretsService.getInstance()
        val scope = SecretScope("p1", "dev", "/")
        service.secrets(connection, scope) // cached before the creation
        assertThat(service.createSecret(connection, scope, "STRIPE_KEY", SecretValue("sk_test"), "from .env")).isEqualTo(WriteOutcome.APPLIED)
        val request = mock.requests.last { it.method == "POST" }
        assertThat(request.path).isEqualTo("/api/v4/secrets/STRIPE_KEY")
        assertThat(request.body).contains("\"projectId\":\"p1\"").contains("\"environment\":\"dev\"").contains("\"secretPath\":\"/\"")
            .contains("\"secretComment\":\"from .env\"").contains("\"type\":\"shared\"")
        // The cache was dropped, so the new secret shows up
        assertThat(service.secrets(connection, scope).map { it.key }).contains("STRIPE_KEY")
    }

    fun `test existing keys, change requests and read-only connections`() {
        val service = SecretsService.getInstance()
        val scope = SecretScope("p1", "dev", "/")
        assertThatThrownBy { service.createSecret(connection, scope, "DB_PASSWORD", SecretValue("x")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("already exist")

        mock.requireApproval = true
        assertThat(service.createSecret(connection, scope, "NEEDS_REVIEW", SecretValue("x"))).isEqualTo(WriteOutcome.APPROVAL_REQUESTED)

        val oauth = connection.copy(id = "oauth-${System.nanoTime()}", authMethodId = InfisicalAuthMethods.OAUTH)
        val before = mock.requests.size
        assertThatThrownBy { service.createSecret(oauth, scope, "X", SecretValue("x")) }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("read-only")
        assertThat(mock.requests).hasSize(before)
    }

    fun `test locations follow the binding and what a reference names`() {
        val bound = SecretLocation.of(project, "DB_PASSWORD")!!
        assertThat(bound).isEqualTo(SecretLocation(connection.id, "p1", "dev", "/", "DB_PASSWORD"))
        val explicit = SecretLocation.of(project, null, SecretReference.parse("infisical://prod/backend/X", listOf("infisical")))!!
        assertThat(explicit).isEqualTo(SecretLocation(connection.id, "p1", "prod", "/backend", "X"))
        assertThat(SecretLocation.decode(explicit.encode())).isEqualTo(explicit)
        assertThat(SecretLocation.decode("garbage")).isNull()
    }

    fun `test the key chooser offers every folder, same and similar names first, the bound folder before others`() {
        val choices = listOf(
            DotEnvLines.KeyChoice("API_KEY", "/"),
            DotEnvLines.KeyChoice("DB_PASSWORD", "/backend"),
            DotEnvLines.KeyChoice("DATABASE_URL", "/"),
            DotEnvLines.KeyChoice("DATABASE_PASSWORD", "/backend/api"),
            DotEnvLines.KeyChoice("DATABASE_PASSWORD", "/"),
            DotEnvLines.KeyChoice("ZED", "/"),
        )
        val ranked = DotEnvLines.rankChoices("DATABASE_PASSWORD", "/", choices).map { "${it.key}@${it.path}" }
        assertThat(ranked).containsExactly(
            "DATABASE_PASSWORD@/", "DATABASE_PASSWORD@/backend/api", "DATABASE_URL@/", "DB_PASSWORD@/backend", "API_KEY@/", "ZED@/",
        )
        // The listing the chooser loads: the whole environment from the root, subfolders included, with their folders
        val everywhere = SecretsService.getInstance().secrets(connection, SecretScope("p1", "dev", "/", recursive = true))
        assertThat(everywhere.map { "${it.key}@${it.path}" }).contains("DB_PASSWORD@/", "BACKEND_ONLY@/backend")
    }

    fun `test secrets are changed, change requests and read-only connections handled`() {
        val service = SecretsService.getInstance()
        val scope = SecretScope("p1", "dev", "/")
        assertThat(service.updateSecret(connection, scope, "API_KEY", SecretValue("new-key"), "rotated")).isEqualTo(WriteOutcome.APPLIED)
        val request = mock.requests.last { it.method == "PATCH" }
        assertThat(request.path).isEqualTo("/api/v4/secrets/API_KEY")
        assertThat(request.body).contains("\"secretValue\":\"new-key\"").contains("\"secretComment\":\"rotated\"").contains("\"type\":\"shared\"")
        assertThat(mock.updated).containsEntry("dev:/:API_KEY", "new-key")
        // No comment given: the comment is left as it is
        service.updateSecret(connection, scope, "API_KEY", SecretValue("v2"))
        assertThat(mock.requests.last { it.method == "PATCH" }.body).doesNotContain("secretComment")

        assertThatThrownBy { service.updateSecret(connection, scope, "MISSING", SecretValue("x")) }.isInstanceOf(SecretProviderException::class.java)
        mock.requireApproval = true
        assertThat(service.updateSecret(connection, scope, "API_KEY", SecretValue("x"))).isEqualTo(WriteOutcome.APPROVAL_REQUESTED)
        val oauth = connection.copy(id = "oauth-${System.nanoTime()}", authMethodId = InfisicalAuthMethods.OAUTH)
        assertThatThrownBy { service.updateSecret(oauth, scope, "API_KEY", SecretValue("x")) }.hasMessageContaining("read-only")
    }

    fun `test read-only in the settings stops every write before it is sent`() {
        val service = SecretsService.getInstance()
        val scope = SecretScope("p1", "dev", "/")
        SecretsAppSettings.getInstance().state.readOnly = true
        try {
            assertThat(service.writesAllowed).isFalse()
            val before = mock.requests.size
            assertThatThrownBy { service.createSecret(connection, scope, "NEW", SecretValue("x")) }.hasMessageContaining("Read-only")
            assertThatThrownBy { service.updateSecret(connection, scope, "API_KEY", SecretValue("x")) }.hasMessageContaining("Read-only")
            assertThatThrownBy { service.createEnvironment(connection, "p1", "QA", "qa") }.hasMessageContaining("Read-only")
            assertThat(mock.requests).hasSize(before)
            // Reading is unaffected
            assertThat(service.secrets(connection, scope, fresh = true).map { it.key }).contains("API_KEY")
        } finally {
            SecretsAppSettings.getInstance().state.readOnly = false
        }
    }

    fun `test the tool window loads its way down to a secret and selects it`() {
        val panel = SecretsToolWindowPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.select(SecretLocation(connection.id, "p1", "dev", "/backend", "BACKEND_ONLY"))
        val deadline = System.currentTimeMillis() + 15_000
        fun selected() = (panel.tree.selectionPath?.lastPathComponent as? SecretNode)?.entry?.key
        while (selected() == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertThat(selected()).isEqualTo("BACKEND_ONLY")
    }

    fun `test environments are created and listed, duplicates and read-only connections are refused`() {
        val service = SecretsService.getInstance()
        service.projects(connection) // cached before the creation
        val created = service.createEnvironment(connection, "p1", "QA Staging", "qa-staging")
        assertThat(created.slug).isEqualTo("qa-staging")
        assertThat(created.name).isEqualTo("QA Staging")
        assertThat(mock.requests.last { it.method == "POST" }.path).isEqualTo("/api/v1/projects/p1/environments")
        assertThat(service.projects(connection).single().environments.map { it.slug }).contains("dev", "prod", "qa-staging")

        assertThatThrownBy { service.createEnvironment(connection, "p1", "Dev", "dev") }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("already exist")
        val oauth = connection.copy(id = "oauth-${System.nanoTime()}", authMethodId = InfisicalAuthMethods.OAUTH)
        assertThatThrownBy { service.createEnvironment(oauth, "p1", "X", "x") }
            .isInstanceOf(SecretProviderException::class.java).hasMessageContaining("read-only")
        assertThat(cz.xprees.secrets.ui.secrets.NewEnvironmentDialog.slugOf("QA  Staging (EU)")).isEqualTo("qa-staging-eu")
    }

    fun `test copy key takes the names of the selected secrets`() {
        val panel = SecretsToolWindowPanel(project)
        Disposer.register(testRootDisposable, panel)
        assertThat(panel.selectedKeysText()).isNull()
        panel.select(SecretLocation(connection.id, "p1", "dev", "/", "DB_PASSWORD"))
        val deadline = System.currentTimeMillis() + 15_000
        while ((panel.tree.selectionPath?.lastPathComponent as? SecretNode) == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertThat(panel.selectedKeysText()).isEqualTo("DB_PASSWORD")
        // Several secrets: one key per line, never a value
        val secrets = (0 until panel.tree.rowCount).map { panel.tree.getPathForRow(it) }.filter { it.lastPathComponent is SecretNode }
        panel.tree.selectionPaths = secrets.toTypedArray()
        assertThat(panel.selectedKeysText()!!.lines()).contains("DB_PASSWORD", "API_KEY").noneMatch { it.contains("dev-pass") }
    }

    fun `test folder suggestions walk the environment from the root`() {
        assertThat(SecretsService.getInstance().allFolders(connection, "p1", "dev")).containsExactly("/", "/backend", "/backend/api")
    }

    fun `test production is the project's pick when set, otherwise the name pattern, and the prompt can be turned off`() {
        val settings = ProjectSecretsSettings.getInstance(project)
        val app = SecretsAppSettings.getInstance().state
        try {
            assertThat(ProdGuard.isProduction(project, "prod")).isTrue()
            assertThat(ProdGuard.isProduction(project, "main")).isFalse()
            // Teams that call production "main" and "eu-live"
            settings.update { productionEnvironments = "main, eu-live" }
            assertThat(ProdGuard.isProduction(project, "main")).isTrue()
            assertThat(ProdGuard.isProduction(project, "eu-live")).isTrue()
            assertThat(ProdGuard.isProduction(project, "prod")).isFalse()
            assertThat(ProdGuard.needsConfirmation(project, "main")).isTrue()
            app.prodGuardEnabled = false
            assertThat(ProdGuard.needsConfirmation(project, "main")).isFalse()
            assertThat(ProdGuard.isProduction(project, "main")).describedAs("still marked").isTrue()
        } finally {
            settings.update { productionEnvironments = null }
            app.prodGuardEnabled = true
        }
    }
}
