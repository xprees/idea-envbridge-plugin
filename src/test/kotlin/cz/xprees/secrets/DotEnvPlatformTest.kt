package cz.xprees.secrets

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.DotEnvLines
import cz.xprees.secrets.editor.DotEnvPlaintextSecretInspection
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.run.EnvFileReferences
import cz.xprees.secrets.run.EnvPatcher
import cz.xprees.secrets.run.SecretsProgramRunner
import cz.xprees.secrets.terminal.TerminalSecretsHandoff
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files

/** `.env` files: the plaintext inspection and its fix, Node.js env files, the dotenv convention, and the terminal handoff. */
class DotEnvPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockInfisical
    private lateinit var connection: SecretConnection

    override fun setUp() {
        super.setUp()
        mock = MockInfisical()
        connection = SecretConnection(
            "dotenv-${System.nanoTime()}", InfisicalProvider.ID, "Test", mock.baseUrl, InfisicalAuthMethods.MACHINE_IDENTITY,
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

    fun `test dotenv lines with quotes, comments and export`() {
        val text = "export A=1\nB=\"two words\" # c\nC='x'\nD=plain # comment\n# E=no\nF=\n"
        val lines = DotEnvLines.parse(text).associateBy { it.name }
        assertThat(lines.keys).containsExactly("A", "B", "C", "D", "F")
        assertThat(lines.getValue("B").value).isEqualTo("two words")
        assertThat(text.substring(lines.getValue("B").valueRange.startOffset, lines.getValue("B").valueRange.endOffset)).isEqualTo("two words")
        assertThat(lines.getValue("D").value).isEqualTo("plain")
        assertThat(lines.getValue("F").value).isEmpty()
        assertThat(DotEnvLines.isDotEnv(".env")).isTrue()
        assertThat(DotEnvLines.isDotEnv(".env.local")).isTrue()
        assertThat(DotEnvLines.isDotEnv("app.env")).isTrue()
        assertThat(DotEnvLines.isDotEnv("environment.ts")).isFalse()
    }

    fun `test completion after the scheme offers keys of every folder, qualified with their folder`() {
        myFixture.configureByText(".env", "X=infisical://BACKEND<caret>\n")
        myFixture.completeBasic()
        // The only match is inserted right away, with its folder
        assertThat(myFixture.editor.document.text).isEqualTo("X=infisical://BACKEND_ONLY?path=/backend\n")
        // ...and the qualified reference is known to the editor, so it isn't reported as missing
        myFixture.enableInspections(cz.xprees.secrets.editor.MissingSecretInspection::class.java)
        assertThat(myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.description?.contains("BACKEND_ONLY") == true }).isEmpty()
        myFixture.configureByText(".env.local", "X=infisical://BACKEND_ONLY?path=/nowhere\n")
        assertThat(myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.description?.contains("BACKEND_ONLY") == true }).isNotEmpty()
    }

    fun `test the comment above a variable is found for extracting`() {
        val text = "# unrelated\n\n# Base URL of the deployed Cloudflare export-zip worker.\n#   Leave empty to use the legacy local zip cron.\nEXPORT_WORKER_URL=https://export.example.app\n" +
            "# OLD_KEY=1\nNEXT=2\nNONE=3\n"
        val lines = DotEnvLines.parse(text).associateBy { it.name }
        assertThat(DotEnvLines.commentAbove(text, lines.getValue("EXPORT_WORKER_URL")))
            .isEqualTo("Base URL of the deployed Cloudflare export-zip worker. Leave empty to use the legacy local zip cron.")
        assertThat(DotEnvLines.commentAbove(text, lines.getValue("NEXT"))).describedAs("commented-out assignment").isEmpty()
        assertThat(DotEnvLines.commentAbove(text, lines.getValue("NONE"))).isEmpty()
        assertThat(DotEnvLines.commentAbove("# only\nFIRST=1", DotEnvLines.parse("# only\nFIRST=1").single())).isEqualTo("only")
    }

    fun `test plaintext secrets in env files are reported and bound with comments`() {
        myFixture.enableInspections(DotEnvPlaintextSecretInspection::class.java)
        myFixture.configureByText(
            ".env",
            "DB_PASSWORD=dev-pass\nAPI_KEY=\"abc\" # old comment\nPATH=/usr/bin\nREF=infisical://API_KEY\nPROD_ONLY=x\n" +
                "ConnectionStrings__Default=fallback # infisical://ConnectionStrings__Default\n",
        )
        val problems = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
            .filter { it.description?.contains("Infisical") == true }.mapNotNull(HighlightInfo::getDescription)
        // PROD_ONLY isn't in the bound environment (dev), PATH isn't a secret, REF and the bound line are taken care of
        assertThat(problems).hasSize(2)
        assertThat(problems).anySatisfy { assertThat(it).contains("DB_PASSWORD").contains("dev") }

        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.familyName == "Bind to Infisical and clear the value" && it.text.contains("DB_PASSWORD") })
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.familyName == "Bind to Infisical, keep the value as local fallback" })
        assertThat(myFixture.editor.document.text).isEqualTo(
            "DB_PASSWORD= # infisical://DB_PASSWORD\nAPI_KEY=\"abc\" # infisical://API_KEY\nPATH=/usr/bin\nREF=infisical://API_KEY\nPROD_ONLY=x\n" +
                "ConnectionStrings__Default=fallback # infisical://ConnectionStrings__Default\n",
        )
        assertThat(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description?.contains("Infisical") == true }).isEmpty()
        // The binding comment is a reference the editor knows (gutter icon, masked hint)
        assertThat(cz.xprees.secrets.editor.EditorReferences.of(myFixture.file).map { it.name }).contains("DB_PASSWORD", "API_KEY")
    }

    fun `test empty variables can be bound with an intention`() {
        myFixture.configureByText(".env.local", "API_KEY=<caret>\nOTHER=\n")
        val intention = myFixture.getAvailableIntention("Bind to Infisical")
        assertNotNull(intention)
        myFixture.launchAction(intention!!)
        assertThat(myFixture.editor.document.text).isEqualTo("API_KEY= # infisical://API_KEY\nOTHER=\n")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("OTHER=") + 6)
        assertNull(myFixture.getAvailableIntention("Bind to Infisical"))
    }

    private fun npmConfiguration(): NpmRunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType("js.build_tools.npm")!!
        val template = type.configurationFactories.first().createTemplateConfiguration(project)
        return RunManager.getInstance(project).createConfiguration(template, template.factory!!).configuration as NpmRunConfiguration
    }

    fun `test env file field of npm configurations may hold references and is detached from the copy only`() {
        val dir = Files.createTempDirectory("npm-env")
        val envFile = dir.resolve("secrets.env")
        Files.writeString(envFile, "DB=infisical://DB_PASSWORD\nPLAIN=1\n")
        val configuration = npmConfiguration()
        configuration.runSettings = configuration.runSettings.toBuilder()
            .setEnvData(EnvironmentVariablesData.create(mapOf("OWN" to "x"), true, envFile.toString())).build()

        val resolver = LaunchSecretResolver.getInstance(project)
        assertThat(EnvFileReferences.hasReferences(configuration, resolver)).isTrue()
        val (envs, useFiles) = EnvFileReferences.effective(configuration, mapOf("OWN" to "x"), resolver)
        assertThat(useFiles).isTrue()
        assertThat(envs).containsEntry("DB", "infisical://DB_PASSWORD").containsEntry("PLAIN", "1").containsEntry("OWN", "x")
        assertThat(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)).isInstanceOf(SecretsProgramRunner::class.java)

        val (patcher, _) = EnvPatcher.find(configuration)!!
        val copy = patcher.copyWith(configuration, mapOf("DB" to "dev-pass")) as NpmRunConfiguration
        EnvFileReferences.detach(copy)
        assertThat(copy.runSettings.envData.environmentFile).isNullOrEmpty()
        assertThat(copy.runSettings.envData.envs).containsEntry("DB", "dev-pass")
        assertThat(configuration.runSettings.envData.environmentFile).isEqualTo(envFile.toString())
    }

    fun `test references in the dotenv file a node tool reads itself are resolved, its other lines are left to the tool`() {
        val dir = Files.createTempDirectory("nuxt-app")
        Files.writeString(dir.resolve("package.json"), "{}")
        Files.writeString(dir.resolve(".env"), "NUXT_PUBLIC_URL=infisical://API_KEY\nPLAIN=1\nINTERPOLATED=\${PLAIN}\nBOUND=http://localhost # infisical://DB_PASSWORD\n")
        val configuration = npmConfiguration()
        configuration.runSettings = configuration.runSettings.toBuilder().setPackageJsonPath(dir.resolve("package.json").toString()).build()

        val resolver = LaunchSecretResolver.getInstance(project)
        assertThat(EnvFileReferences.dotenvFiles(configuration)).containsExactly(dir.resolve(".env"))
        assertThat(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)).isInstanceOf(SecretsProgramRunner::class.java)
        val (envs, useFiles) = EnvFileReferences.effective(configuration, mapOf("NUXT_PUBLIC_URL" to "configured-wins"), resolver)
        assertThat(useFiles).isFalse()
        assertThat(envs).containsOnlyKeys("NUXT_PUBLIC_URL", "BOUND").containsEntry("NUXT_PUBLIC_URL", "configured-wins")
        assertThat(EnvFileReferences.effective(configuration, emptyMap(), resolver).first)
            .containsOnlyKeys("NUXT_PUBLIC_URL", "BOUND")
            .containsEntry("NUXT_PUBLIC_URL", "infisical://API_KEY").containsEntry("BOUND", "infisical://DB_PASSWORD")

        // Without references the file is the tool's business only
        Files.writeString(dir.resolve(".env"), "PLAIN=1\n")
        assertThat(EnvFileReferences.hasReferences(configuration, resolver)).isFalse()
    }

    fun `test the terminal handoff is taken once`() {
        TerminalSecretsHandoff.offer(mapOf("A" to "1"))
        assertThat(TerminalSecretsHandoff.take()).containsEntry("A", "1")
        assertThat(TerminalSecretsHandoff.take()).isNull()
    }
}
