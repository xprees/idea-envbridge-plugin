package cz.xprees.secrets

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object SecretsIcons {
    /** The plugin's own key icon (deliberately not a provider's logo). */
    @JvmField
    val PROVIDER: Icon = IconLoader.getIcon("/icons/secrets.svg", SecretsIcons::class.java)

    @JvmField
    val TOOL_WINDOW: Icon = IconLoader.getIcon("/icons/secretsToolWindow.svg", SecretsIcons::class.java)
}
