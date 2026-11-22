package cz.xprees.secrets

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.aws.AwsAuthMethods
import cz.xprees.secrets.aws.AwsMemory
import cz.xprees.secrets.aws.AwsSecretsManagerProvider
import cz.xprees.secrets.aws.AwsSso
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * The AWS provider's name layouts, fewer requests (BatchGetSecretValue, values kept by version), descriptions, tags and
 * rotation, and the IAM Identity Center sign-in, against [MockAwsSecretsManager] and [MockAwsSso].
 */
class AwsFeaturesPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockAwsSecretsManager
    private lateinit var sso: MockAwsSso
    private val connections = mutableListOf<SecretConnection>()

    override fun setUp() {
        super.setUp()
        mock = MockAwsSecretsManager()
        sso = MockAwsSso()
        mock.put("myapp/dev", """{"DB_PASSWORD":"dev-pass","PORT":5432}""")
        mock.put("myapp/prod", """{"DB_PASSWORD":"prod-pass"}""")
        mock.put("myapp/stg", """{"DB_PASSWORD":"stg-pass"}""")
        // environment-first names, as some teams name them
        mock.put("dev/billing", """{"STRIPE_KEY":"sk-dev"}""")
        mock.put("prod/billing", """{"STRIPE_KEY":"sk-prod"}""")
        mock.put("acme/dev/web", """{"URL":"https://dev.acme"}""")
        AwsSso.endpointOverride = { _, _ -> sso.baseUrl }
        AwsSso.pollIntervalOverrideMs = 10
    }

    override fun tearDown() {
        try {
            connections.forEach { CredentialVault.removeAll(it.id, AwsAuthMethods.SECRET_KEYS) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            SecretsAppSettings.getInstance().state.readOnly = false
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null; useProjectFile = true; restrictToBoundProject = true }
            SecretsService.getInstance().invalidate()
            AwsSso.endpointOverride = null
            AwsSso.pollIntervalOverrideMs = null
            AwsMemory.clock = System::currentTimeMillis
            mock.close()
            sso.close()
        } finally {
            super.tearDown()
        }
    }

    private fun connection(options: Map<String, String> = emptyMap(), method: String = AwsAuthMethods.ACCESS_KEY): SecretConnection {
        val connection = SecretConnection(
            id = "aws-f-${System.nanoTime()}",
            providerId = AwsSecretsManagerProvider.ID,
            name = "AWS ${connections.size}",
            baseUrl = mock.baseUrl,
            authMethodId = method,
            options = mapOf(AwsAuthMethods.OPTION_REGION to mock.region) + options,
        )
        if (method == AwsAuthMethods.ACCESS_KEY) {
            CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(AwsAuthMethods.OPTION_ACCESS_KEY_ID), "AKIAMOCKKEY000000001")
            CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(AwsAuthMethods.OPTION_SECRET_ACCESS_KEY), "mock-secret-key/1")
        }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun client(connection: SecretConnection) = SecretsService.getInstance().client(connection)

    private val browser = mutableListOf<String>()
    private val statuses = mutableListOf<String>()

    private val interaction = object : SignInInteraction {
        override fun openBrowser(url: String) {
            browser += url
        }

        override fun isCancelled(): Boolean = false

        override fun status(text: String) {
            statuses += text
        }
    }

    private fun failure(call: () -> Unit): SecretProviderException = try {
        call()
        error("no exception")
    } catch (e: SecretProviderException) {
        e
    }

    private fun bind(connection: SecretConnection, projectId: String, environment: String) {
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            this.projectId = projectId
            this.environment = environment
            path = null
            useProjectFile = false
            restrictToBoundProject = false
        }
    }

    // ---- Name layout --------------------------------------------------------------------------------------------------

    fun `test an environment-first layout lists, reads, writes and creates by the template`() {
        val connection = connection(mapOf(AwsAuthMethods.OPTION_NAME_LAYOUT to "{env}/{project}"))
        val projects = client(connection).listProjects()
        // myapp/dev reads as project dev, environment myapp under this layout; acme/dev/web as project dev/web
        assertThat(projects.first { it.id == "billing" }.environments.map { it.slug }).containsExactly("dev", "prod")
        assertThat(client(connection).listSecrets(SecretScope("billing", "prod")).single().value.reveal()).isEqualTo("sk-prod")

        SecretsService.getInstance().createSecret(connection, SecretScope("billing", "dev"), "WEBHOOK", SecretValue("wh"))
        assertThat(mock.current("dev/billing")).contains("\"WEBHOOK\":\"wh\"")
        SecretsService.getInstance().createEnvironment(connection, "billing", "qa", "qa")
        assertThat(mock.current("qa/billing")).isEqualTo("{}")
    }

    fun `test a layout with a fixed start filters on the server and hides other names with a note`() {
        val connection = connection(mapOf(AwsAuthMethods.OPTION_NAME_LAYOUT to "acme/{env}/{project}"))
        val projects = client(connection).listProjects()
        assertThat(projects.map { it.id }).containsExactly("web")
        val filter = mock.requests("ListSecrets").last().body.getAsJsonArray("Filters").single().asJsonObject
        assertThat(filter.getAsJsonArray("Values").single().asString).isEqualTo("acme/")
        assertThat(SecretProvider.byId(AwsSecretsManagerProvider.ID)!!.listingNote(connection)).isNull()

        // without the fixed start, names that don't match are counted
        val envFirst = connection(mapOf(AwsAuthMethods.OPTION_NAME_LAYOUT to "{project}-{env}"))
        assertThat(client(envFirst).listProjects()).isEmpty()
        assertThat(SecretProvider.byId(AwsSecretsManagerProvider.ID)!!.listingNote(envFirst)).contains("6 secrets are not shown").contains("{project}-{env}")

        val clash = connection(mapOf(AwsAuthMethods.OPTION_NAME_LAYOUT to "acme/{env}/{project}", AwsAuthMethods.OPTION_NAME_PREFIX to "other/"))
        assertThat(failure { client(clash).listProjects() }.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(failure { client(connection(mapOf(AwsAuthMethods.OPTION_NAME_LAYOUT to "{project}{env}"))).listProjects() }.message).contains("separator")
    }

    fun `test the name layout option is validated in the connection dialog`() {
        val option = AwsAuthMethods.ALL.first().options.first { it.key == AwsAuthMethods.OPTION_NAME_LAYOUT }
        assertThat(option.validate!!("{env}/{project}")).isNull()
        assertThat(option.validate!!("{env}")).contains("exactly once")
    }

    fun `test a reference names project and environment, whatever the layout`() {
        mock.put("dev/myapp", """{"DB_PASSWORD":"env-first-pass"}""")
        val resolver = LaunchSecretResolver.getInstance(project)
        val plan = resolver.plan(mapOf("DB_PASSWORD" to "aws-sm://myapp/dev#DB_PASSWORD", "SHORT" to "aws-sm://#DB_PASSWORD"))

        bind(connection(), "myapp", "dev")
        assertThat(resolver.resolve(plan).environment).containsEntry("DB_PASSWORD", "dev-pass").containsEntry("SHORT", "dev-pass")

        bind(connection(mapOf(AwsAuthMethods.OPTION_NAME_LAYOUT to "{env}/{project}")), "myapp", "dev")
        assertThat(resolver.resolve(plan).environment).containsEntry("DB_PASSWORD", "env-first-pass").containsEntry("SHORT", "env-first-pass")
    }

    // ---- Fewer requests -------------------------------------------------------------------------------------------------

    fun `test a launch reads every secret it needs in one BatchGetSecretValue request`() {
        val connection = connection()
        bind(connection, "myapp", "dev")
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(
                linkedMapOf(
                    "DEV" to "aws-sm://#DB_PASSWORD",
                    "PROD" to "aws-sm://myapp/prod#DB_PASSWORD",
                    "STG" to "aws-sm://myapp/stg#DB_PASSWORD",
                ),
            ),
        )
        assertThat(result.environment).containsEntry("DEV", "dev-pass").containsEntry("PROD", "prod-pass").containsEntry("STG", "stg-pass")
        // a secret that doesn't exist is reported by the batch, and the launch fails closed
        mock.requests.clear()
        assertThatThrownBy {
            resolver.resolve(resolver.plan(linkedMapOf("DEV" to "aws-sm://#DB_PASSWORD", "MISSING" to "aws-sm://myapp/nope#X")))
        }.isInstanceOf(SecretProviderException::class.java)
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(1)
    }

    fun `test the first launch batches, and reading ahead never serves a second launch`() {
        val connection = connection()
        bind(connection, "myapp", "dev")
        val resolver = LaunchSecretResolver.getInstance(project)
        val plan = resolver.plan(linkedMapOf("DEV" to "aws-sm://#DB_PASSWORD", "PROD" to "aws-sm://myapp/prod#DB_PASSWORD"))
        assertThat(resolver.resolve(plan).environment).containsEntry("DEV", "dev-pass").containsEntry("PROD", "prod-pass")
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(1)
        assertThat(mock.requests("GetSecretValue")).isEmpty()

        // a change made right after is seen by the next launch: launches always read current values
        mock.write("myapp/prod", """{"DB_PASSWORD":"prod-new"}""")
        assertThat(resolver.resolve(plan).environment).containsEntry("PROD", "prod-new")
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(2)
    }

    fun `test a denied BatchGetSecretValue falls back to GetSecretValue and is not retried for a while`() {
        val connection = connection()
        bind(connection, "myapp", "dev")
        mock.denied += "BatchGetSecretValue"
        val resolver = LaunchSecretResolver.getInstance(project)
        val plan = resolver.plan(linkedMapOf("DEV" to "aws-sm://#DB_PASSWORD", "PROD" to "aws-sm://myapp/prod#DB_PASSWORD"))
        assertThat(resolver.resolve(plan).environment).containsEntry("DEV", "dev-pass").containsEntry("PROD", "prod-pass")
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(1)
        assertThat(mock.requests("GetSecretValue")).hasSize(2)

        resolver.resolve(plan)
        assertThat(mock.requests("BatchGetSecretValue")).describedAs("remembered").hasSize(1)

        // an hour later it is tried again
        val start = System.currentTimeMillis()
        AwsMemory.clock = { start + 61 * 60 * 1000L }
        mock.denied.clear()
        resolver.resolve(plan)
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(2)
    }

    fun `test per-secret errors of a batch are reported for that secret only`() {
        val connection = connection()
        mock.denied += "GetSecretValue:myapp/prod"
        client(connection).prefetch(listOf(SecretScope("myapp", "dev"), SecretScope("myapp", "prod")))
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(1)
        assertThat(client(connection).listSecrets(SecretScope("myapp", "dev")).first().value.reveal()).isEqualTo("dev-pass")
        assertThat(mock.requests("GetSecretValue")).isEmpty()
        assertThat(failure { client(connection).listSecrets(SecretScope("myapp", "prod")) }.kind).isEqualTo(Kind.FORBIDDEN)
    }

    fun `test browsing re-reads only secrets whose version changed since they were read`() {
        val connection = connection()
        val service = SecretsService.getInstance()
        service.projects(connection, fresh = true)
        // the keys index reads every environment of the bound project: one batch for all three
        listOf("dev", "prod", "stg").forEach { service.secrets(connection, SecretScope("myapp", it)) }
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(1)
        assertThat(mock.requests("BatchGetSecretValue").single().body.getAsJsonArray("SecretIdList").map { it.asString })
            .containsExactlyInAnyOrder("myapp/dev", "myapp/prod", "myapp/stg")
        assertThat(mock.requests("GetSecretValue")).isEmpty()

        // the shared cache expires; a new listing shows only prod changed
        mock.write("myapp/prod", """{"DB_PASSWORD":"prod-new"}""")
        service.invalidate(connection.id)
        mock.requests.clear()
        service.projects(connection, fresh = true)
        val values = listOf("dev", "prod", "stg").associateWith { service.secrets(connection, SecretScope("myapp", it)).single { e -> e.key == "DB_PASSWORD" }.value.reveal() }
        assertThat(values).containsEntry("prod", "prod-new").containsEntry("dev", "dev-pass")
        // invalidate() also forgets the values read: everything is read again, in one batch
        assertThat(mock.requests("BatchGetSecretValue")).hasSize(1)

        // without invalidating, only the changed secret is read
        mock.write("myapp/stg", """{"DB_PASSWORD":"stg-new"}""")
        mock.requests.clear()
        client(connection).listProjects()
        val again = listOf("dev", "prod", "stg").associateWith { client(connection).listSecrets(SecretScope("myapp", it), fresh = false).single { e -> e.key == "DB_PASSWORD" }.value.reveal() }
        assertThat(again).containsEntry("stg", "stg-new").containsEntry("prod", "prod-new").containsEntry("dev", "dev-pass")
        assertThat(mock.requests("BatchGetSecretValue") + mock.requests("GetSecretValue")).hasSize(1)
        assertThat(mock.requests.first { it.action == "GetSecretValue" || it.action == "BatchGetSecretValue" }.rawBody).contains("myapp/stg").doesNotContain("myapp/dev")

        // current values asked for: always a request
        client(connection).listSecrets(SecretScope("myapp", "dev"), fresh = true)
        assertThat(mock.requests("GetSecretValue").last().body.get("SecretId").asString).isEqualTo("myapp/dev")
    }

    fun `test LastChangedDate detects changes where the listing has no version IDs`() {
        mock.listWithoutVersions = true
        val connection = connection()
        client(connection).listProjects()
        client(connection).listSecrets(SecretScope("myapp", "dev"), fresh = false)
        mock.requests.clear()
        client(connection).listSecrets(SecretScope("myapp", "dev"), fresh = false)
        assertThat(mock.requests).isEmpty()

        mock.write("myapp/dev", """{"DB_PASSWORD":"changed"}""")
        client(connection).listProjects()
        assertThat(client(connection).listSecrets(SecretScope("myapp", "dev"), fresh = false).single().value.reveal()).isEqualTo("changed")
    }

    fun `test the keys index works from the batch`() {
        val connection = connection()
        bind(connection, "myapp", "dev")
        val snapshot = KeysIndex.getInstance(project).refreshNow()!!
        assertThat(snapshot.environments.keys).containsExactlyInAnyOrder("dev", "prod", "stg")
        assertThat(snapshot.coverage("PORT")).containsExactly("dev")
        assertThat(mock.requests("GetSecretValue").size + mock.requests("BatchGetSecretValue").size).isLessThanOrEqualTo(2)
    }

    // ---- Descriptions, tags, rotation ------------------------------------------------------------------------------------

    fun `test descriptions are the keys' comments, and editing one changes the secret's description without a new version`() {
        val connection = connection()
        mock.secrets.getValue("myapp/dev").description = "Main app, development"
        mock.secrets.getValue("myapp/dev").tags = mapOf("team" to "core", "cost-center" to "42")
        val environments = client(connection).listProjects().first { it.id == "myapp" }.environments
        val dev = environments.first { it.slug == "dev" }
        assertThat(dev.metadata!!.description).isEqualTo("Main app, development")
        assertThat(dev.metadata!!.tags).containsEntry("team", "core").containsEntry("cost-center", "42")
        assertThat(dev.metadata!!.rotation).isNull()
        assertThat(client(connection).listSecrets(SecretScope("myapp", "dev")).map { it.comment }).containsOnly("Main app, development")

        val versions = mock.secrets.getValue("myapp/dev").versions.size
        SecretsService.getInstance().updateSecret(connection, SecretScope("myapp", "dev"), "PORT", SecretValue("5432"), comment = "Shared by every key")
        val update = mock.requests("UpdateSecret").single().body
        assertThat(update.get("Description").asString).isEqualTo("Shared by every key")
        assertThat(update.has("SecretString")).isFalse()
        assertThat(mock.secrets.getValue("myapp/dev").versions).hasSize(versions)
        assertThat(mock.requests("PutSecretValue")).isEmpty()
        assertThat(client(connection).listSecrets(SecretScope("myapp", "dev")).map { it.comment }).containsOnly("Shared by every key")
    }

    fun `test a new key's comment becomes the description only of a new secret or one without a description`() {
        val connection = connection()
        val service = SecretsService.getInstance()
        service.createSecret(connection, SecretScope("myapp", "qa"), "A", SecretValue("1"), "QA config")
        assertThat(mock.secrets.getValue("myapp/qa").description).isEqualTo("QA config")
        service.createSecret(connection, SecretScope("myapp", "qa"), "B", SecretValue("2"), "Something else")
        assertThat(mock.secrets.getValue("myapp/qa").description).isEqualTo("QA config")
        service.createSecret(connection, SecretScope("myapp", "dev"), "C", SecretValue("3"), "Dev config")
        assertThat(mock.secrets.getValue("myapp/dev").description).isEqualTo("Dev config")
    }

    fun `test rotation info is shown and Rotate Now calls RotateSecret only where rotation is on`() {
        val connection = connection()
        val secret = mock.secrets.getValue("myapp/prod")
        secret.rotationEnabled = true
        secret.lastRotated = 1_767_225_600 // 2026-01-01T00:00:00Z
        secret.nextRotation = 1_769_904_000
        mock.rotatedValue = """{"DB_PASSWORD":"rotated"}"""
        val prod = client(connection).listProjects().first { it.id == "myapp" }.environments.first { it.slug == "prod" }
        val rotation = prod.metadata!!.rotation!!
        assertThat(rotation.enabled).isTrue()
        assertThat(rotation.lastRotated.toString()).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(rotation.nextRotation).isNotNull()

        val service = SecretsService.getInstance()
        service.rotateSecret(connection, SecretScope("myapp", "prod"))
        assertThat(mock.requests("RotateSecret").single().body.get("SecretId").asString).isEqualTo("myapp/prod")
        assertThat(mock.requests("RotateSecret").single().body.get("ClientRequestToken").asString).isNotBlank()
        assertThat(client(connection).listSecrets(SecretScope("myapp", "prod")).single().value.reveal()).isEqualTo("rotated")

        // not on: refused before calling AWS (and AWS refuses too when it wasn't listed)
        assertThat(failure { service.rotateSecret(connection, SecretScope("myapp", "dev")) }.message).contains("Rotation isn't turned on")
        assertThat(mock.requests("RotateSecret")).hasSize(1)

        SecretsAppSettings.getInstance().state.readOnly = true
        assertThat(failure { service.rotateSecret(connection, SecretScope("myapp", "prod")) }.message).contains("Read-only")
        assertThat(mock.requests("RotateSecret")).hasSize(1)
    }

    fun `test a rotation that finishes during a write makes the write fail as changed meanwhile`() {
        val connection = connection()
        mock.changeMeanwhile = "myapp/dev" to """{"DB_PASSWORD":"rotated","PORT":5432}"""
        val conflict = failure { SecretsService.getInstance().updateSecret(connection, SecretScope("myapp", "dev"), "PORT", SecretValue("1")) }
        assertThat(conflict.message).contains("changed meanwhile")
    }

    // ---- IAM Identity Center ----------------------------------------------------------------------------------------------

    private fun ssoConnection(account: String? = "123456789012", role: String? = "Developer") = connection(
        buildMap {
            put(AwsAuthMethods.OPTION_SSO_START_URL, "https://my-company.awsapps.com/start")
            put(AwsAuthMethods.OPTION_SSO_REGION, "eu-central-1")
            account?.let { put(AwsAuthMethods.OPTION_SSO_ACCOUNT_ID, it) }
            role?.let { put(AwsAuthMethods.OPTION_SSO_ROLE_NAME, it) }
        },
        method = AwsAuthMethods.SSO,
    )

    fun `test IAM Identity Center sign-in follows the device flow and keeps tokens only in the password storage`() {
        sso.slowDownFirst = true
        sso.pendingPolls = 2
        val connection = ssoConnection()
        val client = client(connection)
        assertThat(client.hasCredentials()).isFalse()
        assertThat(failure { client.listProjects() }.kind).isEqualTo(Kind.AUTH_REQUIRED)

        val account = client.signIn(interaction)
        assertThat(account).isEqualTo("ASIA…0001 (IAM Identity Center, Developer @ 123456789012, eu-west-1)")
        assertThat(browser).containsExactly("https://device.sso.example/?user_code=ABCD-EFGH")
        assertThat(statuses).anyMatch { it.contains("ABCD-EFGH") }
        val register = sso.requests("/client/register").single().body
        assertThat(register).contains("\"clientType\":\"public\"").contains("sso:account:access")
        assertThat(sso.requests("/device_authorization").single().body).contains("https://my-company.awsapps.com/start")
        // slow_down, two pending, then the token
        assertThat(sso.requests("/token")).hasSize(4)
        assertThat(sso.requests("/token").first().body).contains("urn:ietf:params:oauth:grant-type:device_code")
        val credentials = sso.requests("/federation/credentials").single()
        assertThat(credentials.query).isEqualTo("account_id=123456789012&role_name=Developer")
        assertThat(credentials.bearer).startsWith("access-")

        // the session (tokens and client registration) is in the password storage only; never in a URL
        val stored = CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)!!
        assertThat(stored).contains(credentials.bearer!!).contains(sso.clientSecret).contains("refresh-")
        assertThat(connection.options.values).noneMatch { it.contains("access-") }
        assertThat(sso.requests).allMatch { it.query == null || !it.query.contains("access-") }

        // Secrets Manager is called with the role credentials (kept in memory: no second GetRoleCredentials)
        assertThat(client(connection).listSecrets(SecretScope("myapp", "dev")).first().value.reveal()).isEqualTo("dev-pass")
        assertThat(mock.requests.last().securityToken).isEqualTo("mock-session-token")
        assertThat(sso.requests("/federation/credentials")).hasSize(1)

        // sign-out logs out and forgets everything
        client(connection).signOut()
        assertThat(sso.requests("/logout").single().bearer).isEqualTo(credentials.bearer)
        assertThat(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)).isNull()
        assertThat(client(connection).hasCredentials()).isFalse()
        assertThat(failure { client(connection).listProjects() }.kind).isEqualTo(Kind.AUTH_REQUIRED)
    }

    fun `test the single account and role are picked, and several ask for a choice`() {
        val picked = ssoConnection(account = null, role = null)
        assertThat(client(picked).signIn(interaction)).contains("Developer @ 123456789012")

        sso.accounts["210987654321"] = "Prod account"
        val several = ssoConnection(account = null, role = null)
        val choose = failure { client(several).signIn(interaction) }
        assertThat(choose.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(choose.message).contains("123456789012 (Dev account)").contains("210987654321 (Prod account)")
    }

    fun `test denied and expired device codes end the sign-in`() {
        sso.pollError = "access_denied"
        assertThat(failure { client(ssoConnection()).signIn(interaction) }.message).contains("denied")
        sso.pollError = "expired_token"
        assertThat(failure { client(ssoConnection()).signIn(interaction) }.message).contains("expired")
    }

    fun `test role credentials are fetched again before they expire, and the token is renewed with the refresh token`() {
        sso.credentialsLifetimeMs = 60_000 // inside the five-minute margin: fetched again every time
        val connection = ssoConnection()
        client(connection).signIn(interaction)
        client(connection).listSecrets(SecretScope("myapp", "dev"))
        assertThat(sso.requests("/federation/credentials")).hasSize(2)

        // the access token stops working: renewed once with the refresh token
        sso.revokeAccessTokens()
        assertThat(client(connection).listSecrets(SecretScope("myapp", "prod")).single().value.reveal()).isEqualTo("prod-pass")
        assertThat(sso.requests("/token").last().body).contains("\"grantType\":\"refresh_token\"")
        assertThat(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)).contains(sso.requests("/federation/credentials").last().bearer!!)

        // and when the refresh token is refused too: sign in again
        sso.revokeAccessTokens()
        sso.refreshAccepted = false
        val expired = failure { client(connection).listSecrets(SecretScope("myapp", "stg")) }
        assertThat(expired.kind).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(expired.message).contains("sign in again").doesNotContain("access-").doesNotContain("refresh-")
    }

    fun `test the SSO options are validated`() {
        val method = AwsAuthMethods.ALL.first { it.id == AwsAuthMethods.SSO }
        fun problem(key: String, value: String) = method.options.first { it.key == key }.validate?.invoke(value)
        assertThat(problem(AwsAuthMethods.OPTION_SSO_START_URL, "http://my.awsapps.com/start")).isNotNull()
        assertThat(problem(AwsAuthMethods.OPTION_SSO_START_URL, "https://my.awsapps.com/start")).isNull()
        assertThat(problem(AwsAuthMethods.OPTION_SSO_REGION, "Frankfurt")).isNotNull()
        assertThat(problem(AwsAuthMethods.OPTION_SSO_ACCOUNT_ID, "1234")).isNotNull()
        assertThat(problem(AwsAuthMethods.OPTION_SSO_ACCOUNT_ID, "123456789012")).isNull()
        assertThat(method.setupLinks.map { it.url("x") }).contains("https://console.aws.amazon.com/singlesignon/home")
    }
}
