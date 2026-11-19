package cz.xprees.secrets.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class SecretsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SecretsToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "Secrets"

        fun show(project: Project) {
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.activate(null)
        }

        /** Opens the Secrets tool window and selects [location] (a secret, or a folder when it has no key). */
        fun select(project: Project, location: SecretLocation) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                toolWindow.contentManager.contents.firstNotNullOfOrNull { it.component as? SecretsToolWindowPanel }?.select(location)
            }
        }
    }
}
