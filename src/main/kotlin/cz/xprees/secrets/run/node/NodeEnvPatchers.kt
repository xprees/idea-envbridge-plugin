package cz.xprees.secrets.run.node

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration
import com.jetbrains.nodejs.run.NodeJsRunConfiguration
import cz.xprees.secrets.run.EnvPatcher

/** Node.js run configurations (also with Bun or Deno set as the interpreter). */
class NodeJsEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? = (profile as? NodeJsRunConfiguration)?.envs

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        val copy = (profile as NodeJsRunConfiguration).clone() as NodeJsRunConfiguration
        // EnvironmentVariablesData is immutable: the copy gets a new object, the original keeps its own
        copy.setEnvData(profile.envData.with(LinkedHashMap(envs)))
        return copy
    }

    override fun attachedEnvFiles(profile: RunProfile): List<String> = envFiles((profile as NodeJsRunConfiguration).envData)

    override fun detachEnvFiles(copy: RunProfile) {
        val configuration = copy as NodeJsRunConfiguration
        configuration.setEnvData(withoutEnvFile(configuration.envData))
    }

    override fun dotenvDirectory(profile: RunProfile): String? =
        (profile as NodeJsRunConfiguration).workingDirectory?.takeIf { it.isNotBlank() } ?: basePathOf(profile)
}

/** npm, pnpm, Yarn and Bun script run configurations (package.json scripts). */
class NpmEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? = (profile as? NpmRunConfiguration)?.runSettings?.envData?.envs

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        val original = profile as NpmRunConfiguration
        val copy = original.clone() as NpmRunConfiguration
        // NpmRunSettings is immutable: build new settings for the copy
        val settings = original.runSettings
        copy.runSettings = settings.toBuilder().setEnvData(settings.envData.with(LinkedHashMap(envs))).build()
        return copy
    }

    override fun attachedEnvFiles(profile: RunProfile): List<String> = envFiles((profile as NpmRunConfiguration).runSettings.envData)

    override fun detachEnvFiles(copy: RunProfile) {
        val configuration = copy as NpmRunConfiguration
        val settings = configuration.runSettings
        configuration.runSettings = settings.toBuilder().setEnvData(withoutEnvFile(settings.envData)).build()
    }

    // Scripts run in the directory of their package.json
    override fun dotenvDirectory(profile: RunProfile): String? =
        (profile as NpmRunConfiguration).runSettings.packageJsonSystemDependentPath?.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it).parent } ?: basePathOf(profile)
}

/**
 * The project directory, read through the platform's [RunConfiguration] interface: calling the inherited `getProject` on the
 * npm configuration class would make the plugin verifier resolve its Node.js base class, which IDEs with JavaScript but
 * without Node.js (DataGrip) don't have.
 */
private fun basePathOf(profile: RunProfile): String? = (profile as? RunConfiguration)?.project?.basePath

/** The *Environment file* field of Node.js tools (several files are separated with `;`). */
private fun envFiles(data: EnvironmentVariablesData): List<String> =
    data.environmentFile.orEmpty().split(';').map(String::trim).filter(String::isNotEmpty)

private fun withoutEnvFile(data: EnvironmentVariablesData): EnvironmentVariablesData =
    EnvironmentVariablesData.create(data.envs, data.isPassParentEnvs, null)
