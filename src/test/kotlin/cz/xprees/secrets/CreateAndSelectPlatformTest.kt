package cz.xprees.secrets

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.CreateOutcome
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.KeysIndex
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
        assertThat(service.createSecret(connection, scope, "STRIPE_KEY", SecretValue("sk_test"), "from .env")).isEqualTo(CreateOutcome.CREATED)
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
        assertThat(service.createSecret(connection, scope, "NEEDS_REVIEW", SecretValue("x"))).isEqualTo(CreateOutcome.APPROVAL_REQUESTED)

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

    fun `test the key chooser offers the same and similar names first`() {
        val ranked = DotEnvLines.rankKeys("DATABASE_PASSWORD", listOf("API_KEY", "DB_PASSWORD", "DATABASE_URL", "DATABASE_PASSWORD", "ZED"))
        assertThat(ranked).containsExactly("DATABASE_PASSWORD", "DATABASE_URL", "DB_PASSWORD", "API_KEY", "ZED")
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
}
