package cz.xprees.secrets

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.ui.toolwindow.SecretsToolWindowPanel

/** The Secrets tool window's tree answers the IDE's standard shortcuts (from the keymap) with its own actions. */
class ToolWindowShortcutsTest : BasePlatformTestCase() {
    fun `test the tree carries the standard shortcuts`() {
        val panel = SecretsToolWindowPanel(project)
        try {
            val registered = ActionUtil.getActions(panel.tree).flatMap { it.shortcutSet.shortcuts.toList() }.toSet()
            val manager = ActionManager.getInstance()
            for (id in listOf(IdeActions.ACTION_COPY, IdeActions.ACTION_CUT, IdeActions.ACTION_PASTE, IdeActions.ACTION_DELETE, IdeActions.ACTION_NEW_ELEMENT, IdeActions.ACTION_RENAME)) {
                val standard = manager.getAction(id)?.shortcutSet?.shortcuts.orEmpty()
                if (standard.isEmpty()) continue // not in this keymap
                assertTrue("$id: ${standard.toList()}", standard.any { it in registered })
            }
        } finally {
            Disposer.dispose(panel)
        }
    }
}
