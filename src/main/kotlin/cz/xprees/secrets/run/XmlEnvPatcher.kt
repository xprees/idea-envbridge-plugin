package cz.xprees.secrets.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import org.jdom.Element
import org.jdom.Verifier

/**
 * Run configurations of the given [typeIds] whose environment variables are stored in the platform's standard XML form
 * (`<envs><env name=… value=…/></envs>`, written by [EnvironmentVariablesComponent]). Reads and replaces them through the
 * configuration's public `writeExternal` / `readExternal`, applied only to the in-memory copy, so no API of the plugin that owns
 * the configuration type is needed.
 */
open class XmlEnvPatcher(private val typeIds: Set<String>) : EnvPatcher {

    override fun read(profile: RunProfile): Map<String, String>? {
        if (profile !is RunConfiguration || profile.type.id !in typeIds) return null
        return readEnvs(serialize(profile))
    }

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        // XML can't carry every character; refuse without echoing the value (it may be a secret)
        envs.forEach { (name, value) ->
            require(Verifier.checkCharacterData(name) == null && Verifier.checkCharacterData(value) == null) {
                "The value of $name can't be passed to this run configuration (unsupported characters)"
            }
        }
        val copy = (profile as RunConfiguration).clone()
        val external = serialize(copy)
        writeEnvs(external, envs)
        copy.readExternal(external)
        return copy
    }

    private fun serialize(configuration: RunConfiguration): Element = Element("configuration").also(configuration::writeExternal)

    companion object {
        fun readEnvs(element: Element): Map<String, String> = LinkedHashMap<String, String>().also { EnvironmentVariablesComponent.readExternal(element, it) }

        /** Replaces the `<envs>` of [element] with [envs]. */
        fun writeEnvs(element: Element, envs: Map<String, String>) {
            element.removeChildren(ENVS)
            EnvironmentVariablesComponent.writeExternal(element, envs)
        }

        private const val ENVS = "envs"
    }
}

/**
 * Rider: `.NET Project` (`DotNetProject`) and `.NET Executable` (`RunExe`) run configurations, ASP.NET Core included. Launch
 * profile configurations take their variables from `launchSettings.json` and can't be patched; use a `.NET Project`
 * configuration for them.
 */
class RiderEnvPatcher : XmlEnvPatcher(setOf("DotNetProject", "RunExe"))
