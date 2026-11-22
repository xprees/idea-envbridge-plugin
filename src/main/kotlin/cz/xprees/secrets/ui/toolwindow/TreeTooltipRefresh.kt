package cz.xprees.secrets.ui.toolwindow

import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JTree

/**
 * Refreshes a tree's tooltip when what it says changes while the mouse rests on a row (details read in the background
 * arriving after "Loading details…" was shown), so the user does not have to move the mouse to see them.
 */
internal object TreeTooltipRefresh {
    /**
     * Where to re-ask for the tooltip: [mouse] (in [tree]'s coordinates) when it is still over the row of [node], else
     * null (the mouse left the tree or moved to another row, and that row's tooltip is not [node]'s to change).
     */
    fun pointToRefresh(tree: JTree, mouse: Point?, node: Any): Point? {
        if (mouse == null) return null
        val hovered = tree.getPathForLocation(mouse.x, mouse.y)?.lastPathComponent ?: return null
        return if (hovered === node) mouse else null
    }

    /**
     * On the EDT: when the mouse is still over [node]'s row, sends the tree a synthetic mouse move at the same spot. Swing's
     * ToolTipManager and IntelliJ's IdeTooltipManager both react to it by asking for the tooltip text again: a visible
     * tooltip is replaced in place, one about to show will show the new text. Returns whether it was sent.
     */
    fun refreshIfHovered(tree: JTree, node: Any): Boolean {
        if (!tree.isShowing) return false
        val point = pointToRefresh(tree, tree.mousePosition, node) ?: return false
        tree.dispatchEvent(
            MouseEvent(tree, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false),
        )
        return true
    }
}
