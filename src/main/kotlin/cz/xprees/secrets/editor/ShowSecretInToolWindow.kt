package cz.xprees.secrets.editor

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.ui.toolwindow.SecretLocation
import cz.xprees.secrets.ui.toolwindow.SecretsToolWindowFactory

/** Opens a secret from the editor in the Secrets tool window, selected (masked hint click, gutter menu). */
object ShowSecretInToolWindow {
    const val HANDLER_ID = "cz.xprees.secrets.showSecret"

    fun show(project: Project, key: String?, reference: SecretReference?) {
        val location = SecretLocation.of(project, key, reference)
        if (location == null) SecretsToolWindowFactory.show(project) else SecretsToolWindowFactory.select(project, location)
    }
}

/** Click on a masked secret hint (`dev ••••••`). */
class ShowSecretInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val project = e.editor.project ?: return
        val location = (payload as? StringInlayActionPayload)?.text?.let(SecretLocation::decode)
        if (location == null) SecretsToolWindowFactory.show(project) else SecretsToolWindowFactory.select(project, location)
    }
}
