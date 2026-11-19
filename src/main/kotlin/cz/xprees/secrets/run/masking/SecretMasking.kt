package cz.xprees.secrets.run.masking

import com.intellij.execution.ExecutionListener
import com.intellij.execution.filters.ConsoleInputFilterProvider
import com.intellij.execution.filters.InputFilter
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.run.SecretsProgramRunner
import java.util.concurrent.ConcurrentHashMap

/**
 * The secret values handed to processes that are still running in this project. Run and Debug consoles replace them with
 * a mask while their process runs. Values shorter than [MIN_LENGTH] are not masked (they would hide ordinary output such as
 * `true` or `8080`).
 */
@Service(Service.Level.PROJECT)
class SecretMaskingService {
    private val active = ConcurrentHashMap<ProcessHandler, Set<String>>()

    fun register(handler: ProcessHandler, values: Set<String>) {
        val maskable = values.filter { it.length >= MIN_LENGTH }.toSet()
        if (maskable.isNotEmpty()) active[handler] = maskable
    }

    fun unregister(handler: ProcessHandler) {
        active.remove(handler)
    }

    /** Longest first, so a value containing another is masked as a whole. */
    fun values(): List<String> = active.values.flatten().distinct().sortedByDescending { it.length }

    /** [text] with every active secret value replaced by the mask, or null when it contains none. */
    fun mask(text: String): String? {
        var result = text
        var changed = false
        for (value in values()) {
            if (result.contains(value)) {
                result = result.replace(value, SecretValue.MASK)
                changed = true
            }
        }
        return if (changed) result else null
    }

    companion object {
        const val MIN_LENGTH = 4

        fun getInstance(project: Project): SecretMaskingService = project.service()
    }
}

/** Tracks the launches this plugin resolved secrets for (their environment carries the values). */
class SecretMaskingExecutionListener(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val values = env.getUserData(SecretsProgramRunner.SECRET_VALUES) ?: return
        SecretMaskingService.getInstance(project).register(handler, values)
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        SecretMaskingService.getInstance(project).unregister(handler)
    }
}

/** Masks secret values in Run, Debug and other consoles of the project (when enabled in the settings). */
class SecretMaskingFilterProvider : ConsoleInputFilterProvider {
    override fun getDefaultFilters(project: Project): Array<InputFilter> = arrayOf(
        InputFilter { text, contentType ->
            if (!SecretsAppSettings.getInstance().state.maskConsoleOutput) return@InputFilter null
            SecretMaskingService.getInstance(project).mask(text)?.let { listOf(Pair.create(it, contentType as ConsoleViewContentType)) }
        },
    )
}
