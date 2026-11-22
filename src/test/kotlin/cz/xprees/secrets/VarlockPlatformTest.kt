package cz.xprees.secrets

import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.resolve.EnvResolution
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.onepassword.OnePasswordAuthMethods
import cz.xprees.secrets.onepassword.OnePasswordProvider
import cz.xprees.secrets.run.EnvFileReferences
import cz.xprees.secrets.run.LaunchChecks
import cz.xprees.secrets.run.SecretsProgramRunner
import cz.xprees.secrets.run.VarlockLaunch
import cz.xprees.secrets.run.VarlockLaunchException
import cz.xprees.secrets.run.masking.SecretMaskingService
import cz.xprees.secrets.vault.VaultAuth
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The varlock compatibility mode at launch: varlock functions resolved through EnvBridge connections, refusals, @import, @currentEnv. */
class VarlockPlatformTest : BasePlatformTestCase() {
    private lateinit var infisical: MockInfisical
    private lateinit var vault: MockVault
    private lateinit var op: FakeOp
    private val connections = mutableListOf<SecretConnection>()
    private val app get() = SecretsAppSettings.getInstance().state

    override fun setUp() {
        super.setUp()
        infisical = MockInfisical()
        vault = MockVault()
        op = FakeOp()
        VaultAuth.cliTokenFile = null
        val infisicalConnection = SecretConnection(
            "varlock-inf-${System.nanoTime()}", InfisicalProvider.ID, "Infisical", infisical.baseUrl, InfisicalAuthMethods.MACHINE_IDENTITY,
            mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to infisical.clientId),
        )
        CredentialVault.set(infisicalConnection.id, CredentialVault.secretOptionSlot(InfisicalAuthMethods.OPTION_CLIENT_SECRET), infisical.clientSecret)
        val vaultConnection = SecretConnection(
            "varlock-vault-${System.nanoTime()}", VaultProvider.ID, "Vault", vault.baseUrl, VaultAuthMethods.APPROLE,
            mapOf(VaultAuthMethods.OPTION_ROLE_ID to vault.roleId),
        )
        CredentialVault.set(vaultConnection.id, CredentialVault.secretOptionSlot(VaultAuthMethods.OPTION_SECRET_ID), vault.secretId)
        val opConnection = SecretConnection(
            "varlock-op-${System.nanoTime()}", OnePasswordProvider.ID, "1Password", "http://localhost:8080", OnePasswordAuthMethods.DESKTOP_APP,
            mapOf(OnePasswordAuthMethods.OPTION_OP_PATH to op.executable.toString()),
        )
        connections += listOf(infisicalConnection, vaultConnection, opConnection)
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = infisicalConnection.id
            projectId = "p1"
            environment = "dev"
            path = null
            useProjectFile = false
        }
        SecretsService.getInstance().invalidate()
        app.varlockCompatibility = true
    }

    override fun tearDown() {
        try {
            app.varlockCompatibility = false
            app.layeredEnvFiles = false
            app.envSpecDecorators = true
            CredentialVault.removeAll(connections[0].id, listOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET))
            CredentialVault.removeAll(connections[1].id, listOf(VaultAuthMethods.OPTION_SECRET_ID))
            SecretsAppSettings.getInstance().setConnections(emptyList())
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsService.getInstance().invalidate()
            infisical.close()
            vault.close()
            op.close()
        } finally {
            super.tearDown()
        }
    }

    private fun npm(packageDir: Path, envs: Map<String, String> = emptyMap()): NpmRunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType("js.build_tools.npm")!!
        val template = type.configurationFactories.first().createTemplateConfiguration(project)
        val configuration = RunManager.getInstance(project).createConfiguration(template, template.factory!!).configuration as NpmRunConfiguration
        Files.writeString(packageDir.resolve("package.json"), "{}")
        configuration.runSettings = configuration.runSettings.toBuilder()
            .setEnvData(EnvironmentVariablesData.create(envs, true, null))
            .setPackageJsonPath(packageDir.resolve("package.json").toString())
            .build()
        return configuration
    }

    private class Launch(val prepared: EnvFileReferences.Prepared, val result: EnvResolution.Result, val outcome: LaunchChecks.Outcome)

    /** What the runner does in its background task, without starting anything. */
    private fun launch(configuration: NpmRunConfiguration, configured: Map<String, String> = emptyMap(), override: String? = null): Launch {
        val resolver = LaunchSecretResolver.getInstance(project)
        val prepared = EnvFileReferences.prepare(configuration, configured, resolver, override)
        VarlockLaunch.checkProblems(prepared.varlock)
        val resolved = resolver.resolve(resolver.plan(prepared.envs), environment = override)
        val result = VarlockLaunch.resolve(project, prepared, resolved, resolver, override ?: SecretBindingService.environmentOverride(configured))
        return Launch(prepared, result, LaunchChecks.apply(prepared, result, configured))
    }

    private fun schema(dir: Path, text: String) = Files.writeString(dir.resolve(".env.schema"), text.trimIndent() + "\n")

    private val dollar = "$"

    private fun fullSchema() = """
        # @initInfisical(projectId=p1, environment=dev, clientId=${dollar}INFISICAL_CLIENT_ID, clientSecret=${dollar}INFISICAL_CLIENT_SECRET)
        # @initHcpVault(url="${vault.baseUrl}")
        # @initOp(token=${dollar}OP_TOKEN)
        # @defaultRequired=infer @defaultSensitive=false
        # @plugin(@varlock/infisical-plugin) @plugin(@varlock/hashicorp-vault-plugin)
        # ---
        # @required
        INFISICAL_CLIENT_ID=
        # @required @sensitive
        INFISICAL_CLIENT_SECRET=
        OP_TOKEN=
        DB_PASSWORD=infisical()
        API=infisical("API_KEY")
        VAULT_PW=vaultSecret("secret/app/db#password")
        user=vaultSecret("secret/app/db")
        OP_PW=op(op://Dev/Database/password)
        HOST=localhost
        URL="postgres://app:${dollar}{DB_PASSWORD}@${dollar}{HOST}/db"
        OPTIONAL=fallback(infisical("NOPE", allowMissing=true), "the-default")
        COPY=ref(API)
        PLAIN_REF=infisical://API_KEY
        RAW=vaultSecret("secret/app/db", raw=true)
        # @type=port
        PORT=${dollar}{UNSET_PORT:-8080}
    """

    fun `test a launch resolves infisical, vaultSecret, op, ref, concat and fallback through EnvBridge connections`() {
        val dir = Files.createTempDirectory("varlock-launch")
        schema(dir, fullSchema())
        val configuration = npm(dir)
        assertThat(EnvFileReferences.usesVarlock(configuration)).isTrue()
        assertThat(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)).isInstanceOf(SecretsProgramRunner::class.java)

        val launch = launch(configuration)
        val environment = launch.outcome.environment
        assertThat(environment)
            .containsEntry("DB_PASSWORD", "dev-pass")
            .containsEntry("API", "dev-key")
            .containsEntry("VAULT_PW", "root-db-pass")
            .containsEntry("user", "app")
            .containsEntry("OP_PW", "db-pass")
            .containsEntry("HOST", "localhost")
            .containsEntry("URL", "postgres://app:dev-pass@localhost/db")
            .containsEntry("OPTIONAL", "the-default")
            .containsEntry("COPY", "dev-key")
            .containsEntry("PLAIN_REF", "dev-key")
            .containsEntry("PORT", "8080")
            .doesNotContainKey("INFISICAL_CLIENT_ID")
        assertThat(com.google.gson.JsonParser.parseString(environment["RAW"]).asJsonObject.keySet()).containsExactlyInAnyOrder("password", "user")
        // Credentials only @initInfisical refers to are not required: EnvBridge signs in by itself
        assertThat(launch.outcome.missingRequired).isEmpty()
        assertThat(launch.outcome.typeWarnings).isEmpty()
        // Secrets are masked like any secret, also inside a concatenated value; plain values are not
        assertThat(launch.outcome.maskedValues).contains("dev-pass", "dev-key", "root-db-pass", "db-pass", "postgres://app:dev-pass@localhost/db")
            .doesNotContain("localhost", "the-default", "8080")
        val source = launch.result.sources["DB_PASSWORD"] as EnvResolution.Source.Varlock
        assertThat(source.functions).containsExactly("infisical")
        assertThat(source.secrets.single().reference.format()).isEqualTo("infisical://dev:DB_PASSWORD?project=p1")
        assertThat((launch.result.sources["URL"] as EnvResolution.Source.Varlock).functions).containsExactly("infisical")
        assertThat(launch.result.sources["HOST"]).isEqualTo(EnvResolution.Source.Explicit)
        assertThat(launch.result.summary()).contains("varlock infisical()").doesNotContain("dev-pass")
        assertThat(launch.result.toString()).doesNotContain("dev-pass")
        assertThat(launch.prepared.varlock.toString()).doesNotContain("dev-pass")
        // The configuration's own value wins over the file's function
        assertThat(launch(configuration, mapOf("DB_PASSWORD" to "mine")).outcome.environment).containsEntry("DB_PASSWORD", "mine")
            .containsEntry("URL", "postgres://app:mine@localhost/db")
    }

    fun `test a missing secret without allowMissing, an unknown Vault URL and a required empty value stop the launch by name`() {
        val dir = Files.createTempDirectory("varlock-missing")
        schema(dir, "# @initInfisical(projectId=p1, environment=dev)\n# ---\nGONE=infisical(\"NOPE\")\nOK=infisical(\"API_KEY\")")
        assertThatThrownBy { launch(npm(dir)) }.isInstanceOf(VarlockLaunchException::class.java)
            .hasMessage("GONE: infisical() reads a secret that doesn't exist in dev:/ (add allowMissing=true if it is optional)")
        schema(dir, "# @initHcpVault(id=other, url=\"https://vault.example.com\")\n# ---\nX=vaultSecret(other, \"secret/app/db#password\")")
        assertThatThrownBy { launch(npm(dir)) }.isInstanceOf(VarlockLaunchException::class.java)
            .hasMessageContaining("X: @initHcpVault uses https://vault.example.com, but no EnvBridge connection does")
        // varlock's default for unmarked files: required (an undefined value stops the launch)
        schema(dir, "EMPTY_ONE=fallback(infisical(\"NOPE\", allowMissing=true), \"\")\nSET=x")
        assertThat(launch(npm(dir)).outcome.missingRequired).containsExactly("EMPTY_ONE")
    }

    fun `test exec is refused with a clear message before anything is fetched, and no process starts`() {
        val dir = Files.createTempDirectory("varlock-exec")
        val marker = dir.resolve("exec-ran")
        schema(dir, "# @initInfisical(projectId=p1, environment=dev)\n# ---\nDB=infisical()\nTOKEN=exec(\"touch $marker\")\nOTHER=\"x-$(touch $marker)\"")
        val configuration = npm(dir)
        assertThatThrownBy { EnvFileReferences.prepare(configuration, emptyMap(), LaunchSecretResolver.getInstance(project)).varlock.let(VarlockLaunch::checkProblems) }
            .isInstanceOf(VarlockLaunchException::class.java)
            .hasMessageContaining("TOKEN: exec() is refused: EnvBridge never runs commands from env files")
            .hasMessageContaining("OTHER: $(…) is refused")

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
        assertThat(notification.title).isEqualTo("varlock values of '${configuration.name}' can't be resolved")
        assertThat(notification.content).contains("TOKEN: exec() is refused").doesNotContain("touch")
        assertThat(notification.actions.map { it.templateText }).contains("Open .env.schema", "Retry")
        assertThat(Files.exists(marker)).describedAs("no command ran").isFalse()
        assertThat(SecretMaskingService.getInstance(project).values()).isEmpty()
    }

    fun `test import brings in files of the project only, with pick`() {
        val base = Path.of(project.basePath!!).also { Files.createDirectories(it) }
        val appDir = Files.createDirectories(base.resolve("app-${System.nanoTime()}"))
        val shared = Files.createDirectories(appDir.resolve("shared"))
        Files.writeString(shared.resolve(".env.shared"), "SHARED_A=infisical(\"API_KEY\")\nSHARED_B=b\nOVERRIDDEN=from-import\n")
        schema(appDir, "# @import(./shared/.env.shared, pick=[SHARED_A, OVERRIDDEN])\n# @import(./missing.env, allowMissing=true)\n# ---\nOVERRIDDEN=from-schema\nOWN=1")
        val launch = launch(npm(appDir))
        assertThat(launch.outcome.environment).containsEntry("SHARED_A", "dev-key").containsEntry("OVERRIDDEN", "from-schema")
            .containsEntry("OWN", "1").doesNotContainKey("SHARED_B")

        val outside = Files.createTempDirectory("varlock-outside")
        Files.writeString(outside.resolve("secrets.env"), "STOLEN=1\n")
        schema(appDir, "# @import(${outside.resolve("secrets.env")})\n# @import(../../../etc/hosts)\n# ---\nOWN=1")
        assertThatThrownBy { launch(npm(appDir)) }.isInstanceOf(VarlockLaunchException::class.java)
            .hasMessageContaining("@import(${outside.resolve("secrets.env")}) is refused: only files inside the project can be imported")
            .hasMessageContaining("@import(../../../etc/hosts) is refused")
        schema(appDir, "# @import(./nope.env)\n# ---\nOWN=1")
        assertThatThrownBy { launch(npm(appDir)) }.hasMessageContaining("@import(./nope.env): no such file")
    }

    fun `test currentEnv picks the layered files and forEnv, below Run with environment`() {
        val dir = Files.createTempDirectory("varlock-env")
        schema(dir, "# @currentEnv=${dollar}APP_ENV\n# ---\nAPP_ENV=staging\nLEVEL=if(forEnv(staging, prod), quiet, loud)\nNAME=base")
        Files.writeString(dir.resolve(".env.staging"), "NAME=staging-file\n")
        Files.writeString(dir.resolve(".env.prod"), "NAME=prod-file\n")
        Files.writeString(dir.resolve(".env.dev"), "NAME=dev-file\n")
        val configuration = npm(dir)

        val staging = launch(configuration)
        assertThat(staging.prepared.environment).isEqualTo("staging")
        assertThat(staging.outcome.environment).containsEntry("NAME", "staging-file").containsEntry("LEVEL", "quiet")
        // The run configuration's APP_ENV wins over the file's
        assertThat(launch(configuration, mapOf("APP_ENV" to "prod")).outcome.environment).containsEntry("NAME", "prod-file")
        // "Run with environment…" wins over @currentEnv
        val dev = launch(configuration, override = "dev")
        assertThat(dev.prepared.environment).isEqualTo("dev")
        assertThat(dev.outcome.environment).containsEntry("NAME", "dev-file").containsEntry("LEVEL", "loud")
        // Without @currentEnv, EnvBridge's selected environment (the binding: dev)
        schema(dir, "LEVEL=if(forEnv(dev), dev-level, other)\nNAME=base")
        assertThat(launch(configuration).outcome.environment).containsEntry("NAME", "dev-file").containsEntry("LEVEL", "dev-level")
    }

    fun `test with the mode off nothing changes`() {
        app.varlockCompatibility = false
        val dir = Files.createTempDirectory("varlock-off")
        schema(dir, fullSchema())
        Files.writeString(dir.resolve(".env"), "FROM_DOTENV=infisical()\nREF=infisical://API_KEY\n")
        val configuration = npm(dir)
        assertThat(EnvFileReferences.usesVarlock(configuration)).isFalse()
        val launch = launch(configuration)
        assertThat(launch.prepared.varlock).isNull()
        // As before: only references of the program's own .env are passed; function values stay the program's business
        assertThat(launch.prepared.envs).containsExactly(org.assertj.core.api.Assertions.entry("REF", "infisical://API_KEY"))
        assertThat(launch.outcome.environment).containsEntry("REF", "dev-key").doesNotContainKey("FROM_DOTENV").doesNotContainKey("DB_PASSWORD")
        assertThat(launch.result.sources.values).noneMatch { it is EnvResolution.Source.Varlock }
    }

    fun `test inline credentials in a header are ignored with a warning`() {
        val dir = Files.createTempDirectory("varlock-inline")
        schema(dir, "# @initInfisical(projectId=p1, environment=dev, clientId=abc, clientSecret=very-secret-inline)\n# ---\nDB_PASSWORD=infisical()")
        val launch = launch(npm(dir))
        assertThat(launch.outcome.environment).containsEntry("DB_PASSWORD", "dev-pass")
        assertThat(launch.result.warnings).containsExactly(
            "@initInfisical in .env.schema has clientSecret written inline: EnvBridge ignores it and signs in by itself. Remove it from the file",
        )
        assertThat(launch.result.warnings.joinToString()).doesNotContain("very-secret-inline")
    }

    fun `test editor references and extract understand varlock functions`() {
        val text = "# @initInfisical(projectId=p1, environment=dev)\n# ---\nDB_PASSWORD=infisical()\nX=vaultSecret(\"secret/app/db#password\")\nAPI_KEY=plain-value\n"
        val file = myFixture.configureByText(".env", text)
        val references = cz.xprees.secrets.editor.EditorReferences.of(file).filter { it.kind == cz.xprees.secrets.core.resolve.EnvReferences.Kind.REFERENCE }
        assertThat(references.map { it.name to it.reference!!.format() }).containsExactly(
            "DB_PASSWORD" to "infisical://dev:DB_PASSWORD?project=p1",
            "password" to "vault://secret/app/db#password",
        )
        // The hint goes after the call
        assertThat(text.substring(references.first().start, references.first().end)).isEqualTo("infisical()")

        // Extract / bind write the varlock function instead of a binding comment
        assertThat(cz.xprees.secrets.editor.varlockCall(text, "API_KEY", "infisical://API_KEY")).isEqualTo("infisical()")
        assertThat(cz.xprees.secrets.editor.varlockCall(text, "OTHER", "infisical://backend/API_KEY")).isEqualTo("infisical(\"API_KEY\", \"/backend\")")
        assertThat(cz.xprees.secrets.editor.varlockCall(text, "OTHER", "infisical://prod:API_KEY")).isNull()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            cz.xprees.secrets.editor.DotEnvLines.setValue(myFixture.editor.document, "API_KEY", "infisical()")
        }
        assertThat(myFixture.editor.document.text).endsWith("API_KEY=infisical()\n")

        // Without the mode, function values are no references
        app.varlockCompatibility = false
        val plain = myFixture.configureByText(".env.local", text)
        assertThat(cz.xprees.secrets.editor.EditorReferences.of(plain).filter { it.kind == cz.xprees.secrets.core.resolve.EnvReferences.Kind.REFERENCE }).isEmpty()
    }
}
