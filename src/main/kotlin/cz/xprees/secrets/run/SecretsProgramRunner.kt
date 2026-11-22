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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.EnvResolution
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.ProdGuard
import cz.xprees.secrets.core.resolve.SecretBindingService
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
 *
 * It also claims configurations whose env files use env-spec decorators ([EnvFileReferences.usesDecorators]) and applies
 * them ([LaunchChecks]): a required key without a value stops the launch, sensitive values are masked in the console,
 * `@internal` keys are left out of the process and values that don't fit their `@type` are reported (the launch goes on).
 *
 * In the varlock compatibility mode it also claims configurations whose env files hold varlock values
 * ([EnvFileReferences.usesVarlock]) and resolves them ([VarlockLaunch]) after the references; a refused or unsupported function
 * stops the launch before anything is fetched or started.
 */
class SecretsProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (profile !is RunConfiguration || isResolvedCopy(profile)) return false
        val (_, envs) = EnvPatcher.find(profile) ?: return false
        val resolver = LaunchSecretResolver.getInstance(profile.project)
        if (!resolver.hasReferences(envs) && !EnvFileReferences.hasReferences(profile, resolver) && !EnvFileReferences.usesDecorators(profile) &&
            !EnvFileReferences.usesVarlock(profile)
        ) {
            return false
        }
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
        val (patcher, configured) = EnvPatcher.find(profile) ?: throw ExecutionException(SecretsBundle.message("run.unsupported"))
        val resolver = LaunchSecretResolver.getInstance(environment.project)
        val override = environment.getUserData(ENVIRONMENT_OVERRIDE)
        fun copyWith(values: Map<String, String>, detach: Boolean): RunProfile =
            patcher.copyWith(profile, values).also { if (detach) EnvFileReferences.detach(it) }

        object : Task.Backgroundable(environment.project, SecretsBundle.message("run.fetching", profile.name), true) {
            private var prepared: EnvFileReferences.Prepared? = null
            private var plan: EnvResolution.Plan? = null
            private var result: EnvResolution.Result? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Env files with references (or layered ones) are read here and detached from the copy; values set on the
                // configuration win
                val files = EnvFileReferences.prepare(profile, configured, resolver, override).also { prepared = it }
                // Refused varlock functions (exec(), keychain(), …) stop the launch before anything is fetched
                VarlockLaunch.checkProblems(files.varlock)
                val plan = resolver.plan(files.envs).also { plan = it }
                val resolved = resolver.resolve(plan, environment = override)
                result = VarlockLaunch.resolve(environment.project, files, resolved, resolver, override ?: SecretBindingService.environmentOverride(configured))
            }

            override fun onSuccess() {
                val resolved = result ?: return
                val files = prepared ?: return
                val outcome = LaunchChecks.apply(files, resolved, configured)
                if (outcome.missingRequired.isNotEmpty()) {
                    SecretsNotifications.requiredMissing(environment.project, profile.name, outcome.missingRequired) { execute(environment) }
                    return
                }
                if (!confirmGuardedEnvironments(environment, profile.name, resolved.environments)) return
                if (resolved.warnings.isNotEmpty()) SecretsNotifications.launchWarnings(environment.project, profile.name, resolved.warnings)
                if (outcome.typeWarnings.isNotEmpty()) {
                    SecretsNotifications.launchWarnings(environment.project, profile.name, outcome.typeWarnings, "envspec.launch.type.title")
                }
                launch(environment, copyWith(outcome.environment, files.detach), outcome.maskedValues, resolved.summary())
            }

            override fun onThrowable(error: Throwable) {
                val failure = error as? SecretProviderException
                    ?: SecretProviderException(SecretProviderException.Kind.SERVER, error.message ?: error.javaClass.simpleName, error)
                if (failure.kind == SecretProviderException.Kind.CANCELLED) return
                if (failure is VarlockLaunchException) {
                    SecretsNotifications.varlockFailed(environment.project, profile.name, failure) { execute(environment) }
                    return
                }
                if (error !is SecretProviderException) LOG.warn("Resolving secrets for '${profile.name}' failed", error)
                val explicit = plan?.explicit ?: resolver.plan(configured).explicit
                val detach = plan != null && prepared?.detach == true
                SecretsNotifications.launchFailed(
                    project = environment.project,
                    configurationName = profile.name,
                    failure = failure,
                    retry = { execute(environment) },
                    runWithoutSecrets = { launch(environment, copyWith(explicit, detach), emptySet(), null) },
                )
            }

            override fun onCancel() = Unit
        }.queue()
    }

    private fun launch(environment: ExecutionEnvironment, copy: RunProfile, secretValues: Set<String>, summary: String?) {
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
        delegated.putUserData(LAUNCH_SUMMARY, summary)
        try {
            delegate.execute(delegated)
        } catch (e: ExecutionException) {
            ExecutionUtil.handleExecutionError(delegated, e)
        }
    }

    /** Asks before secrets from a production-like environment (see [ProdGuard]) are handed to a process. */
    private fun confirmGuardedEnvironments(environment: ExecutionEnvironment, name: String, environments: Set<String>): Boolean {
        val guarded = environments.filter { ProdGuard.needsConfirmation(environment.project, it) }
        if (guarded.isEmpty()) return true
        return Messages.showYesNoDialog(
            environment.project,
            SecretsBundle.message("prod.guard.message", name, guarded.joinToString()),
            SecretsBundle.message("prod.guard.title"),
            SecretsBundle.message("prod.guard.run"),
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    private fun delegate(executorId: String, profile: RunProfile): ProgramRunner<*>? =
        ProgramRunner.PROGRAM_RUNNER_EP.extensionList.firstOrNull { runner ->
            runner !is SecretsProgramRunner && runCatching { runner.canRun(executorId, profile) }.getOrDefault(false)
        }

    companion object {
        const val RUNNER_ID = "SecretsForInfisical"
        private val LOG = logger<SecretsProgramRunner>()

        /** Set on an [ExecutionEnvironment] to resolve this launch against another environment ("Run with environment…"). */
        val ENVIRONMENT_OVERRIDE: Key<String> = Key.create("cz.xprees.secrets.environmentOverride")

        /** Values that came from a secret manager, on the environment of the delegated launch (for console masking). */
        val SECRET_VALUES: Key<Set<String>> = Key.create("cz.xprees.secrets.values")

        /** What the delegated launch got from secret managers (names and scopes, no values), printed in its console. */
        val LAUNCH_SUMMARY: Key<String> = Key.create("cz.xprees.secrets.summary")

        /** The copies this runner launched (identity, weakly held): it must not claim them a second time. */
        private val resolvedCopies: MutableSet<RunProfile> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        private fun markResolved(profile: RunProfile) {
            resolvedCopies += profile
        }

        private fun isResolvedCopy(profile: RunProfile): Boolean = profile in resolvedCopies
    }
}
