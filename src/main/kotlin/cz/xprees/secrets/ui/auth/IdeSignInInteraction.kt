package cz.xprees.secrets.ui.auth

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.provider.SignInInteraction

/**
 * A sign-in flow's link to the IDE while it runs under [indicator]: the system browser, the progress text, cancellation, and
 * modal choices ([ChooseItemDialog], shown on the EDT in the indicator's modality while the flow's thread waits).
 */
class IdeSignInInteraction(private val project: Project?, private val indicator: ProgressIndicator) : SignInInteraction {
    override fun openBrowser(url: String) = BrowserUtil.browse(url)

    override fun isCancelled() = indicator.isCanceled

    override fun status(text: String) {
        indicator.text = text
    }

    override val canChoose: Boolean get() = true

    override fun <T : Any> choose(title: String, items: List<T>, text: (T) -> String, selected: T?): T? {
        var chosen: T? = null
        val ask = Runnable {
            val dialog = ChooseItemDialog(project, title, items, text, selected)
            if (dialog.showAndGet()) chosen = dialog.chosen
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) ask.run() else app.invokeAndWait(ask, indicator.modalityState)
        return chosen
    }
}
