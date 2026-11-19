package cz.xprees.secrets.run.python

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.jetbrains.python.run.PythonRunParams
import cz.xprees.secrets.run.EnvPatcher

/** Python run configurations: scripts and modules, pytest and other test runners, Django, FastAPI, Flask. */
class PythonEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? =
        if (profile is RunConfiguration && profile is PythonRunParams) profile.envs else null

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        val copy = (profile as RunConfiguration).clone()
        (copy as PythonRunParams).envs = LinkedHashMap(envs)
        return copy
    }
}
