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

    fun `test plaintext secrets in env files are reported and replaced by references`() {
        myFixture.enableInspections(DotEnvPlaintextSecretInspection::class.java)
        myFixture.configureByText(
            ".env",
            "DB_PASSWORD=dev-pass\nAPI_KEY=\"abc\" # comment\nPATH=/usr/bin\nREF=infisical://API_KEY\nPROD_ONLY=x\n",
        )
        val problems = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
            .filter { it.description?.contains("Infisical") == true }.mapNotNull(HighlightInfo::getDescription)
        // PROD_ONLY isn't in the bound environment (dev), PATH isn't a secret, REF is already a reference
        assertThat(problems).hasSize(2)
        assertThat(problems).anySatisfy { assertThat(it).contains("DB_PASSWORD").contains("dev") }

        while (true) {
            val fix = myFixture.getAllQuickFixes().firstOrNull { it.familyName == "Replace with Infisical reference" } ?: break
            myFixture.launchAction(fix)
        }
        assertThat(myFixture.editor.document.text).isEqualTo(
            "DB_PASSWORD=infisical://DB_PASSWORD\nAPI_KEY=\"infisical://API_KEY\" # comment\nPATH=/usr/bin\nREF=infisical://API_KEY\nPROD_ONLY=x\n",
        )
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
        Files.writeString(dir.resolve(".env"), "NUXT_PUBLIC_URL=infisical://API_KEY\nPLAIN=1\nINTERPOLATED=\${PLAIN}\n")
        val configuration = npmConfiguration()
        configuration.runSettings = configuration.runSettings.toBuilder().setPackageJsonPath(dir.resolve("package.json").toString()).build()

        val resolver = LaunchSecretResolver.getInstance(project)
        assertThat(EnvFileReferences.dotenvFiles(configuration)).containsExactly(dir.resolve(".env"))
        assertThat(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)).isInstanceOf(SecretsProgramRunner::class.java)
        val (envs, useFiles) = EnvFileReferences.effective(configuration, mapOf("NUXT_PUBLIC_URL" to "configured-wins"), resolver)
        assertThat(useFiles).isFalse()
        assertThat(envs).containsExactly(org.assertj.core.api.Assertions.entry("NUXT_PUBLIC_URL", "configured-wins"))
        assertThat(EnvFileReferences.effective(configuration, emptyMap(), resolver).first)
            .containsExactly(org.assertj.core.api.Assertions.entry("NUXT_PUBLIC_URL", "infisical://API_KEY"))

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
