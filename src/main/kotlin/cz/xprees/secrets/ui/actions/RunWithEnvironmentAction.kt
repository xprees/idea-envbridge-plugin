package cz.xprees.secrets.ui.actions

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.run.EnvFileReferences
import cz.xprees.secrets.run.EnvPatcher
import cz.xprees.secrets.run.SecretsProgramRunner
import javax.swing.Icon

/**
 * Runs the selected run configuration once against another environment, without changing the configuration or the
 * project binding. Available for configurations that reference secrets.
 */
class RunWithEnvironmentAction : DumbAwareAction(AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val settings = project?.let { RunManager.getInstance(it).selectedConfiguration }
        val configuration = settings?.configuration
        val envs = configuration?.let { EnvPatcher.find(it)?.second }
        e.presentation.isEnabled = envs != null && LaunchSecretResolver.getInstance(project).let { resolver ->
            resolver.hasReferences(envs) || EnvFileReferences.hasReferences(configuration, resolver)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RunManager.getInstance(project).selectedConfiguration ?: return
        val environments = KeysIndex.getInstance(project).current()?.environments?.keys?.toList().orEmpty()
        if (environments.isEmpty()) return
        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<String>(SecretsBundle.message("run.with.environment.title", settings.name), environments) {
                override fun getIconFor(value: String): Icon? = if (ProdGuard.matches(value)) AllIcons.General.Warning else null

                override fun isSpeedSearchEnabled() = true

                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                    doFinalStep {
                        val environment = ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)?.build()
                            ?: return@doFinalStep
                        environment.putUserData(SecretsProgramRunner.ENVIRONMENT_OVERRIDE, selectedValue)
                        ProgramRunnerUtil.executeConfiguration(environment, true, true)
                    }
                    return PopupStep.FINAL_CHOICE
                }
            },
        )
        val component = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component) else popup.showCenteredInCurrentWindow(project)
    }
}
