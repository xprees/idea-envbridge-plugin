package cz.xprees.secrets

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.aws.AwsAuthMethods
import cz.xprees.secrets.aws.AwsCli
import cz.xprees.secrets.aws.AwsProfiles
import cz.xprees.secrets.aws.AwsSecretNames
import cz.xprees.secrets.aws.AwsSecretsManagerProvider
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.ui.settings.ConnectionDialog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.nio.file.Path

class AwsPlatformTest : BasePlatformTestCase() {
    private lateinit var mock: MockAwsSecretsManager
    private val connections = mutableListOf<SecretConnection>()
    private var tempDir: Path? = null

    private val accessKey = "AKIAMOCKKEY000000001"
    private val secretKey = "mock-secret-key/1"

    override fun setUp() {
        super.setUp()
        mock = MockAwsSecretsManager()
        mock.put("myapp/dev", """{"DB_PASSWORD":"dev-pass","PORT":5432,"FEATURES":{"beta":true},"API_URL":"https://dev.example"}""")
        mock.put("myapp/prod", """{"DB_PASSWORD":"prod-pass"}""")
        mock.put("team/web/dev", """{"PUBLIC_URL":"http://localhost:3000"}""")
        mock.put("legacy-token", "plain-token-value")
        mock.put("myapp/blob", null, byteArrayOf(1, 2, 3))
        mock.put("broken//name", "{}")
    }

    override fun tearDown() {
        try {
            connections.forEach { CredentialVault.removeAll(it.id, AwsAuthMethods.SECRET_KEYS) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            SecretsAppSettings.getInstance().state.readOnly = false
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null; useProjectFile = true; restrictToBoundProject = true }
            SecretsService.getInstance().invalidate()
            AwsProfiles.environment = System::getenv
            tempDir?.toFile()?.deleteRecursively()
            mock.close()
        } finally {
            super.tearDown()
        }
    }

    private fun connection(method: String, options: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap()): SecretConnection {
        val connection = SecretConnection(
            id = "aws-${System.nanoTime()}",
            providerId = AwsSecretsManagerProvider.ID,
            name = "AWS ${connections.size}",
            baseUrl = mock.baseUrl,
            authMethodId = method,
            options = mapOf(AwsAuthMethods.OPTION_REGION to mock.region) + options,
        )
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun keyConnection(options: Map<String, String> = emptyMap(), secret: String = secretKey, id: String = accessKey) = connection(
        AwsAuthMethods.ACCESS_KEY,
        options,
        mapOf(AwsAuthMethods.OPTION_ACCESS_KEY_ID to id, AwsAuthMethods.OPTION_SECRET_ACCESS_KEY to secret),
    )

    private fun client(connection: SecretConnection) = SecretsService.getInstance().client(connection)

    private val interaction = object : SignInInteraction {
        override fun openBrowser(url: String) = Unit
        override fun isCancelled(): Boolean = false
    }

    private fun failure(call: () -> Unit): SecretProviderException = try {
        call()
        error("no exception")
    } catch (e: SecretProviderException) {
        e
    }

    private fun json(name: String) = JsonParser.parseString(mock.current(name)!!).asJsonObject

    private val dev = SecretScope("myapp", "dev")
    private val prod = SecretScope("myapp", "prod")

    private fun temp(): Path = tempDir ?: Files.createTempDirectory("aws-test").also { tempDir = it }

    fun `test provider is registered with its grammar, regional presets and setup links`() {
        val provider = SecretProvider.byScheme("aws-sm")
        assertThat(provider).isInstanceOf(AwsSecretsManagerProvider::class.java)
        assertThat(provider!!.supportsWrites).isTrue()
        assertThat(provider.instancePresets).contains("eu-west-1 (Ireland)" to "https://secretsmanager.eu-west-1.amazonaws.com")
        assertThat(provider.instancePresets.map { it.second }).allMatch { SecretConnection.normalizeBaseUrl(it) == it }
        assertThat(provider.authMethods.map { it.id }).containsExactly(AwsAuthMethods.ACCESS_KEY, AwsAuthMethods.PROFILE, AwsAuthMethods.ENVIRONMENT)
        for (method in provider.authMethods) {
            assertThat(method.setupLinks).describedAs(method.id).isNotEmpty()
            method.setupLinks.forEach { assertThat(ConnectionDialog.isWebPage(it.url(mock.baseUrl)!!)).isTrue() }
        }
        assertThat(provider.authMethod(AwsAuthMethods.ACCESS_KEY)!!.setupLinks.first().url("x")).isEqualTo("https://console.aws.amazon.com/iam/home#/security_credentials")
        // credentials are secret options only (PasswordSafe), never settings
        assertThat(provider.authMethods.flatMap { it.options }.map { it.key }).doesNotContain(*AwsAuthMethods.SECRET_KEYS.toTypedArray())
        assertThat(SecretReference.parse("aws-sm://myapp/dev#DB_PASSWORD", listOf("aws-sm"))!!.projectId).isEqualTo("myapp")
    }

    fun `test the region comes from the option or the endpoint, and the dashboard from the region`() {
        fun conn(url: String, region: String? = null) =
            SecretConnection("c", AwsSecretsManagerProvider.ID, "c", url, AwsAuthMethods.ACCESS_KEY, region?.let { mapOf(AwsAuthMethods.OPTION_REGION to it) }.orEmpty())
        assertThat(AwsSecretsManagerProvider.region(conn("https://secretsmanager.eu-central-1.amazonaws.com"))).isEqualTo("eu-central-1")
        assertThat(AwsSecretsManagerProvider.region(conn("https://secretsmanager-fips.us-gov-west-1.amazonaws.com"))).isEqualTo("us-gov-west-1")
        assertThat(AwsSecretsManagerProvider.region(conn("https://vpce-0abc-xyz.secretsmanager.ap-south-1.vpce.amazonaws.com"))).isEqualTo("ap-south-1")
        assertThat(AwsSecretsManagerProvider.region(conn("http://localhost:4566", "us-east-1"))).isEqualTo("us-east-1")
        assertThat(failure { AwsSecretsManagerProvider.region(conn("http://localhost:4566")) }.message).contains("set the Region")
        assertThat(failure { AwsSecretsManagerProvider.region(conn("http://localhost:4566", "Ireland")) }.kind).isEqualTo(Kind.CONFIGURATION)

        val provider = AwsSecretsManagerProvider()
        val aws = conn("https://secretsmanager.eu-west-1.amazonaws.com")
        assertThat(provider.dashboardUrl(aws, SecretScope("team/web", "dev")))
            .isEqualTo("https://eu-west-1.console.aws.amazon.com/secretsmanager/secret?name=team%2Fweb%2Fdev&region=eu-west-1")
        assertThat(provider.dashboardUrl(aws, null)).isEqualTo("https://eu-west-1.console.aws.amazon.com/secretsmanager/listsecrets?region=eu-west-1")
        assertThat(provider.dashboardUrl(conn("http://localhost:4566", "us-east-1"), SecretScope("a", "b"))).isNull()
    }

    fun `test access keys list prefixes, environments and keys, every request correctly signed`() {
        val connection = keyConnection()
        val client = client(connection)
        assertThat(client.hasCredentials()).isTrue()
        assertThat(client.signIn(interaction)).isEqualTo("AKIA…0001 (eu-west-1)")

        val projects = client.listProjects()
        // prefixes sorted, names without a prefix last; a name the mapping can't express is left out
        assertThat(projects.map { it.id }).containsExactly("myapp", "team/web", AwsSecretNames.NO_PREFIX)
        assertThat(projects.first { it.id == "myapp" }.environments.map { it.slug }).containsExactly("blob", "dev", "prod")
        assertThat(projects.last().name).isEqualTo("(no prefix)")
        assertThat(projects.last().environments.map { it.slug }).containsExactly("legacy-token")

        assertThat(client.listFolders(dev)).isEmpty()
        val secrets = client.listSecrets(dev).associate { it.key to it.value.reveal() }
        // keys in the secret's order; non-string values as their JSON
        assertThat(secrets.keys).containsExactly("DB_PASSWORD", "PORT", "FEATURES", "API_URL")
        assertThat(secrets).containsEntry("DB_PASSWORD", "dev-pass").containsEntry("PORT", "5432").containsEntry("FEATURES", """{"beta":true}""")
        // a plain-string secret is one key named after the name's last segment
        val plain = client.listSecrets(SecretScope(AwsSecretNames.NO_PREFIX, "legacy-token")).single()
        assertThat(plain.key).isEqualTo("legacy-token")
        assertThat(plain.value.reveal()).isEqualTo("plain-token-value")
        assertThat(failure { client.listSecrets(SecretScope("myapp", "dev", "/db")) }.message).contains("no folders")

        // Every request passed the mock's signature check, and the credentials never travel in a URL or body
        val requests = mock.requests
        assertThat(requests).isNotEmpty()
        assertThat(requests).allMatch { it.authorization!!.startsWith("AWS4-HMAC-SHA256 Credential=$accessKey/") && it.authorization.contains("/eu-west-1/secretsmanager/aws4_request") }
        assertThat(requests).allMatch { it.rawQuery == null && !it.rawBody.contains(secretKey) && !it.authorization!!.contains(secretKey) }
        assertThat(requests.first { it.action == "GetSecretValue" }.body.get("SecretId").asString).isEqualTo("myapp/dev")
    }

    fun `test listing follows pages and the name prefix option`() {
        repeat(250) { mock.put("bulk/env-${"%03d".format(it)}", "{}") }
        val projects = client(keyConnection()).listProjects()
        assertThat(projects.first { it.id == "bulk" }.environments).hasSize(250)
        assertThat(mock.requests("ListSecrets")).hasSize(3)

        mock.requests.clear()
        val team = client(keyConnection(mapOf(AwsAuthMethods.OPTION_NAME_PREFIX to "team/"))).listProjects()
        assertThat(team.map { it.id }).containsExactly("team/web")
        val filter = mock.requests("ListSecrets").single().body.getAsJsonArray("Filters").single().asJsonObject
        assertThat(filter.get("Key").asString).isEqualTo("name")
        assertThat(filter.getAsJsonArray("Values").single().asString).isEqualTo("team/")
    }

    fun `test temporary credentials send their session token`() {
        val temp = connection(
            AwsAuthMethods.ACCESS_KEY,
            secrets = mapOf(
                AwsAuthMethods.OPTION_ACCESS_KEY_ID to "ASIAMOCKTEMP00000001",
                AwsAuthMethods.OPTION_SECRET_ACCESS_KEY to "mock-temp-secret/2",
                AwsAuthMethods.OPTION_SESSION_TOKEN to "mock-session-token",
            ),
        )
        assertThat(client(temp).listSecrets(prod).single().value.reveal()).isEqualTo("prod-pass")
        assertThat(mock.requests.last().securityToken).isEqualTo("mock-session-token")
        assertThat(mock.requests.last().authorization).contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token;x-amz-target")

        CredentialVault.remove(temp.id, CredentialVault.secretOptionSlot(AwsAuthMethods.OPTION_SESSION_TOKEN))
        assertThat(failure { client(temp).listSecrets(prod) }.kind).isEqualTo(Kind.AUTH_REQUIRED)
    }

    fun `test wrong credentials and refused requests map to clear errors without secrets`() {
        val wrongSecret = failure { client(keyConnection(secret = "not-the-secret")).signIn(interaction) }
        assertThat(wrongSecret.kind).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(wrongSecret.message).contains("InvalidSignatureException").doesNotContain("not-the-secret")
        assertThat(failure { client(keyConnection(id = "AKIAUNKNOWN000000000")).listProjects() }.message).contains("UnrecognizedClientException")
        assertThat(failure { client(connection(AwsAuthMethods.ACCESS_KEY)).listProjects() }.kind).isEqualTo(Kind.CONFIGURATION)

        val client = client(keyConnection())
        mock.denied += "GetSecretValue:myapp/prod"
        val denied = failure { client.listSecrets(prod) }
        assertThat(denied.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(denied.message).contains("access denied").contains("AccessDeniedException")

        val missing = failure { client.listSecrets(SecretScope("myapp", "nope")) }
        assertThat(missing.kind).isEqualTo(Kind.NOT_FOUND)
        assertThat(missing.message).contains("Reading myapp/nope").contains("ResourceNotFoundException")

        mock.throttleNext = true
        val throttled = failure { client.listSecrets(dev) }
        assertThat(throttled.kind).isEqualTo(Kind.SERVER)
        assertThat(throttled.message).contains("throttled")

        val binary = failure { client.listSecrets(SecretScope("myapp", "blob")) }
        assertThat(binary.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(binary.message).contains("binary")

        // signing out forgets the stored key
        client.signOut()
        assertThat(client.hasCredentials()).isFalse()
    }

    fun `test a profile with static keys is read at use time after signing in`() {
        val dir = temp()
        Files.writeString(dir.resolve("credentials"), "[default]\naws_access_key_id = AKIAOTHER\naws_secret_access_key = other\n\n[dev]\naws_access_key_id = $accessKey\naws_secret_access_key = $secretKey\n")
        Files.writeString(dir.resolve("config"), "[profile dev]\nregion = eu-west-1\ns3 =\n  max_concurrent_requests = 20\n")
        AwsProfiles.environment = { mapOf("AWS_SHARED_CREDENTIALS_FILE" to dir.resolve("credentials").toString(), "AWS_CONFIG_FILE" to dir.resolve("config").toString()) }

        val connection = connection(AwsAuthMethods.PROFILE, mapOf(AwsAuthMethods.OPTION_PROFILE to "dev"))
        val client = client(connection)
        assertThat(client.hasCredentials()).isFalse()
        assertThat(failure { client.listProjects() }.kind).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(client.signIn(interaction)).isEqualTo("AKIA…0001 (profile dev, eu-west-1)")
        assertThat(client.hasCredentials()).isTrue()
        assertThat(client.listSecrets(prod).single().value.reveal()).isEqualTo("prod-pass")
        // nothing but the marker is stored: the keys stay in the user's file
        assertThat(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)).doesNotContain(secretKey)
        AwsAuthMethods.SECRET_KEYS.forEach { assertThat(CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(it))).isNull() }

        assertThat(failure { client(connection(AwsAuthMethods.PROFILE, mapOf(AwsAuthMethods.OPTION_PROFILE to "nope"))).signIn(interaction) }.message)
            .contains("No AWS profile 'nope'")
        client.signOut()
        assertThat(client.hasCredentials()).isFalse()
    }

    fun `test SSO and role profiles get temporary credentials from the AWS CLI`() {
        val dir = temp()
        val log = dir.resolve("aws.log")
        val cli = dir.resolve("aws")
        Files.writeString(
            cli,
            """
            |#!/bin/sh
            |echo "${'$'}*" >> '$log'
            |case "${'$'}4" in
            |  dev-sso) printf '%s' '{"Version":1,"AccessKeyId":"ASIAMOCKTEMP00000001","SecretAccessKey":"mock-temp-secret/2","SessionToken":"mock-session-token","Expiration":"2099-01-01T00:00:00Z"}' ;;
            |  expired) echo "Error when retrieving token from sso: Token has expired and refresh failed" >&2; exit 255 ;;
            |  *) echo "The config profile (${'$'}4) could not be found" >&2; exit 255 ;;
            |esac
            """.trimMargin(),
        )
        cli.toFile().setExecutable(true)
        Files.writeString(dir.resolve("config"), "[profile dev-sso]\nsso_session = corp\nsso_account_id = 123456789012\nsso_role_name = Dev\n\n[profile expired]\nrole_arn = arn:aws:iam::123456789012:role/x\nsource_profile = dev-sso\n")
        AwsProfiles.environment = { mapOf("AWS_SHARED_CREDENTIALS_FILE" to dir.resolve("none").toString(), "AWS_CONFIG_FILE" to dir.resolve("config").toString()) }

        val connection = connection(AwsAuthMethods.PROFILE, mapOf(AwsAuthMethods.OPTION_PROFILE to "dev-sso", AwsAuthMethods.OPTION_CLI_PATH to cli.toString()))
        val client = client(connection)
        assertThat(client.signIn(interaction)).isEqualTo("ASIA…0001 (profile dev-sso, eu-west-1)")
        assertThat(client.listSecrets(dev).map { it.key }).contains("DB_PASSWORD")
        assertThat(mock.requests.last().securityToken).isEqualTo("mock-session-token")
        // argv names only the profile; the credentials came over stdout and are reused from memory
        assertThat(Files.readAllLines(log)).containsExactly("configure export-credentials --profile dev-sso --format process")
        assertThat(CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION)).doesNotContain("mock-temp-secret")

        val expired = connection(AwsAuthMethods.PROFILE, mapOf(AwsAuthMethods.OPTION_PROFILE to "expired", AwsAuthMethods.OPTION_CLI_PATH to cli.toString()))
        val sso = failure { client(expired).signIn(interaction) }
        assertThat(sso.kind).isEqualTo(Kind.AUTH_REQUIRED)
        assertThat(sso.message).contains("aws sso login --profile expired")

        assertThat(failure { client(connection(AwsAuthMethods.PROFILE, mapOf(AwsAuthMethods.OPTION_PROFILE to "dev-sso", AwsAuthMethods.OPTION_CLI_PATH to dir.resolve("missing").toString()))).signIn(interaction) }.message)
            .contains("AWS CLI was not found")
        assertThat(AwsCli.failure("aws: error: argument operation: Invalid choice, valid choices are: ...", "x").message).contains("v2.9")
    }

    fun `test environment variables of the IDE`() {
        AwsProfiles.environment = { mapOf("AWS_ACCESS_KEY_ID" to accessKey, "AWS_SECRET_ACCESS_KEY" to secretKey) }
        val connection = connection(AwsAuthMethods.ENVIRONMENT)
        assertThat(client(connection).signIn(interaction)).isEqualTo("AKIA…0001 (environment, eu-west-1)")
        assertThat(client(connection).listSecrets(prod).single().value.reveal()).isEqualTo("prod-pass")
        AwsProfiles.environment = { emptyMap() }
        assertThat(failure { client(connection).listSecrets(prod) }.message).contains("AWS_ACCESS_KEY_ID")
    }

    fun `test keys are created, changed and deleted with check-and-set writes`() {
        val connection = keyConnection()
        val service = SecretsService.getInstance()
        val before = mock.secrets.getValue("myapp/dev").current().first

        assertThat(service.createSecret(connection, dev, "NEW_KEY", SecretValue("v1"))).isEqualTo(WriteOutcome.APPLIED)
        assertThat(json("myapp/dev").get("NEW_KEY").asString).isEqualTo("v1")
        // other keys keep their values and JSON types
        assertThat(json("myapp/dev").get("PORT").asInt).isEqualTo(5432)
        assertThat(json("myapp/dev").getAsJsonObject("FEATURES").get("beta").asBoolean).isTrue()
        // the write went to a pending version made current with RemoveFromVersionId = the version read; no helper label stays
        val put = mock.requests("PutSecretValue").single().body
        assertThat(put.getAsJsonArray("VersionStages").map { it.asString }).containsExactly("ENVBRIDGE_PENDING")
        val move = mock.requests("UpdateSecretVersionStage").first().body
        assertThat(move.get("VersionStage").asString).isEqualTo("AWSCURRENT")
        assertThat(move.get("RemoveFromVersionId").asString).isEqualTo(before)
        assertThat(move.get("MoveToVersionId").asString).isEqualTo(put.get("ClientRequestToken").asString)
        assertThat(mock.stagesOf("myapp/dev").values.flatten()).doesNotContain("ENVBRIDGE_PENDING").contains("AWSCURRENT", "AWSPREVIOUS")
        assertThat(mock.stagesOf("myapp/dev")[before]).containsExactly("AWSPREVIOUS")

        assertThat(failure { service.createSecret(connection, dev, "NEW_KEY", SecretValue("x")) }.message).contains("already has a key NEW_KEY")
        assertThat(failure { service.updateSecret(connection, dev, "MISSING", SecretValue("x")) }.kind).isEqualTo(Kind.NOT_FOUND)

        service.updateSecret(connection, dev, "NEW_KEY", SecretValue("v2"))
        assertThat(json("myapp/dev").get("NEW_KEY").asString).isEqualTo("v2")
        // an unchanged value makes no new version
        val puts = mock.requests("PutSecretValue").size
        service.updateSecret(connection, dev, "PORT", SecretValue("5432"))
        assertThat(mock.requests("PutSecretValue")).hasSize(puts)

        assertThat(service.deleteSecrets(connection, dev, listOf("NEW_KEY")).getValue("NEW_KEY").getOrThrow()).isEqualTo(WriteOutcome.APPLIED)
        assertThat(json("myapp/dev").has("NEW_KEY")).isFalse()
        // the last key leaves an empty object: the AWS secret is never deleted
        service.deleteSecrets(connection, prod, listOf("DB_PASSWORD")).getValue("DB_PASSWORD").getOrThrow()
        assertThat(mock.current("myapp/prod")).isEqualTo("{}")

        // a key in a secret that doesn't exist yet creates it
        service.createSecret(connection, SecretScope("myapp", "qa"), "FIRST", SecretValue("one"))
        assertThat(mock.current("myapp/qa")).isEqualTo("""{"FIRST":"one"}""")
        assertThat(mock.requests("CreateSecret").single().body.get("ClientRequestToken").asString).isNotBlank()
        assertThat(mock.requests("DeleteSecret")).isEmpty()
        // values never appear in what the plugin logs or throws; they only go in request bodies
        assertThat(mock.requests).allMatch { it.rawQuery == null }
    }

    fun `test a key is renamed in one write, keeping its place and type, and taken names are refused before writing`() {
        val connection = keyConnection()
        val service = SecretsService.getInstance()
        service.updateSecret(connection, dev, "PORT", SecretValue("5432"), null, "DB_PORT")
        val renamed = json("myapp/dev")
        assertThat(renamed.keySet()).containsExactly("DB_PASSWORD", "DB_PORT", "FEATURES", "API_URL")
        assertThat(renamed.get("DB_PORT").isJsonPrimitive && renamed.get("DB_PORT").asJsonPrimitive.isNumber).isTrue()
        assertThat(mock.requests("PutSecretValue")).hasSize(1)

        service.updateSecret(connection, dev, "API_URL", SecretValue("https://new.example"), null, "BASE_URL")
        assertThat(json("myapp/dev").get("BASE_URL").asString).isEqualTo("https://new.example")

        val writes = mock.requests("PutSecretValue").size
        assertThat(failure { service.updateSecret(connection, dev, "DB_PORT", SecretValue("1"), null, "DB_PASSWORD") }.message).contains("already has a key DB_PASSWORD")
        assertThat(failure { service.updateSecret(connection, dev, "DB_PORT", SecretValue("1"), null, " ") }.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(failure { service.updateSecret(connection, dev, "MISSING", SecretValue("1"), null, "OTHER") }.kind).isEqualTo(Kind.NOT_FOUND)
        assertThat(mock.requests("PutSecretValue")).hasSize(writes)
    }

    fun `test plain-string secrets can change their value but are never converted or deleted`() {
        val connection = keyConnection()
        val service = SecretsService.getInstance()
        val legacy = SecretScope(AwsSecretNames.NO_PREFIX, "legacy-token")
        service.updateSecret(connection, legacy, "legacy-token", SecretValue("rotated"))
        assertThat(mock.current("legacy-token")).isEqualTo("rotated")
        assertThat(failure { service.updateSecret(connection, legacy, "legacy-token", SecretValue("x"), null, "TOKEN") }.message).contains("can't be renamed")
        assertThat(failure { service.createSecret(connection, legacy, "OTHER", SecretValue("x")) }.message).contains("plain string")
        assertThat(service.deleteSecrets(connection, legacy, listOf("legacy-token")).getValue("legacy-token").exceptionOrNull()!!.message).contains("never deletes")
        assertThat(mock.current("legacy-token")).isEqualTo("rotated")
    }

    fun `test a concurrent change is refused by the check-and-set, and nothing changes`() {
        val connection = keyConnection()
        mock.changeMeanwhile = "myapp/dev" to """{"DB_PASSWORD":"changed-by-someone-else"}"""
        val conflict = failure { SecretsService.getInstance().updateSecret(connection, dev, "DB_PASSWORD", SecretValue("mine")) }
        assertThat(conflict.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(conflict.message).contains("changed meanwhile").contains("Refresh").doesNotContain("mine")
        assertThat(json("myapp/dev").get("DB_PASSWORD").asString).isEqualTo("changed-by-someone-else")
        // the version that lost keeps no label, so AWS cleans it up
        assertThat(mock.stagesOf("myapp/dev").values.flatten()).doesNotContain("ENVBRIDGE_PENDING")
    }

    fun `test writes need UpdateSecretVersionStage and say so`() {
        val connection = keyConnection()
        mock.denied += "UpdateSecretVersionStage:myapp/dev"
        val denied = failure { SecretsService.getInstance().createSecret(connection, dev, "K", SecretValue("v")) }
        assertThat(denied.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(denied.message).contains("secretsmanager:UpdateSecretVersionStage").contains("Nothing was changed")
        assertThat(json("myapp/dev").has("K")).isFalse()
    }

    fun `test moving copies to the destination first, refuses conflicts, and reports keys it could not remove`() {
        val connection = keyConnection()
        val service = SecretsService.getInstance()
        val entries = client(connection).listSecrets(dev).filter { it.key in setOf("API_URL", "PORT") }
        val result = service.moveSecrets(connection, dev, entries, prod, overwrite = false)
        assertThat(result.moved).containsExactly("PORT", "API_URL")
        assertThat(json("myapp/prod").get("PORT").asInt).isEqualTo(5432)
        assertThat(json("myapp/dev").has("PORT")).isFalse()

        // a key at the destination stops the move before anything is written
        val password = client(connection).listSecrets(dev).filter { it.key == "DB_PASSWORD" }
        val writes = mock.requests("PutSecretValue").size
        assertThat(failure { service.moveSecrets(connection, dev, password, prod, overwrite = false) }.message).contains("already has \"DB_PASSWORD\"")
        assertThat(mock.requests("PutSecretValue")).hasSize(writes)
        assertThat(json("myapp/prod").get("DB_PASSWORD").asString).isEqualTo("prod-pass")

        // the source can't be written: copied, kept in both, reported
        mock.denied += "PutSecretValue:myapp/dev"
        val partial = service.moveSecrets(connection, dev, password, prod, overwrite = true)
        assertThat(partial.moved).isEmpty()
        assertThat(partial.notRemoved).containsOnlyKeys("DB_PASSWORD")
        assertThat(json("myapp/prod").get("DB_PASSWORD").asString).isEqualTo("dev-pass")
        assertThat(json("myapp/dev").get("DB_PASSWORD").asString).isEqualTo("dev-pass")

        // to an environment that doesn't exist yet: the secret is created
        mock.denied.clear()
        service.moveSecrets(connection, prod, client(connection).listSecrets(prod).filter { it.key == "PORT" }, SecretScope("team/web", "stg"), overwrite = false)
        assertThat(mock.current("team/web/stg")).isEqualTo("""{"PORT":5432}""")
    }

    fun `test copying writes the destination once and never overwrites`() {
        val connection = keyConnection()
        val service = SecretsService.getInstance()
        val entries = client(connection).listSecrets(dev).filter { it.key in setOf("DB_PASSWORD", "FEATURES") }
        val result = service.copySecrets(connection, dev, entries, prod)
        assertThat(result.copied).containsExactly("FEATURES")
        assertThat(result.failed).containsOnlyKeys("DB_PASSWORD")
        assertThat(json("myapp/prod").get("DB_PASSWORD").asString).isEqualTo("prod-pass")
        assertThat(json("myapp/prod").getAsJsonObject("FEATURES").get("beta").asBoolean).isTrue()
        assertThat(json("myapp/dev").has("FEATURES")).isTrue()
        assertThat(mock.requests("PutSecretValue")).hasSize(1)
    }

    fun `test new environments are new secrets holding an empty object`() {
        val connection = keyConnection()
        val created = SecretsService.getInstance().createEnvironment(connection, "myapp", "Staging", "stg")
        assertThat(created.slug).isEqualTo("stg")
        assertThat(mock.current("myapp/stg")).isEqualTo("{}")
        assertThat(mock.requests("CreateSecret").single().body.get("Description").asString).isEqualTo("Staging")
        assertThat(client(connection).listProjects().first { it.id == "myapp" }.environments.map { it.slug }).contains("stg")
        assertThat(failure { SecretsService.getInstance().createEnvironment(connection, "myapp", "x", "a/b") }.message).contains("without /")
        assertThat(failure { SecretsService.getInstance().createEnvironment(connection, "myapp", "dev", "dev") }.message).contains("ResourceExistsException")
    }

    fun `test read-only blocks every write before anything is sent`() {
        val connection = keyConnection()
        SecretsAppSettings.getInstance().state.readOnly = true
        assertThat(SecretsService.getInstance().writesAllowed(connection)).isFalse()
        assertThatThrownBy { SecretsService.getInstance().createSecret(connection, dev, "K", SecretValue("v")) }.hasMessageContaining("Read-only")
        assertThatThrownBy { SecretsService.getInstance().createEnvironment(connection, "myapp", "qa", "qa") }.hasMessageContaining("Read-only")
        assertThat(mock.requests).isEmpty()
    }

    fun `test launches resolve aws-sm references against the bound prefix and environment`() {
        val connection = keyConnection()
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            projectId = "myapp"
            environment = "dev"
            path = null
            useProjectFile = false
            restrictToBoundProject = false
        }
        val resolver = LaunchSecretResolver.getInstance(project)
        val result = resolver.resolve(
            resolver.plan(
                linkedMapOf(
                    "DB_PASSWORD" to "aws-sm://#DB_PASSWORD",
                    "PROD_PASSWORD" to "aws-sm://myapp/prod#DB_PASSWORD",
                    "PROD_SHORT" to "aws-sm://#DB_PASSWORD?env=prod",
                    "WEB" to "aws-sm://team/web/dev#*",
                    "TOKEN" to "aws-sm://legacy-token#legacy-token",
                    "PLAIN" to "x",
                ),
            ),
        )
        assertThat(result.environment)
            .containsEntry("DB_PASSWORD", "dev-pass")
            .containsEntry("PROD_PASSWORD", "prod-pass")
            .containsEntry("PROD_SHORT", "prod-pass")
            .containsEntry("PUBLIC_URL", "http://localhost:3000")
            .containsEntry("TOKEN", "plain-token-value")
            .containsEntry("PLAIN", "x")
            .doesNotContainKey("WEB")

        // the short form follows the selected environment
        ProjectSecretsSettings.getInstance(project).update { environment = "prod" }
        assertThat(resolver.resolve(resolver.plan(mapOf("DB_PASSWORD" to "aws-sm://#DB_PASSWORD"))).environment).containsEntry("DB_PASSWORD", "prod-pass")

        // fail closed
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("X" to "aws-sm://myapp/dev#MISSING"))) }.isInstanceOf(SecretProviderException::class.java)

        // "Only use the bound project" keeps references in the bound prefix
        ProjectSecretsSettings.getInstance(project).update { restrictToBoundProject = true }
        assertThatThrownBy { resolver.resolve(resolver.plan(mapOf("WEB" to "aws-sm://team/web/dev#PUBLIC_URL"))) }.hasMessageContaining("bound to myapp")
    }
}
