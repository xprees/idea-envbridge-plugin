package cz.xprees.secrets.editor

import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

/**
 * Entries of the IDE's right-click menu on a masked secret hint (`dev ••••••`). The hint carries which secret it is for
 * (key, environment, folder; never the value), and the value is fetched only when an entry is chosen.
 */
object SecretInlayMenu {
    /** Name of the hint payload with the secret's location. */
    const val PAYLOAD = "cz.xprees.secrets.secret"
    const val PROVIDER_ID = "cz.xprees.secrets.hints"

    data class Target(val key: String, val environment: String?, val folder: String)

    fun encode(key: String, environment: String?, folder: String): String = listOf(key, environment.orEmpty(), folder).joinToString(SEPARATOR)

    fun decode(text: String): Target? {
        val parts = text.split(SEPARATOR)
        if (parts.size != 3 || parts[0].isEmpty()) return null
        return Target(parts[0], parts[1].ifEmpty { null }, parts[2].ifEmpty { "/" })
    }

    /** The secret of the hint the menu was opened on, or null for other hints. */
    fun target(e: AnActionEvent): Target? {
        if (e.getData(InlayHintsProvider.PROVIDER_ID) != PROVIDER_ID) return null
        val payload = e.getData(InlayHintsProvider.INLAY_PAYLOADS)?.get(PAYLOAD) as? StringInlayActionPayload ?: return null
        return decode(payload.text)
    }

    private const val SEPARATOR = "\u0000"
}

abstract class SecretInlayAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && SecretInlayMenu.target(e) != null
    }
}

/** Shows the value in a hint next to the caret, until the next keystroke or caret move. */
class PreviewSecretValueInlayAction : SecretInlayAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val target = SecretInlayMenu.target(e) ?: return
        SecretValueActions.reveal(project, editor, target.key, target.environment, target.folder)
    }
}

class CopySecretValueInlayAction : SecretInlayAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = SecretInlayMenu.target(e) ?: return
        SecretValueActions.copy(project, target.key, target.environment, target.folder)
    }
}
