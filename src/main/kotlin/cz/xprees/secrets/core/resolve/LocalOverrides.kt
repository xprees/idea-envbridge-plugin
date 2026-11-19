package cz.xprees.secrets.core.resolve

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.auth.CredentialVault

/**
 * Local values that replace what the secret manager returns for a variable, for this project and machine only (for example
 * your own database password while Infisical holds the team's). Stored in the IDE's credential store, never in files.
 * They apply to secrets only: a value typed into a run configuration still wins.
 */
@Service(Service.Level.PROJECT)
class LocalOverrides(private val project: Project) {
    @Volatile
    private var cached: Map<String, String>? = null

    private val owner: String get() = "project-${project.locationHash}"

    /** All overrides (variable name -> value). Reads the credential store the first time: call off the EDT. */
    fun all(): Map<String, String> = cached ?: load().also { cached = it }

    fun names(): Set<String> = all().keys

    fun set(name: String, value: String) = store(all() + (name to value))

    fun remove(name: String) = store(all() - name)

    private fun load(): Map<String, String> = CredentialVault.get(owner, SLOT)?.let { json ->
        runCatching { JsonParser.parseString(json).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrNull()
    }.orEmpty()

    private fun store(values: Map<String, String>) {
        CredentialVault.set(owner, SLOT, if (values.isEmpty()) null else JsonObject().apply { values.forEach(::addProperty) }.toString())
        cached = values
    }

    companion object {
        private const val SLOT = "local-overrides"

        fun getInstance(project: Project): LocalOverrides = project.service()
    }
}
