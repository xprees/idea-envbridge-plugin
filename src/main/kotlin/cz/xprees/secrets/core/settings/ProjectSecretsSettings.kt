package cz.xprees.secrets.core.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * The project binding chosen in the IDE: which connection, project, environment and folder references fall back to.
 * Stored in the workspace file (not shared through VCS): IDs and slugs only, no secrets. Anything left empty comes from the
 * provider's project file (`.infisical.yaml`) or the provider's defaults.
 */
@Service(Service.Level.PROJECT)
@State(name = "SecretsForInfisicalProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectSecretsSettings(private val project: Project) :
    SimplePersistentStateComponent<ProjectSecretsSettings.ProjectState>(ProjectState()) {

    class ProjectState : BaseState() {
        var connectionId by string()
        var projectId by string()

        /** The environment picked in the IDE; wins over the project file's branch mapping and default. */
        var environment by string()
        var path by string()

        /** Whether the provider's project file (`.infisical.yaml`) is read at all. */
        var useProjectFile by property(true)

        /**
         * When a project is bound: whether only that project may be used (references naming another project or connection are
         * refused, and the tool window shows only the bound project). Has no effect while nothing is bound.
         */
        var restrictToBoundProject by property(true)

        /** This project's production environments (comma-separated slugs); empty: the name pattern of the settings decides. */
        var productionEnvironments by string()

        /** Whether new terminal tabs of this project get secrets (opt-in). */
        var terminalEnabled by property(false)

        /** What new terminal tabs get: one per line, a scope import (a reference whose last segment is `*`) or `NAME=infisical://…`. */
        var terminalReferences by string(DEFAULT_TERMINAL_REFERENCES)
    }

    /**
     * Changes the state (BaseState property setters count the modification, so it gets saved) and tells listeners that the
     * binding may have changed.
     */
    fun update(block: ProjectState.() -> Unit) {
        state.block()
        if (!project.isDisposed) project.messageBus.syncPublisher(BINDING_TOPIC).bindingChanged()
    }

    /** The slugs of this project's production environments, or an empty set when the name pattern decides. */
    fun productionEnvironments(): Set<String> = parseEnvironments(state.productionEnvironments.orEmpty())

    fun interface BindingListener {
        fun bindingChanged()
    }

    companion object {
        const val DEFAULT_TERMINAL_REFERENCES = "infisical://*"

        /** Project-level topic: the binding (connection, project, environment, folder) may have changed. */
        @JvmField
        val BINDING_TOPIC: Topic<BindingListener> = Topic.create("EnvBridge binding", BindingListener::class.java)

        fun getInstance(project: Project): ProjectSecretsSettings = project.service()

        /** "prod, live" -> {prod, live}. */
        fun parseEnvironments(text: String): Set<String> = text.split(',', ' ', ';').map(String::trim).filter(String::isNotEmpty).toSet()
    }
}
