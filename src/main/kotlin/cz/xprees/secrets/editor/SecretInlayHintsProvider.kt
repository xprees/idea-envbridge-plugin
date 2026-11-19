package cz.xprees.secrets.editor

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
                for (found in EditorReferences.of(file)) {
                    // References show their own target already; hint the code that reads variables
                    if (found.kind == EnvReferences.Kind.REFERENCE) continue
                    val resolved = EditorReferences.resolve(file.project, found, snapshot) ?: continue
                    if (resolved.info == null) continue
                    sink.addPresentation(InlineInlayPosition(found.end, true), hintFormat = HintFormat.default) {
                        text("${snapshot.boundEnvironment} ${SecretValue.MASK}")
                    }
                }
            }
        }
    }
}
