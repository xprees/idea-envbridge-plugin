package cz.xprees.secrets

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.database.InfisicalDatabaseAuthProvider
import cz.xprees.secrets.dotnet.AppSettingsKeys
import cz.xprees.secrets.dotnet.AppSettingsPlaintextSecretInspection
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.run.XmlEnvPatcher
import org.assertj.core.api.Assertions.assertThat
import java.lang.reflect.Proxy

class M4PlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockInfisical
    private lateinit var connection: SecretConnection

    override fun setUp() {
        super.setUp()
        mock = MockInfisical()
        connection = SecretConnection(
            "m4-${System.nanoTime()}", InfisicalProvider.ID, "Test", mock.baseUrl, InfisicalAuthMethods.MACHINE_IDENTITY,
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

    fun `test appsettings key mapping`() {
        assertThat(AppSettingsKeys.isAppSettings("appsettings.json")).isTrue()
        assertThat(AppSettingsKeys.isAppSettings("appsettings.Development.json")).isTrue()
        assertThat(AppSettingsKeys.isAppSettings("package.json")).isFalse()
        assertThat(AppSettingsKeys.match(listOf("ConnectionStrings", "Default"), listOf("connectionstrings__default"))).isEqualTo("connectionstrings__default")
        assertThat(AppSettingsKeys.match(listOf("Logging", "Level"), listOf("ConnectionStrings__Default"))).isNull()
        assertThat(AppSettingsKeys.toConfigurationKey("A__B__C")).isEqualTo("A:B:C")
    }

    fun `test appsettings marks overridden settings and reports plaintext secrets`() {
        myFixture.enableInspections(AppSettingsPlaintextSecretInspection::class.java)
        myFixture.configureByText(
            "appsettings.Development.json",
            """{ "ConnectionStrings": { "Default": "Server=<caret>db;Password=hunter2" }, "Logging": { "Level": "Debug" } }""",
        )
        val infos = myFixture.doHighlighting()
        val warnings = infos.filter { it.severity == HighlightSeverity.WEAK_WARNING }.mapNotNull(HighlightInfo::getDescription)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("ConnectionStrings__Default")
        assertThat(infos.filter { it.toolTip?.contains("Overridden at run time") == true }).hasSize(1)

        myFixture.launchAction(myFixture.findSingleIntention("Clear the value"))
        assertThat(myFixture.editor.document.text).contains("\"Default\": \"\"").doesNotContain("hunter2")
    }

    fun `test xml env patcher replaces envs only in the copy`() {
        val configuration = ApplicationConfiguration("App", project, ApplicationConfigurationType.getInstance())
        configuration.envs = linkedMapOf("DB" to "infisical://DB_PASSWORD", "PLAIN" to "x")
        val patcher = XmlEnvPatcher(setOf(configuration.type.id))
        assertThat(patcher.read(configuration)).containsEntry("DB", "infisical://DB_PASSWORD").containsEntry("PLAIN", "x")
        val copy = patcher.copyWith(configuration, mapOf("DB" to "dev-pass", "PLAIN" to "x")) as ApplicationConfiguration
        assertThat(copy.envs).containsEntry("DB", "dev-pass")
        assertThat(configuration.envs).containsEntry("DB", "infisical://DB_PASSWORD")
        assertThat(XmlEnvPatcher(setOf("SomethingElse")).read(configuration)).isNull()
    }

    fun `test database connections get user and password from references`() {
        assertThat(DatabaseAuthProvider.get(InfisicalDatabaseAuthProvider.ID)).isInstanceOf(InfisicalDatabaseAuthProvider::class.java)
        val point = proxy<DatabaseConnectionPoint> { method, _ ->
            when (method.name) {
                "getAdditionalProperty" -> null
                else -> null
            }
        }
        val properties = HashMap<String, String>()
        val pointWithRefs = proxy<DatabaseConnectionPoint> { method, args ->
            if (method.name == "getAdditionalProperty") {
                when (args?.get(0)) {
                    InfisicalDatabaseAuthProvider.PROP_USER -> "app_user"
                    InfisicalDatabaseAuthProvider.PROP_PASSWORD -> "infisical://DB_PASSWORD"
                    else -> null
                }
            } else {
                null
            }
        }
        val connection = proxy<ProtoConnection> { method, _ ->
            when (method.name) {
                "getProject" -> project
                "getConnectionPoint" -> pointWithRefs
                "getConnectionProperties" -> properties
                else -> null
            }
        }
        val proceed = kotlinx.coroutines.runBlocking { InfisicalDatabaseAuthProvider().interceptConnection(connection, false) }
        assertThat(proceed).isTrue()
        assertThat(properties).containsEntry("user", "app_user").containsEntry("password", "dev-pass")
        assertNotNull(point)
    }

    private inline fun <reified T> proxy(crossinline handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> T::class.java.simpleName
                else -> handler(method, args)
            }
        } as T
}
