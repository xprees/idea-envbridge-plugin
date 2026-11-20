package cz.xprees.secrets

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.execution.process.NopProcessHandler
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.MissingSecretInspection
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.run.masking.SecretMaskingService
import org.assertj.core.api.Assertions.assertThat

class EditorPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockInfisical
    private lateinit var connection: SecretConnection

    override fun setUp() {
        super.setUp()
        mock = MockInfisical()
        connection = SecretConnection(
            "editor-${System.nanoTime()}", InfisicalProvider.ID, "Test", mock.baseUrl, InfisicalAuthMethods.MACHINE_IDENTITY,
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

    fun `test keys index knows every environment`() {
        val snapshot = KeysIndex.getInstance(project).current()!!
        assertThat(snapshot.boundKeys.keys).containsExactlyInAnyOrder("DB_PASSWORD", "API_KEY", "ConnectionStrings__Default")
        assertThat(snapshot.coverage("DB_PASSWORD")).containsExactlyInAnyOrder("dev", "prod")
        assertThat(snapshot.coverage("PROD_ONLY")).containsExactly("prod")
    }

    fun `test completion of secret keys where code reads variables`() {
        myFixture.configureByText("app.js", "const password = process.env.<caret>")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("DB_PASSWORD", "API_KEY", "PROD_ONLY")

        myFixture.configureByText("App.txt", "String p = System.getenv(\"API<caret>\");")
        myFixture.completeBasic()
        // a single match is inserted right away
        assertThat(myFixture.editor.document.text).contains("System.getenv(\"API_KEY\")")
    }

    fun `test missing secrets are reported, present ones and ordinary variables are not`() {
        myFixture.enableInspections(MissingSecretInspection::class.java)
        myFixture.configureByText(
            "config.txt",
            "A=infisical://NOPE\nB=infisical://DB_PASSWORD\nC=process.env.PROD_ONLY\nD=process.env.PATH\nE=infisical://prod:PROD_ONLY\n",
        )
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull(HighlightInfo::getDescription)
        assertThat(warnings).hasSize(2)
        assertThat(warnings).anySatisfy { assertThat(it).contains("NOPE").contains("dev") }
        assertThat(warnings).anySatisfy { assertThat(it).contains("PROD_ONLY").contains("prod") }
    }

    fun `test secrets are annotated with their environments`() {
        myFixture.configureByText("main.py", "import os\npassword = os.environ[\"DB_PASSWORD\"]\n")
        val infos = myFixture.doHighlighting().filter { it.toolTip?.contains("DB_PASSWORD") == true }
        assertThat(infos).isNotEmpty()
        assertThat(infos.first().toolTip).contains("dev").contains("prod").doesNotContain("dev-pass")
    }

    fun `test inlay provider applies to every language`() {
        for (language in listOf(PlainTextLanguage.INSTANCE) + listOf("TypeScript", "JAVA", "Python", "yaml").map { com.intellij.lang.Language.findLanguageByID(it)!! }) {
            val providers = InlayHintsProviderFactory.getProvidersForLanguage(language).map { it.providerId }
            assertThat(providers).describedAs(language.id).contains("cz.xprees.secrets.hints")
        }
    }

    fun `test run with environment overrides the binding for one launch`() {
        val resolver = LaunchSecretResolver.getInstance(project)
        val plan = resolver.plan(mapOf("DB" to "infisical://DB_PASSWORD"))
        assertThat(resolver.resolve(plan).environment).containsEntry("DB", "dev-pass")
        assertThat(resolver.resolve(plan, environment = "prod").environment).containsEntry("DB", "prod-pass")
    }

    fun `test console masking while the process runs`() {
        val masking = SecretMaskingService.getInstance(project)
        val handler = NopProcessHandler()
        masking.register(handler, setOf("dev-pass", "ab"))
        assertThat(masking.mask("connecting with dev-pass now")).isEqualTo("connecting with ${SecretValue.MASK} now")
        assertThat(masking.mask("ab is too short to mask")).isNull()
        masking.unregister(handler)
        assertThat(masking.mask("dev-pass")).isNull()
    }
}
