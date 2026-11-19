package cz.xprees.secrets

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import cz.xprees.secrets.ui.settings.SecretsConfigurable
import org.assertj.core.api.Assertions.assertThat

class SettingsLayoutTest : BasePlatformTestCase() {
    /** Long texts must wrap: a page wider than a small Settings window hides the drop-down buttons on the right. */
    fun `test settings page stays narrow`() {
        val configurable = SecretsConfigurable(project)
        try {
            val component = configurable.createComponent()!!
            configurable.reset()
            val width = component.preferredSize.width
            println("SETTINGS_PREFERRED_WIDTH=$width")
            assertThat(width).isLessThanOrEqualTo(JBUI.scale(640))
        } finally {
            configurable.disposeUIResources()
        }
    }
}
