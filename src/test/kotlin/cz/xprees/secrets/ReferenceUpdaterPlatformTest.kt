package cz.xprees.secrets

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.ui.secrets.ReferenceUpdater
import org.assertj.core.api.Assertions.assertThat

class ReferenceUpdaterPlatformTest : BasePlatformTestCase() {
    private val connection = SecretConnection("refs-${System.nanoTime()}", InfisicalProvider.ID, "Main", "https://app.infisical.com", InfisicalAuthMethods.BROWSER)

    override fun setUp() {
        super.setUp()
        SecretsAppSettings.getInstance().setConnections(listOf(connection))
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "p1"
            environment = "dev"
            path = null
            useProjectFile = false
            terminalReferences = "infisical://*\nDB=infisical://DB_PASSWORD"
        }
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; terminalReferences = null }
            RunManager.getInstance(project).allSettings.forEach { RunManager.getInstance(project).removeConfiguration(it) }
        } finally {
            super.tearDown()
        }
    }

    fun `test moved secrets' references are updated in env files, run configurations and the terminal, undoably`() {
        val env = myFixture.addFileToProject(
            ".env",
            "DB_PASSWORD= # infisical://DB_PASSWORD\nAPI_KEY=infisical://API_KEY\nPROD=infisical://prod:DB_PASSWORD\n",
        ).virtualFile
        val runManager = RunManager.getInstance(project)
        val app = runManager.createConfiguration("App", ApplicationConfigurationType.getInstance().configurationFactories[0])
        (app.configuration as ApplicationConfiguration).envs = linkedMapOf("DB" to "infisical://DB_PASSWORD", "PLAIN" to "x")
        runManager.addConfiguration(app)
        // reads prod for its references: its DB_PASSWORD isn't the one that moved
        val prodApp = runManager.createConfiguration("Prod App", ApplicationConfigurationType.getInstance().configurationFactories[0])
        (prodApp.configuration as ApplicationConfiguration).envs = linkedMapOf("DB" to "infisical://DB_PASSWORD", "ENVBRIDGE_ENVIRONMENT" to "prod")
        runManager.addConfiguration(prodApp)

        val updater = ReferenceUpdater(project)
        val plan = updater.plan(connection, listOf("DB_PASSWORD"), SecretScope("p1", "dev", "/"), SecretScope("p1", "dev", "/backend"))
        assertThat(plan.references).isEqualTo(3)
        updater.apply(plan)

        val document = FileDocumentManager.getInstance().getDocument(env)!!
        assertThat(document.text).isEqualTo("DB_PASSWORD= # infisical://backend/DB_PASSWORD\nAPI_KEY=infisical://API_KEY\nPROD=infisical://prod:DB_PASSWORD\n")
        assertThat((app.configuration as ApplicationConfiguration).envs).containsEntry("DB", "infisical://backend/DB_PASSWORD").containsEntry("PLAIN", "x")
        assertThat((prodApp.configuration as ApplicationConfiguration).envs).containsEntry("DB", "infisical://DB_PASSWORD")
        assertThat(ProjectSecretsSettings.getInstance(project).state.terminalReferences).isEqualTo("infisical://*\nDB=infisical://backend/DB_PASSWORD")

        // Edit › Undo puts everything back, Redo makes it again
        TestDialogManager.setTestDialog(TestDialog.OK)
        val undo = UndoManager.getInstance(project)
        assertThat(undo.isUndoAvailable(null)).isTrue()
        undo.undo(null)
        assertThat(document.text).contains("DB_PASSWORD= # infisical://DB_PASSWORD\n")
        assertThat((app.configuration as ApplicationConfiguration).envs).containsEntry("DB", "infisical://DB_PASSWORD")
        assertThat(ProjectSecretsSettings.getInstance(project).state.terminalReferences).isEqualTo("infisical://*\nDB=infisical://DB_PASSWORD")
        undo.redo(null)
        assertThat(document.text).contains("infisical://backend/DB_PASSWORD")
        assertThat((app.configuration as ApplicationConfiguration).envs).containsEntry("DB", "infisical://backend/DB_PASSWORD")
    }

    fun `test a renamed secret's references take the new name everywhere, undoably as one command`() {
        val env = myFixture.addFileToProject(
            ".env",
            "DB_PASSWORD= # infisical://DB_PASSWORD\nOTHER=infisical://backend/DB_PASSWORD\nPROD=infisical://prod:DB_PASSWORD\n",
        ).virtualFile
        val runManager = RunManager.getInstance(project)
        val app = runManager.createConfiguration("App", ApplicationConfigurationType.getInstance().configurationFactories[0])
        (app.configuration as ApplicationConfiguration).envs = linkedMapOf("DB" to "infisical://DB_PASSWORD", "PLAIN" to "x")
        runManager.addConfiguration(app)

        val updater = ReferenceUpdater(project)
        val plan = updater.planRename(connection, SecretScope("p1", "dev", "/"), "DB_PASSWORD", "DATABASE_PASSWORD")
        assertThat(plan.references).isEqualTo(3)
        assertThat(plan.summary()).contains("new name")
        updater.apply(plan)

        val document = FileDocumentManager.getInstance().getDocument(env)!!
        assertThat(document.text)
            .isEqualTo("DB_PASSWORD= # infisical://DATABASE_PASSWORD\nOTHER=infisical://backend/DB_PASSWORD\nPROD=infisical://prod:DB_PASSWORD\n")
        assertThat((app.configuration as ApplicationConfiguration).envs).containsEntry("DB", "infisical://DATABASE_PASSWORD").containsEntry("PLAIN", "x")
        assertThat(ProjectSecretsSettings.getInstance(project).state.terminalReferences).isEqualTo("infisical://*\nDB=infisical://DATABASE_PASSWORD")

        TestDialogManager.setTestDialog(TestDialog.OK)
        val undo = UndoManager.getInstance(project)
        assertThat(undo.isUndoAvailable(null)).isTrue()
        undo.undo(null)
        assertThat(document.text).startsWith("DB_PASSWORD= # infisical://DB_PASSWORD\n")
        assertThat((app.configuration as ApplicationConfiguration).envs).containsEntry("DB", "infisical://DB_PASSWORD")
        assertThat(ProjectSecretsSettings.getInstance(project).state.terminalReferences).isEqualTo("infisical://*\nDB=infisical://DB_PASSWORD")
        // one command: nothing of it is left to undo
        assertThat(document.text).doesNotContain("DATABASE_PASSWORD")
        undo.redo(null)
        assertThat(document.text).contains("infisical://DATABASE_PASSWORD")
        assertThat((app.configuration as ApplicationConfiguration).envs).containsEntry("DB", "infisical://DATABASE_PASSWORD")
    }

    fun `test nothing to update makes no command`() {
        val plan = ReferenceUpdater(project).plan(connection, listOf("NOT_USED"), SecretScope("p1", "dev", "/"), SecretScope("p1", "dev", "/x"))
        assertThat(plan.isEmpty).isTrue()
        ReferenceUpdater(project).apply(plan)
        assertThat(UndoManager.getInstance(project).isUndoAvailable(null)).isFalse()
    }
}
