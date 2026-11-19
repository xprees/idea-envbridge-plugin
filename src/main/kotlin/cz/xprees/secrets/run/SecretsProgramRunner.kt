package cz.xprees.secrets.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Key
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.EnvResolution
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import java.util.Collections
import java.util.WeakHashMap

/**
 * Puts secrets into run configurations that reference them (`KEY=infisical://…` or a scope import in their environment
 * variables), for every executor (Run, Debug, Coverage, Profiler, …).
 *
 * Registered first among the program runners, it claims such a configuration, fetches fresh values in the background, makes an
 * in-memory **copy** of the configuration with the resolved variables and hands the copy to the runner the IDE would have used
 * otherwise. The configuration itself is never modified, so secret values can't end up in `workspace.xml` or `.run` files.
 * When anything can't be resolved, the launch is stopped (fail closed) and a notification offers to sign in, retry or run
 * without secrets this once.
 */
class SecretsProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (profile !is RunConfiguration || isResolvedCopy(profile)) return false
        val (_, envs) = EnvPatcher.find(profile) ?: return false
        if (!LaunchSecretResolver.getInstance(profile.project).hasReferences(envs)) return false
        return delegate(executorId, profile) != null
    }

    // Runner-specific settings belong to the runner that really launches the configuration
    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings? = null

    @Suppress("UNCHECKED_CAST")
    override fun getSettingsEditor(executor: Executor, configuration: RunConfiguration): SettingsEditor<RunnerSettings>? =
        delegate(executor.id, configuration)?.getSettingsEditor(executor, configuration) as SettingsEditor<RunnerSettings>?

    override fun getStartActionText(executor: Executor, configuration: RunConfiguration): String? =
        delegate(executor.id, configuration)?.getStartActionText(executor, configuration) ?: super.getStartActionText(executor, configuration)

    override fun execute(environment: ExecutionEnvironment) {
        val profile = environment.runProfile as RunConfiguration
        val (patcher, envs) = EnvPatcher.find(profile) ?: throw ExecutionException(SecretsBundle.message("run.unsupported"))
        val resolver = LaunchSecretResolver.getInstance(environment.project)
        val plan = resolver.plan(envs)

        object : Task.Backgroundable(environment.project, SecretsBundle.message("run.fetching", profile.name), true) {
            private var result: EnvResolution.Result? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = resolver.resolve(plan)
            }

            override fun onSuccess() {
                val resolved = result ?: return
                if (resolved.warnings.isNotEmpty()) SecretsNotifications.launchWarnings(environment.project, profile.name, resolved.warnings)
                launch(environment, patcher.copyWith(profile, resolved.environment), resolved.secretValues)
            }

            override fun onThrowable(error: Throwable) {
                val failure = error as? SecretProviderException
                    ?: SecretProviderException(SecretProviderException.Kind.SERVER, error.message ?: error.javaClass.simpleName, error)
                if (failure.kind == SecretProviderException.Kind.CANCELLED) return
                if (error !is SecretProviderException) LOG.warn("Resolving secrets for '${profile.name}' failed", error)
                SecretsNotifications.launchFailed(
                    project = environment.project,
                    configurationName = profile.name,
                    failure = failure,
                    retry = { execute(environment) },
                    runWithoutSecrets = { launch(environment, patcher.copyWith(profile, plan.explicit), emptySet()) },
                )
            }

            override fun onCancel() = Unit
        }.queue()
    }

    private fun launch(environment: ExecutionEnvironment, copy: RunProfile, secretValues: Set<String>) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        markResolved(copy)
        val delegate = delegate(environment.executor.id, copy)
        if (delegate == null) {
            ExecutionUtil.handleExecutionError(environment, ExecutionException(SecretsBundle.message("run.no.runner")))
            return
        }
        val builder = ExecutionEnvironmentBuilder(environment)
        environment.runnerAndConfigurationSettings?.let { builder.runnerAndSettings(delegate, it) } ?: builder.runner(delegate)
        val delegated = builder.runProfile(copy).build(environment.callback)
        delegated.putUserData(SECRET_VALUES, secretValues)
        try {
            delegate.execute(delegated)
        } catch (e: ExecutionException) {
            ExecutionUtil.handleExecutionError(delegated, e)
        }
    }

    private fun delegate(executorId: String, profile: RunProfile): ProgramRunner<*>? =
        ProgramRunner.PROGRAM_RUNNER_EP.extensionList.firstOrNull { runner ->
            runner !is SecretsProgramRunner && runCatching { runner.canRun(executorId, profile) }.getOrDefault(false)
        }

    companion object {
        const val RUNNER_ID = "SecretsForInfisical"
        private val LOG = logger<SecretsProgramRunner>()

        /** Values that came from a secret manager, on the environment of the delegated launch (for console masking). */
        val SECRET_VALUES: Key<Set<String>> = Key.create("cz.xprees.secrets.values")

        /** The copies this runner launched (identity, weakly held): it must not claim them a second time. */
        private val resolvedCopies: MutableSet<RunProfile> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        private fun markResolved(profile: RunProfile) {
            resolvedCopies += profile
        }

        private fun isResolvedCopy(profile: RunProfile): Boolean = profile in resolvedCopies
    }
}
