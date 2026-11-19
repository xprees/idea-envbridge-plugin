package cz.xprees.secrets.run.node

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
}
