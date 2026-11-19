package cz.xprees.secrets

import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.run.EnvFileReferences
import cz.xprees.secrets.run.EnvPatcher
import cz.xprees.secrets.run.docker.DockerEnvPatcher
import cz.xprees.secrets.run.go.GoEnvPatcher
import cz.xprees.secrets.run.node.NodeJsEnvPatcher
import cz.xprees.secrets.run.node.NpmEnvPatcher
import cz.xprees.secrets.run.python.PythonEnvPatcher
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files

/** Every supported run configuration type: its patcher reads the variables and copies never write back into the original. */
class RunTargetsPlatformTest : BasePlatformTestCase() {

    private fun template(typeId: String): RunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType(typeId)
        assertNotNull("Run configuration type $typeId is not available in the test IDE", type)
        return type!!.configurationFactories.first().createTemplateConfiguration(project)
    }

    private fun checkIsolation(typeId: String, expected: Class<out EnvPatcher>) {
        val template = template(typeId)
        val (patcher, initial) = EnvPatcher.find(template) ?: error("No patcher for $typeId")
        assertThat(patcher).describedAs(typeId).isInstanceOf(expected)

        val configured = patcher.copyWith(template, initial + ("DB" to "infisical://DB_PASSWORD"))
        val launched = patcher.copyWith(configured, initial + ("DB" to "s3cr3t"))

        assertThat(patcher.read(launched)).describedAs(typeId).containsEntry("DB", "s3cr3t")
        assertThat(patcher.read(configured)).describedAs(typeId).containsEntry("DB", "infisical://DB_PASSWORD")
        assertThat(patcher.read(template)).describedAs(typeId).doesNotContainKey("DB")
        assertThat(launched).isNotSameAs(configured)
    }

    fun `test node js`() = checkIsolation("NodeJSConfigurationType", NodeJsEnvPatcher::class.java)

    fun `test npm`() = checkIsolation("js.build_tools.npm", NpmEnvPatcher::class.java)

    fun `test python`() = checkIsolation("PythonConfigurationType", PythonEnvPatcher::class.java)

    fun `test go`() = checkIsolation("GoApplicationRunConfiguration", GoEnvPatcher::class.java)

    fun `test docker`() = checkIsolation("docker-deploy", DockerEnvPatcher::class.java)

    fun `test env files with references are merged and detached only from the copy`() {
        val dir = Files.createTempDirectory("env-files")
        val envFile = dir.resolve(".env")
        Files.writeString(envFile, "DB_PASSWORD=infisical://DB_PASSWORD\nFROM_FILE=1\nOVERRIDDEN=file\n")
        val configuration = ApplicationConfiguration("App", project, ApplicationConfigurationType.getInstance())
        assertThat(configuration).isInstanceOf(EnvFilesOptions::class.java)
        configuration.envFilePaths = listOf(envFile.toString())
        configuration.envs = mapOf("OVERRIDDEN" to "configuration")

        val resolver = LaunchSecretResolver.getInstance(project)
        assertThat(EnvFileReferences.hasReferences(configuration, resolver)).isTrue()
        val (envs, useFiles) = EnvFileReferences.effective(configuration, configuration.envs, resolver)
        assertThat(useFiles).isTrue()
        assertThat(envs).containsEntry("DB_PASSWORD", "infisical://DB_PASSWORD").containsEntry("FROM_FILE", "1")
            .containsEntry("OVERRIDDEN", "configuration")

        val (patcher, _) = EnvPatcher.find(configuration)!!
        val copy = patcher.copyWith(configuration, mapOf("DB_PASSWORD" to "s3cr3t"))
        EnvFileReferences.detach(copy)
        assertThat((copy as EnvFilesOptions).envFilePaths).isEmpty()
        assertThat(configuration.envFilePaths).containsExactly(envFile.toString())

        // Files without references are left alone
        Files.writeString(envFile, "PLAIN=1\n")
        assertThat(EnvFileReferences.effective(configuration, configuration.envs, resolver).second).isFalse()
    }
}
