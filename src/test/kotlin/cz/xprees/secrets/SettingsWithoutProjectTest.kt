package cz.xprees.secrets

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.ui.settings.SecretsConfigurable

/** The settings page: opened from the Welcome screen it runs against the IDE's default project. */
class SettingsWithoutProjectTest : BasePlatformTestCase() {
    fun `test settings page works for the default project`() = check(ProjectManager.getInstance().defaultProject)

    fun `test settings page works for a project`() = check(project)

    fun `test a binding to a removed connection doesn't count as a change`() {
        val settings = ProjectSecretsSettings.getInstance(project)
        settings.update { connectionId = "removed-connection"; projectId = "p1" }
        try {
            check(project)
        } finally {
            settings.update { connectionId = null; projectId = null }
        }
    }

    private fun check(project: Project) {
        val configurable = SecretsConfigurable(project)
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            // Nothing changed yet: Apply stays disabled
            assertFalse(configurable.isModified)
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }
    }
}
