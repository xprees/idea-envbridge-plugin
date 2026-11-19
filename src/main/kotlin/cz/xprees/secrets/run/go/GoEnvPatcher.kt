package cz.xprees.secrets.run.go

import com.goide.execution.GoRunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import cz.xprees.secrets.run.EnvPatcher

/** Go run configurations: Go Build (go run), Go Test, Go Application. */
class GoEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? = (profile as? GoRunConfigurationBase<*>)?.customEnvironment

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        val copy = (profile as GoRunConfigurationBase<*>).clone() as GoRunConfigurationBase<*>
        copy.customEnvironment = LinkedHashMap(envs)
        return copy
    }
}
