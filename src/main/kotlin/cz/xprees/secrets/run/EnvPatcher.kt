package cz.xprees.secrets.run

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration

/**
 * Reads and replaces the environment variables of one kind of run configuration. Each technology (JVM, Gradle, Maven,
 * Node.js, Python, Docker, .NET, …) contributes one; they are registered from optional plugin dependencies, so a patcher only
 * exists where its plugin is installed.
 *
 * Implementations must **never modify the given profile**: [copyWith] returns a copy, so secret values can't reach the
 * configuration the IDE stores in `workspace.xml`.
 */
interface EnvPatcher {
    /** The variables of [profile] as the user configured them, or null when this patcher does not handle it. */
    fun read(profile: RunProfile): Map<String, String>?

    /** A copy of [profile] whose variables are [envs]. */
    fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile

    companion object {
        val EP_NAME: ExtensionPointName<EnvPatcher> = ExtensionPointName.create("cz.xprees.secrets.envPatcher")

        /** The first patcher that handles [profile], with the variables it read. */
        fun find(profile: RunProfile): Pair<EnvPatcher, Map<String, String>>? {
            for (patcher in EP_NAME.extensionList) {
                val envs = runCatching { patcher.read(profile) }.getOrNull() ?: continue
                return patcher to envs
            }
            return null
        }
    }
}

/**
 * Run configurations with the platform's standard environment variables: Application, JUnit, TestNG, Spring Boot, Kotlin,
 * shell scripts and many more implement [CommonProgramRunConfigurationParameters].
 */
class CommonParametersEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? =
        if (profile is RunConfiguration && profile is CommonProgramRunConfigurationParameters) profile.envs else null

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        val copy = (profile as RunConfiguration).clone()
        (copy as CommonProgramRunConfigurationParameters).envs = LinkedHashMap(envs)
        return copy
    }
}

/** Gradle and other external-system run configurations (`ExternalSystemTaskExecutionSettings.env`). */
class ExternalSystemEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? = (profile as? ExternalSystemRunConfiguration)?.settings?.env

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        val original = profile as ExternalSystemRunConfiguration
        val copy = original.clone()
        // Never write through to the original (tests check that clone() gives the copy settings of its own)
        check(copy.settings !== original.settings) { "External system settings are shared between clones" }
        copy.settings.env = LinkedHashMap(envs)
        return copy
    }
}
