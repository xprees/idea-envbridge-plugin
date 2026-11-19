package cz.xprees.secrets.run.docker

import com.intellij.docker.DockerDeploymentConfiguration
import com.intellij.docker.agent.settings.DockerEnvVarImpl
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.util.xmlb.XmlSerializer
import cz.xprees.secrets.run.EnvPatcher
import org.jdom.Element
import org.jdom.Verifier

/**
 * Docker run configurations: Docker Image, Dockerfile and Docker Compose. Their variables become the container's environment
 * (`docker run -e`) or, for Compose, the environment of the `docker compose` process, which feeds `${VAR}` interpolation and
 * pass-through entries (`environment: [VAR]`) in the Compose file. Nothing is written to disk.
 *
 * The Docker settings are reached through the configuration's public XML form (`writeExternal` / `readExternal`), which the
 * plugin only ever applies to its in-memory copy.
 */
class DockerEnvPatcher : EnvPatcher {
    override fun read(profile: RunProfile): Map<String, String>? {
        if (!isDocker(profile)) return null
        return settings(serialize(profile as RunConfiguration)).envVars.associate { it.name.orEmpty() to it.value.orEmpty() }
    }

    override fun copyWith(profile: RunProfile, envs: Map<String, String>): RunProfile {
        // XML can't carry every character; refuse without echoing the value (it may be a secret)
        envs.forEach { (name, value) ->
            require(Verifier.checkCharacterData(name) == null && Verifier.checkCharacterData(value) == null) {
                "The value of $name can't be passed to a Docker run configuration (unsupported characters)"
            }
        }
        val copy = (profile as RunConfiguration).clone()
        val external = serialize(copy)
        val deployment = external.getChild(DEPLOYMENT) ?: Element(DEPLOYMENT).also(external::addContent)
        val settings = DockerDeploymentConfiguration().apply {
            loadState(settings(external))
            envVars = envs.map { (name, value) -> DockerEnvVarImpl(name, value) }.toMutableList()
        }
        deployment.removeChild(SETTINGS)
        deployment.addContent(Element(SETTINGS).also { XmlSerializer.serializeInto(settings, it) })
        copy.readExternal(external)
        return copy
    }

    private fun isDocker(profile: RunProfile): Boolean = profile is RunConfiguration && profile.type.id == DOCKER_TYPE_ID

    private fun serialize(configuration: RunConfiguration): Element = Element("configuration").also(configuration::writeExternal)

    private fun settings(external: Element): DockerDeploymentConfiguration =
        external.getChild(DEPLOYMENT)?.getChild(SETTINGS)?.let { XmlSerializer.deserialize(it, DockerDeploymentConfiguration::class.java) }
            ?: DockerDeploymentConfiguration()

    companion object {
        /** The run configuration type of Docker Image, Dockerfile and Docker Compose configurations. */
        const val DOCKER_TYPE_ID = "docker-deploy"
        private const val DEPLOYMENT = "deployment"
        private const val SETTINGS = "settings"
    }
}
