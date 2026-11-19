package cz.xprees.secrets.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.util.concurrency.AppExecutorUtil
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

/**
 * Copies secrets to the system clipboard (not through the IDE's CopyPasteManager, so they don't land in the IDE's clipboard
 * history) and removes them again after the configured delay, if nothing replaced them in the meantime.
 */
object SecretClipboard {
    fun copy(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        val seconds = SecretsAppSettings.getInstance().state.clipboardClearSeconds.toLong()
        if (seconds <= 0) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater({
                val current = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
                if (current == text) clipboard.setContents(StringSelection(""), null)
            }, ModalityState.any())
        }, seconds, TimeUnit.SECONDS)
    }
}
