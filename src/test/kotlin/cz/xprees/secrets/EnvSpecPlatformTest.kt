package cz.xprees.secrets

import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.EnvSpecDecoratorCompletionContributor
import cz.xprees.secrets.editor.EnvSpecInspection
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.run.EnvFileReferences
import cz.xprees.secrets.run.LaunchChecks
import cz.xprees.secrets.run.SecretsProgramRunner
import cz.xprees.secrets.run.masking.SecretMaskingService
import cz.xprees.secrets.ui.settings.SecretsConfigurable
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** env-spec decorators at launch (required, sensitive, internal, type), layered env files, the editor inspection and completion. */
class EnvSpecPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockInfisical
    private lateinit var connection: SecretConnection
    private val app get() = SecretsAppSettings.getInstance().state

    override fun setUp() {
        super.setUp()
        mock = MockInfisical()
        connection = SecretConnection(
            "envspec-${System.nanoTime()}", InfisicalProvider.ID, "Test", mock.baseUrl, InfisicalAuthMethods.MACHINE_IDENTITY,
            mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to mock.clientId),
        )
        CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET), mock.clientSecret)
        SecretsAppSettings.getInstance().setConnections(listOf(connection))
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "p1"
            environment = "dev"
            path = null
            useProjectFile = false
        }
        SecretsService.getInstance().invalidate()
        assertNotNull(KeysIndex.getInstance(project).refreshNow())
    }

    override fun tearDown() {
        try {
            app.envSpecDecorators = true
            app.layeredEnvFiles = false
            app.varlockCompatibility = false
            CredentialVault.removeAll(connection.id, listOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET))
            SecretsAppSettings.getInstance().setConnections(emptyList())
            mock.close()
        } finally {
            super.tearDown()
        }
    }

    private fun npm(envFile: Path? = null, packageDir: Path? = null, envs: Map<String, String> = emptyMap()): NpmRunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType("js.build_tools.npm")!!
        val template = type.configurationFactories.first().createTemplateConfiguration(project)
        val configuration = RunManager.getInstance(project).createConfiguration(template, template.factory!!).configuration as NpmRunConfiguration
        var builder = configuration.runSettings.toBuilder().setEnvData(EnvironmentVariablesData.create(envs, true, envFile?.toString()))
        if (packageDir != null) {
            Files.writeString(packageDir.resolve("package.json"), "{}")
            builder = builder.setPackageJsonPath(packageDir.resolve("package.json").toString())
        }
        configuration.runSettings = builder.build()
        return configuration
    }

    private fun outcome(configuration: NpmRunConfiguration, configured: Map<String, String> = emptyMap(), override: String? = null): Pair<EnvFileReferences.Prepared, LaunchChecks.Outcome> {
        val resolver = LaunchSecretResolver.getInstance(project)
        val prepared = EnvFileReferences.prepare(configuration, configured, resolver, override)
        val resolved = resolver.resolve(resolver.plan(prepared.envs), environment = override)
        return prepared to LaunchChecks.apply(prepared, resolved, configured)
    }

    private val decorated = """
        # @required
        API_URL=
        # @sensitive
        TOKEN=abcd-secret-1234
        # @internal
        INTERNAL_ONLY=only-for-checks
        # @type=port
        PORT=not-a-port
        # @type=enum(debug, info)
        LEVEL=info
        DB=infisical://DB_PASSWORD
        # @required @sensitive
        DB_COPY=infisical://API_KEY
        PLAIN=1
    """.trimIndent() + "\n"

    fun `test required keys without a value stop the launch, values are never named`() {
        val dir = Files.createTempDirectory("envspec-req")
        val file = dir.resolve("app.env")
        Files.writeString(file, decorated)
        val configuration = npm(file)
        val (prepared, outcome) = outcome(configuration)
        assertThat(prepared.detach).describedAs("attached file with references and @internal keys is used and detached").isTrue()
        assertThat(outcome.missingRequired).containsExactly("API_URL")
        // A value set on the configuration satisfies it
        assertThat(outcome(configuration, mapOf("API_URL" to "http://localhost")).second.missingRequired).isEmpty()
        // @optional in a later layer (or here, the setting off) lets it through
        app.envSpecDecorators = false
        assertThat(outcome(configuration).second.missingRequired).isEmpty()
    }

    fun `test the runner blocks a launch with a missing required key and says which`() {
        val dir = Files.createTempDirectory("envspec-run")
        val file = dir.resolve("app.env")
        // No references at all: the decorators alone make EnvBridge take the launch
        Files.writeString(file, "# @required\nAPI_URL=\n# @required\nOTHER=\nSET=1\n")
        val configuration = npm(file)
        val runner = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)
        assertThat(runner).isInstanceOf(SecretsProgramRunner::class.java)
        val notifications = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
        val settings = RunManager.getInstance(project).createConfiguration(configuration, configuration.factory!!)
        val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).runner(runner!!).build()
        runner.execute(environment)
        PlatformTestUtil.waitWithEventsDispatching("no notification", { notifications.isNotEmpty() }, 30)
        val notification = notifications.single()
        assertThat(notification.title).isEqualTo("Required variables of '${configuration.name}' have no value")
        assertThat(notification.content).contains("API_URL, OTHER").doesNotContain("SET")
        assertThat(SecretMaskingService.getInstance(project).values()).isEmpty()
    }

    fun `test files without decorators are not taken over`() {
        val dir = Files.createTempDirectory("envspec-none")
        val file = dir.resolve("app.env")
        Files.writeString(file, "# just a comment @ home\nPLAIN=1\n")
        val configuration = npm(file)
        assertThat(EnvFileReferences.usesDecorators(configuration)).isFalse()
        assertThat(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)).isNotInstanceOf(SecretsProgramRunner::class.java)
        val (prepared, outcome) = outcome(configuration)
        assertThat(prepared.spec.active).isFalse()
        assertThat(prepared.envs).isEmpty()
        assertThat(outcome.missingRequired).isEmpty()
        // Decorators EnvBridge doesn't act on don't take it over either
        Files.writeString(file, "# @example=foo @docs(https://example.com)\nPLAIN=1\n")
        assertThat(EnvFileReferences.usesDecorators(configuration)).isFalse()
        Files.writeString(file, "# @required\nPLAIN=1\n")
        assertThat(EnvFileReferences.usesDecorators(configuration)).isTrue()
        app.envSpecDecorators = false
        assertThat(EnvFileReferences.usesDecorators(configuration)).isFalse()
    }

    fun `test sensitive values are masked, internal keys are not injected, type mismatches warn`() {
        val dir = Files.createTempDirectory("envspec-mask")
        val file = dir.resolve("app.env")
        Files.writeString(file, decorated)
        val (_, outcome) = outcome(npm(file), mapOf("API_URL" to "http://localhost"))
        assertThat(outcome.missingRequired).isEmpty()
        // Secrets from the secret manager and values of @sensitive keys; plain values aren't
        assertThat(outcome.maskedValues).contains("abcd-secret-1234", "dev-pass", "dev-key").doesNotContain("1", "info")
        assertThat(outcome.environment).containsEntry("TOKEN", "abcd-secret-1234").containsEntry("DB", "dev-pass")
            .containsEntry("DB_COPY", "dev-key").containsEntry("PLAIN", "1").doesNotContainKey("INTERNAL_ONLY")
        assertThat(outcome.typeWarnings).containsExactly("PORT: the value doesn't match @type=port")
        assertThat(outcome.typeWarnings.joinToString()).doesNotContain("not-a-port")
        assertThat(outcome.toString()).doesNotContain("abcd-secret-1234").doesNotContain("dev-pass")
        // Set on the configuration itself, an internal key is kept
        assertThat(outcome(npm(file), mapOf("API_URL" to "x", "INTERNAL_ONLY" to "mine")).second.environment).containsEntry("INTERNAL_ONLY", "mine")

        // The masking service masks what the launch registers (as the execution listener does)
        val handler = object : com.intellij.execution.process.NopProcessHandler() {}
        SecretMaskingService.getInstance(project).register(handler, outcome.maskedValues)
        try {
            assertThat(SecretMaskingService.getInstance(project).mask("token=abcd-secret-1234")).isEqualTo("token=${cz.xprees.secrets.core.model.SecretValue.MASK}")
        } finally {
            SecretMaskingService.getInstance(project).unregister(handler)
        }
    }

    fun `test the program's own dotenv with decorators is checked without being taken over`() {
        val dir = Files.createTempDirectory("envspec-nuxt")
        Files.writeString(dir.resolve(".env"), "# @sensitive\nSESSION_SECRET=very-secret-value\n# @type=url\nPUBLIC_URL=localhost:3000\n")
        val configuration = npm(packageDir = dir)
        assertThat(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)).isInstanceOf(SecretsProgramRunner::class.java)
        val (prepared, outcome) = outcome(configuration)
        // The tool reads its .env itself: nothing is passed, but values are known for masking and checks
        assertThat(prepared.envs).isEmpty()
        assertThat(outcome.maskedValues).containsExactly("very-secret-value")
        assertThat(outcome.typeWarnings).containsExactly("PUBLIC_URL: the value doesn't match @type=url")
    }

    fun `test layered env files follow the selected environment`() {
        app.layeredEnvFiles = true
        val dir = Files.createTempDirectory("envspec-layers")
        Files.writeString(dir.resolve(".env.schema"), "# @defaultRequired=false\n# ---\n# @required\nNAME=schema\nSCHEMA_ONLY=default\nUNDEFINED=\nCALL=op(op://v/i/f)\n")
        Files.writeString(dir.resolve(".env"), "NAME=base\nBASE=1\nLOCAL=base\nDEVVAL=base\n")
        Files.writeString(dir.resolve(".env.local"), "LOCAL=local\nDEVVAL=local\n")
        Files.writeString(dir.resolve(".env.dev"), "DEVVAL=dev\nSECRET=infisical://DB_PASSWORD\n")
        Files.writeString(dir.resolve(".env.dev.local"), "DEVLOCAL=1\n")
        Files.writeString(dir.resolve(".env.prod"), "DEVVAL=prod\nSECRET=infisical://DB_PASSWORD\n")
        val configuration = npm(packageDir = dir)

        assertThat(EnvFileReferences.dotenvFiles(configuration, "dev").map { it.fileName.toString() })
            .containsExactly(".env.schema", ".env", ".env.local", ".env.dev", ".env.dev.local")
        val (prepared, outcome) = outcome(configuration)
        assertThat(prepared.environment).isEqualTo("dev")
        assertThat(outcome.environment).containsEntry("NAME", "base").containsEntry("SCHEMA_ONLY", "default").containsEntry("BASE", "1")
            .containsEntry("LOCAL", "local").containsEntry("DEVVAL", "dev").containsEntry("DEVLOCAL", "1").containsEntry("SECRET", "dev-pass")
            .doesNotContainKeys("UNDEFINED", "CALL")
        assertThat(outcome.missingRequired).isEmpty()

        // ENVBRIDGE_ENVIRONMENT on the configuration, and "Run with environment…"
        val prod = outcome(configuration, mapOf("ENVBRIDGE_ENVIRONMENT" to "prod")).second.environment
        assertThat(prod).containsEntry("DEVVAL", "prod").containsEntry("SECRET", "prod-pass").doesNotContainKey("DEVLOCAL")
        assertThat(outcome(configuration, override = "prod").second.environment).containsEntry("DEVVAL", "prod")

        // The status bar's environment
        ProjectSecretsSettings.getInstance(project).update { environment = "prod" }
        assertThat(outcome(configuration).second.environment).containsEntry("DEVVAL", "prod")

        // An attached .env brings its layered siblings
        ProjectSecretsSettings.getInstance(project).update { environment = "dev" }
        val attached = npm(envFile = dir.resolve(".env"))
        assertThat(EnvFileReferences.files(attached, "dev").map { it.fileName.toString() })
            .containsExactly(".env.schema", ".env", ".env.local", ".env.dev", ".env.dev.local")
        val (attachedPrepared, attachedOutcome) = outcome(attached)
        assertThat(attachedPrepared.detach).isTrue()
        assertThat(attachedOutcome.environment).containsEntry("DEVVAL", "dev").containsEntry("SECRET", "dev-pass")

        // Layering off: the pair the tool reads itself, references only (as before)
        app.layeredEnvFiles = false
        assertThat(EnvFileReferences.dotenvFiles(configuration).map { it.fileName.toString() }).containsExactly(".env", ".env.local")
        assertThat(outcome(configuration).first.envs).isEmpty()
    }

    fun `test a schema's decorators apply without layering and environment names stay file names`() {
        val dir = Files.createTempDirectory("envspec-schema")
        Files.writeString(dir.resolve(".env.schema"), "# @defaultRequired=infer\n# ---\nAPI_URL=http://default\nOPTIONAL=\n")
        Files.writeString(dir.resolve(".env"), "API_URL=\nREF=infisical://API_KEY\n")
        val (_, outcome) = outcome(npm(packageDir = dir))
        // infer: API_URL has a value in the schema, so it is required; .env empties it
        assertThat(outcome.missingRequired).containsExactly("API_URL")
        assertThat(EnvFileReferences.layerNames("../../etc")).containsExactly(".env.schema", ".env", ".env.local")
        assertThat(EnvFileReferences.layerNames("staging")).containsExactly(".env.schema", ".env", ".env.local", ".env.staging", ".env.staging.local")
    }

    fun `test the inspection reports decorator problems in files that use decorators`() {
        myFixture.enableInspections(EnvSpecInspection::class.java)
        myFixture.configureByText(
            ".env.schema",
            "# @defaultRequired=maybe\n# @required\n# ---\n# @requried @optional\n# @type=port\nPORT=http\n# @required @optional\nA=1\n# @type=colour\nB=x\n# @type=string(startsWith=sk-)\nKEY=pk-123\n# @type=enum(a, b)\nE=c\n",
        )
        val problems = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }
        assertThat(problems).contains(
            "Unknown decorator @requried; did you mean @required?",
            "Invalid value for @defaultRequired: maybe",
            "@required belongs to a variable, not to the header",
            "@required and @optional contradict each other",
            "Unknown type 'colour'",
            "The value of PORT doesn't match @type=port",
            "The value of KEY doesn't match @type=string(startsWith=sk-)",
            "The value of E doesn't match @type=enum(a, b)",
        )
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Change to @required" })
        assertThat(myFixture.editor.document.text).contains("# @required @optional\n# @type=port")

        // A plain .env with @-comments but no decorators stays quiet
        myFixture.configureByText(".env", "# mail me @ home\nA=1 # see @foo\n")
        assertThat(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }.filter { "decorator" in it }).isEmpty()
    }

    fun `test decorator names are completed after # @`() {
        myFixture.configureByText(".env.schema", "# @sens<caret>\nA=1\n")
        myFixture.completeBasic()
        assertThat(myFixture.editor.document.text).isEqualTo("# @sensitive\nA=1\n")
        myFixture.configureByText(".env", "A=1 # @requ<caret>\n")
        myFixture.completeBasic()
        assertThat(myFixture.editor.document.text).isEqualTo("A=1 # @required\n")
        assertThat(EnvSpecDecoratorCompletionContributor.decoratorPrefix("# @required @ty")).isEqualTo("ty")
        assertThat(EnvSpecDecoratorCompletionContributor.decoratorPrefix("# @type=enum(a, b")).isNull()
        assertThat(EnvSpecDecoratorCompletionContributor.decoratorPrefix("# plain @x")).isNull()
    }

    fun `test the settings are stored with defaults that keep existing behaviour`() {
        val defaults = SecretsAppSettings.AppState()
        assertThat(defaults.envSpecDecorators).isTrue()
        assertThat(defaults.layeredEnvFiles).isFalse()
        assertThat(defaults.varlockCompatibility).isFalse()
        val configurable = SecretsConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            assertThat(configurable.isModified).isFalse()
        } finally {
            configurable.disposeUIResources()
        }
    }
}
