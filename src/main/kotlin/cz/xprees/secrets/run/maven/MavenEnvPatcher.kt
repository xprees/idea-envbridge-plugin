package cz.xprees.secrets.run.maven

import com.intellij.execution.configurations.RunProfile
import cz.xprees.secrets.run.EnvPatcher
import org.jetbrains.idea.maven.execution.MavenRunConfiguration
import org.jetbrains.idea.maven.execution.MavenRunner

/**
 * Maven run configurations. Their variables live in the configuration's own runner settings or, when it has none, in the
 * project's Maven runner settings (Settings › Build Tools › Maven › Runner).
 */
class MavenEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? {
        val configuration = profile as? MavenRunConfiguration ?: return null
        val settings = configuration.runnerSettings ?: MavenRunner.getInstance(configuration.project).settings
        return settings.environmentProperties
    }

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        val original = profile as MavenRunConfiguration
        val copy = original.clone()
        // A settings object of the copy's own, so neither the original nor the project defaults are touched
        val settings = (original.runnerSettings ?: MavenRunner.getInstance(original.project).settings).clone()
        settings.environmentProperties = LinkedHashMap(envs)
        copy.runnerSettings = settings
        return copy
    }
}
