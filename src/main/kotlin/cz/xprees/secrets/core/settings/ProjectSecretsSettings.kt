package cz.xprees.secrets.core.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The project binding chosen in the IDE: which connection, project, environment and folder references fall back to.
 * Stored in the workspace file (not shared through VCS): IDs and slugs only, no secrets. Anything left empty comes from the
 * provider's project file (`.infisical.yaml`) or the provider's defaults.
 */
@Service(Service.Level.PROJECT)
@State(name = "SecretsForInfisicalProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectSecretsSettings : SimplePersistentStateComponent<ProjectSecretsSettings.ProjectState>(ProjectState()) {

    class ProjectState : BaseState() {
        var connectionId by string()
        var projectId by string()

        /** The environment picked in the IDE; wins over the project file's branch mapping and default. */
        var environment by string()
        var path by string()

        /** Whether the provider's project file (`.infisical.yaml`) is read at all. */
        var useProjectFile by property(true)

        /** Whether new terminal tabs of this project get secrets (opt-in). */
        var terminalEnabled by property(false)

        /** What new terminal tabs get: one per line, a scope import (a reference whose last segment is `*`) or `NAME=infisical://…`. */
        var terminalReferences by string(DEFAULT_TERMINAL_REFERENCES)
    }

    /** Changes the state; BaseState property setters count the modification, so it gets saved. */
    fun update(block: ProjectState.() -> Unit) {
        state.block()
    }

    companion object {
        const val DEFAULT_TERMINAL_REFERENCES = "infisical://*"

        fun getInstance(project: Project): ProjectSecretsSettings = project.service()
    }
}
