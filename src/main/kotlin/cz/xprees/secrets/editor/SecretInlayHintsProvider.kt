package cz.xprees.secrets.editor

import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.ui.toolwindow.SecretLocation

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.KeysIndex

/**
 * A masked hint after each secret: `dev ••••••` (the environment the value comes from). The value itself never appears;
 * the gutter icon reveals it on demand. Toggle in Settings › Editor › Inlay Hints.
 */
class SecretInlayHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val snapshot = KeysIndex.getInstance(file.project).current() ?: return null
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                // In .env files a reference (or binding comment) stands for a value, so it gets the shadow value too
                val dotEnv = DotEnvLines.isDotEnv(file.name)
                for (found in EditorReferences.of(file)) {
                    // Elsewhere references show their own target already; hint the code that reads variables
                    if (found.kind == EnvReferences.Kind.REFERENCE && !dotEnv) continue
                    val resolved = EditorReferences.resolve(file.project, found, snapshot) ?: continue
                    if (resolved.info == null) continue
                    val environment = found.reference?.environment ?: snapshot.boundEnvironment
                    // A click opens the secret in the Secrets tool window
                    val click = SecretLocation.of(file.project, found.name, found.reference)
                        ?.let { InlayActionData(StringInlayActionPayload(it.encode()), ShowSecretInToolWindow.HANDLER_ID) }
                    sink.addPresentation(InlineInlayPosition(found.end, true), hintFormat = HintFormat.default, tooltip = SecretsBundle.message("inlay.click.tooltip")) {
                        text("$environment ${SecretValue.MASK}", click)
                    }
                }
            }
        }
    }
}
