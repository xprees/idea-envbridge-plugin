package cz.xprees.secrets

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.ui.toolwindow.TreeTooltipRefresh
import org.assertj.core.api.Assertions.assertThat
import java.awt.Point
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** The tool window's tooltip refreshes when lazily read details arrive only while the mouse is still over that row. */
class TreeTooltipRefreshPlatformTest : BasePlatformTestCase() {
    private val root = DefaultMutableTreeNode("root")
    private val first = DefaultMutableTreeNode("FIRST_SECRET")
    private val second = DefaultMutableTreeNode("SECOND_SECRET")
    private lateinit var tree: JTree

    override fun setUp() {
        super.setUp()
        root.add(first)
        root.add(second)
        tree = JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            setSize(300, 200)
            doLayout()
        }
    }

    private fun centerOf(row: Int): Point = tree.getRowBounds(row).let { Point(it.x + 2, it.y + it.height / 2) }

    fun testRefreshesWhenMouseIsStillOverTheSameRow() {
        val point = centerOf(0)
        assertThat(TreeTooltipRefresh.pointToRefresh(tree, point, first)).isEqualTo(point)
    }

    fun testNoRefreshWhenMouseMovedToAnotherRow() {
        assertThat(TreeTooltipRefresh.pointToRefresh(tree, centerOf(1), first)).isNull()
    }

    fun testNoRefreshWhenMouseIsOutsideTheTreeOrOverNoRow() {
        assertThat(TreeTooltipRefresh.pointToRefresh(tree, null, first)).isNull()
        assertThat(TreeTooltipRefresh.pointToRefresh(tree, Point(5, 190), first)).isNull()
    }

    fun testNothingIsSentToATreeThatIsNotShowing() {
        assertThat(TreeTooltipRefresh.refreshIfHovered(tree, first)).isFalse()
    }
}
