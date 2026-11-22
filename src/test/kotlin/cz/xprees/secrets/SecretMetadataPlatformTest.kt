package cz.xprees.secrets

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.RotationInfo
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.model.SecretReminder
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.infisical.InfisicalAuthMethods
import cz.xprees.secrets.infisical.InfisicalProvider
import cz.xprees.secrets.onepassword.OnePasswordAuthMethods
import cz.xprees.secrets.OnePasswordFixtures.DEV
import cz.xprees.secrets.onepassword.OnePasswordProvider
import cz.xprees.secrets.ui.secrets.MetadataFields
import cz.xprees.secrets.ui.toolwindow.SecretMetadataText
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.ZoneOffset

/**
 * Secret metadata beyond values: Infisical tags (shown, picked in the dialogs, written as `tagIds`), secret rotations and
 * reminders; Vault KV v2 `custom_metadata` (read on demand, edited); 1Password item tags and the "Only items tagged" option.
 * Against [MockInfisical], [MockVault], [FakeOp] and [MockOnePasswordConnect], including permission errors.
 */
class SecretMetadataPlatformTest : BasePlatformTestCase() {
    private lateinit var infisical: MockInfisical
    private lateinit var vault: MockVault
    private lateinit var connect: MockOnePasswordConnect
    private var op: FakeOp? = null
    private val connections = mutableListOf<SecretConnection>()

    override fun setUp() {
        super.setUp()
        infisical = MockInfisical()
        vault = MockVault()
        connect = MockOnePasswordConnect()
    }

    override fun tearDown() {
        try {
            val keys = listOf(
                InfisicalAuthMethods.OPTION_CLIENT_SECRET, VaultAuthMethods.OPTION_SECRET_ID,
                OnePasswordAuthMethods.OPTION_CONNECT_TOKEN, OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN,
            )
            connections.forEach { CredentialVault.removeAll(it.id, keys) }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            SecretsAppSettings.getInstance().state.readOnly = false
            SecretsService.getInstance().invalidate()
            infisical.close()
            vault.close()
            connect.close()
            op?.close()
        } finally {
            super.tearDown()
        }
    }

    private fun register(connection: SecretConnection, secrets: Map<String, String>): SecretConnection {
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun infisicalConnection() = register(
        SecretConnection(
            "meta-inf-${System.nanoTime()}", InfisicalProvider.ID, "Infisical", infisical.baseUrl, InfisicalAuthMethods.MACHINE_IDENTITY,
            mapOf(InfisicalAuthMethods.OPTION_CLIENT_ID to infisical.clientId),
        ),
        mapOf(InfisicalAuthMethods.OPTION_CLIENT_SECRET to infisical.clientSecret),
    )

    private fun vaultConnection() = register(
        SecretConnection(
            "meta-vault-${System.nanoTime()}", VaultProvider.ID, "Vault", vault.baseUrl, VaultAuthMethods.APPROLE,
            mapOf(VaultAuthMethods.OPTION_ROLE_ID to vault.roleId),
        ),
        mapOf(VaultAuthMethods.OPTION_SECRET_ID to vault.secretId),
    )

    private fun connectConnection(options: Map<String, String> = emptyMap()) = register(
        SecretConnection("meta-op-${System.nanoTime()}", OnePasswordProvider.ID, "1Password", connect.baseUrl, OnePasswordAuthMethods.CONNECT, options),
        mapOf(OnePasswordAuthMethods.OPTION_CONNECT_TOKEN to connect.token),
    )

    private fun cliConnection(options: Map<String, String> = emptyMap()): SecretConnection {
        val fake = op ?: FakeOp().also { op = it }
        return register(
            SecretConnection(
                "meta-cli-${System.nanoTime()}", OnePasswordProvider.ID, "1Password CLI", "http://localhost:8080", OnePasswordAuthMethods.DESKTOP_APP,
                mapOf(OnePasswordAuthMethods.OPTION_OP_PATH to fake.executable.toString()) + options,
            ),
            emptyMap(),
        )
    }

    private val service get() = SecretsService.getInstance()

    private fun failure(call: () -> Unit): SecretProviderException =
        runCatching(call).exceptionOrNull() as? SecretProviderException ?: error("expected a SecretProviderException")

    // ---- Infisical -----------------------------------------------------------------------------------------------------

    private val tagged = SecretScope("p1", "dev", "/tagged")

    fun `test Infisical listings carry tags, rotation and reminders`() {
        val secrets = service.secrets(infisicalConnection(), tagged).associateBy { it.key }
        assertThat(secrets.getValue("API_TOKEN").metadata).isEqualTo(
            SecretMetadata(tags = mapOf("backend" to "", "critical" to ""), reminder = SecretReminder(30, "Ask the vendor")),
        )
        assertThat(secrets.getValue("DB_PASS").metadata).isEqualTo(SecretMetadata(rotation = RotationInfo(enabled = false, managed = true)))
        assertThat(secrets.getValue("PLAIN").metadata).isNull()
        // The rest of the listing is as before: no metadata where Infisical sends none
        assertThat(service.secrets(connections.single(), SecretScope("p1", "dev", "/")).map { it.metadata }).containsOnlyNulls()

        val provider = SecretProvider.byId(InfisicalProvider.ID)!!
        assertThat(provider.hasSecretDetails(connections.single(), secrets.getValue("DB_PASS"))).isTrue()
        assertThat(provider.hasSecretDetails(connections.single(), secrets.getValue("API_TOKEN"))).isFalse()
    }

    fun `test Infisical tags are offered from the project and written as tagIds`() {
        val connection = infisicalConnection()
        val editor = service.metadataEditor(connection, tagged, "API_TOKEN")!!
        assertThat(editor.tagChoices).containsExactly("backend", "critical", "db")
        assertThat(editor.current).isEqualTo(SecretMetadata(tags = mapOf("backend" to "", "critical" to "")))
        assertThat(editor.freeForm).isFalse()

        assertThat(service.updateMetadata(connection, tagged, "API_TOKEN", SecretMetadata(tags = mapOf("db" to "")))).isEqualTo(WriteOutcome.APPLIED)
        val patch = infisical.requests.last { it.method == "PATCH" }
        assertThat(patch.path).isEqualTo("/api/v4/secrets/API_TOKEN")
        // Only the tags: the value isn't sent again
        assertThat(patch.body).doesNotContain("secretValue")
        assertThat(infisical.tagWrites["dev:/tagged:API_TOKEN"]).containsExactly("t-db")

        // No tags removes them all
        service.updateMetadata(connection, tagged, "API_TOKEN", SecretMetadata())
        assertThat(infisical.tagWrites["dev:/tagged:API_TOKEN"]).isEmpty()

        // A tag the project doesn't have is refused before writing
        val before = infisical.requests.count { it.method == "PATCH" }
        assertThat(failure { service.updateMetadata(connection, tagged, "API_TOKEN", SecretMetadata(tags = mapOf("nope" to ""))) }.message).contains("no tag nope")
        assertThat(infisical.requests.count { it.method == "PATCH" }).isEqualTo(before)
    }

    fun `test new Infisical secrets get their tags in the same request`() {
        val connection = infisicalConnection()
        val editor = service.metadataEditor(connection, SecretScope("p1", "dev", "/"), null)!!
        assertThat(editor.current).isEqualTo(SecretMetadata())
        val outcome = service.createSecret(connection, SecretScope("p1", "dev", "/"), "NEW_KEY", SecretValue("v"), "", SecretMetadata(tags = mapOf("backend" to "", "db" to "")))
        assertThat(outcome).isEqualTo(WriteOutcome.APPLIED)
        assertThat(infisical.tagWrites["dev:/:NEW_KEY"]).containsExactly("t-backend", "t-db")
        assertThat(infisical.requests.filter { it.method == "POST" && it.path.startsWith("/api/v4/secrets/") }).hasSize(1)
        assertThat(infisical.requests.none { it.method == "PATCH" }).isTrue()
    }

    fun `test Infisical tags from older instances and without permission`() {
        val connection = infisicalConnection()
        infisical.legacyTagsOnly = true
        assertThat(service.metadataEditor(connection, tagged, "API_TOKEN")!!.tagChoices).containsExactly("backend", "db")
        assertThat(infisical.requests.map { it.path }).contains("/api/v1/workspace/p1/tags")

        infisical.legacyTagsOnly = false
        infisical.tagsForbidden = true
        val forbidden = failure { service.metadataEditor(connection, tagged, "API_TOKEN") }
        assertThat(forbidden.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(forbidden.message).contains("Tags \"Read\" permission")
        // Read-only: nothing to edit, and nothing is asked
        infisical.tagsForbidden = false
        SecretsAppSettings.getInstance().state.readOnly = true
        val asked = infisical.requests.size
        assertThat(service.metadataEditor(connection, tagged, "API_TOKEN")).isNull()
        assertThat(infisical.requests).hasSize(asked)
        assertThat(failure { service.updateMetadata(connection, tagged, "API_TOKEN", SecretMetadata()) }.message).contains("Read-only")
    }

    fun `test Infisical rotation details are read on demand and Rotate Now uses the rotation's type`() {
        val connection = infisicalConnection()
        val details = service.secretDetails(connection, tagged, "DB_PASS")!!
        assertThat(details.rotation).isEqualTo(
            RotationInfo(true, Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-12-01T10:00:00Z"), managed = true, name = "db-credentials"),
        )
        // Reading details asks for the secret without its value
        val describe = infisical.requests.first { it.path == "/api/v4/secrets" }
        assertThat(describe.query["viewSecretValue"]).isEqualTo("false")
        assertThat(describe.query["includeImports"]).isEqualTo("false")

        service.rotateSecret(connection, tagged, "DB_PASS")
        assertThat(infisical.rotations).containsExactly("postgres-credentials/r1")

        // Not a rotated secret: refused before anything rotates
        assertThat(failure { service.rotateSecret(connection, tagged, "PLAIN") }.message).contains("isn't managed by a secret rotation")
        assertThat(infisical.rotations).hasSize(1)
    }

    fun `test Infisical rotation permission errors are explained`() {
        val connection = infisicalConnection()
        infisical.rotateForbidden = true
        val rotate = failure { service.rotateSecret(connection, tagged, "DB_PASS") }
        assertThat(rotate.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(rotate.message).contains("db-credentials").contains("\"Rotate Secrets\" permission")

        infisical.rotationsForbidden = true
        val list = failure { service.rotateSecret(connection, tagged, "DB_PASS") }
        assertThat(list.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(list.message).contains("Secret Rotation \"Read\" permission")
        assertThat(failure { service.secretDetails(connection, tagged, "DB_PASS") }.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(infisical.rotations).isEmpty()

        SecretsAppSettings.getInstance().state.readOnly = true
        assertThat(failure { service.rotateSecret(connection, tagged, "DB_PASS") }.message).contains("Read-only")
    }

    fun `test rotated Infisical secrets keep their value and name but take comments and tags`() {
        val connection = infisicalConnection()
        val refused = failure { service.updateSecret(connection, tagged, "DB_PASS", SecretValue("mine")) }
        assertThat(refused.message).contains("managed by a secret rotation")
        assertThat(failure { service.updateSecret(connection, tagged, "DB_PASS", SecretValue("rotated-pass"), newKey = "DB_PASSWORD2") }.message)
            .contains("managed by a secret rotation")
        assertThat(infisical.requests.none { it.method == "PATCH" }).isTrue()

        // The value as listed is left alone: only the comment goes
        service.updateSecret(connection, tagged, "DB_PASS", SecretValue("rotated-pass"), comment = "rotated by Infisical")
        assertThat(infisical.requests.last { it.method == "PATCH" }.body).contains("secretComment").doesNotContain("secretValue")
        service.updateMetadata(connection, tagged, "DB_PASS", SecretMetadata(tags = mapOf("db" to "")))
        assertThat(infisical.tagWrites["dev:/tagged:DB_PASS"]).containsExactly("t-db")
    }

    // ---- Vault ---------------------------------------------------------------------------------------------------------

    fun `test Vault custom_metadata is read on demand only, never while listing`() {
        val connection = vaultConnection()
        vault.customMetadata["root:secret/app/db"] = mapOf("description" to "Main database", "owner" to "team-a")
        service.secrets(connection, SecretScope("secret", "root", "/app/db"))
        service.secrets(connection, SecretScope("secret", "root", "/", recursive = true))
        assertThat(vault.requests.filter { it.path.contains("/metadata/") && it.query["list"] != "true" }).isEmpty()

        val details = service.secretDetails(connection, SecretScope("secret", "root", "/app/db"), "password")
        assertThat(details).isEqualTo(SecretMetadata(description = "Main database", tags = mapOf("owner" to "team-a")))
        assertThat(vault.requests.filter { it.path == "/v1/secret/metadata/app/db" && it.method == "GET" && it.query["list"] != "true" }).hasSize(1)
        // A secret without custom_metadata, a folder only, the mount's root
        assertThat(service.secretDetails(connection, SecretScope("secret", "root", "/app/api"), null)).isEqualTo(SecretMetadata())
        assertThat(service.secretDetails(connection, SecretScope("secret", "root", "/other"), null)).isNull()
        assertThat(service.secretDetails(connection, SecretScope("secret", "root", "/"), null)).isNull()
        assertThat(SecretProvider.byId(VaultProvider.ID)!!.hasSecretDetails(connection, null)).isTrue()
    }

    fun `test Vault custom_metadata is edited and KV v1 has none`() {
        val connection = vaultConnection()
        val scope = SecretScope("secret", "root", "/app/db")
        vault.customMetadata["root:secret/app/db"] = mapOf("owner" to "team-a")
        val editor = service.metadataEditor(connection, scope, "password")!!
        assertThat(editor.freeForm).isTrue()
        assertThat(editor.tagChoices).isNull()
        assertThat(editor.current).isEqualTo(SecretMetadata(tags = mapOf("owner" to "team-a")))
        assertThat(editor.note).contains("secret/app/db")

        service.updateMetadata(connection, scope, "password", SecretMetadata(description = "DB", tags = mapOf("owner" to "team-b", "tier" to "1")))
        assertThat(vault.customMetadata["root:secret/app/db"]).isEqualTo(mapOf("owner" to "team-b", "tier" to "1", "description" to "DB"))
        // The fields themselves weren't written
        assertThat(vault.writes).isEmpty()

        val v1 = SecretScope("kv1", "root", "/app/db")
        assertThat(service.metadataEditor(connection, v1, "password")).isNull()
        assertThat(service.secretDetails(connection, v1, "password")).isNull()
        assertThat(failure { service.updateMetadata(connection, v1, "password", SecretMetadata()) }.message).contains("no metadata")
        assertThat(vault.requests.none { it.path.startsWith("/v1/kv1/metadata") }).isTrue()
    }

    fun `test Vault metadata permission errors name the policy and leave the value edit working`() {
        val connection = vaultConnection()
        val scope = SecretScope("secret", "root", "/app/db")
        vault.metadataReadOnly += "secret"
        val write = failure { service.updateMetadata(connection, scope, "password", SecretMetadata(description = "x")) }
        assertThat(write.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(write.message).contains("policy needs update on secret/metadata/app/db")
        // The value is written through data/, which the policy allows
        service.updateSecret(connection, scope, "password", SecretValue("new-pass"))
        assertThat(service.secrets(connection, scope, fresh = true).first { it.key == "password" }.value.reveal()).isEqualTo("new-pass")

        vault.metadataUnreadable += "secret"
        val read = failure { service.secretDetails(connection, scope, "password") }
        assertThat(read.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(read.message).contains("policy needs read on secret/metadata/app/db")
        assertThat(failure { service.metadataEditor(connection, scope, "password") }.kind).isEqualTo(Kind.FORBIDDEN)
    }

    // ---- 1Password -----------------------------------------------------------------------------------------------------

    fun `test 1Password item tags are shown on items and their fields (Connect)`() {
        val connection = connectConnection()
        val folders = service.folders(connection, SecretScope(DEV, "default", "/")).associateBy { it.name }
        assertThat(folders.getValue("Database").metadata).isEqualTo(SecretMetadata(tags = mapOf("env/dev" to "", "db" to "")))
        assertThat(folders.getValue("Stripe").metadata).isEqualTo(SecretMetadata(tags = mapOf("payments" to "")))
        assertThat(folders.filterKeys { it.startsWith("Dup") }.values.map { it.metadata }).containsOnlyNulls()
        val fields = service.secrets(connection, SecretScope(DEV, "default", "/Database"))
        assertThat(fields.map { it.metadata }.distinct()).containsExactly(SecretMetadata(tags = mapOf("env/dev" to "", "db" to "")))
        // Read-only: nothing to edit
        assertThat(service.metadataEditor(connection, SecretScope(DEV, "default", "/Database"), "password")).isNull()
    }

    fun `test 1Password Only items tagged narrows listings and the keys index (Connect)`() {
        // "env" also matches the nested tag env/dev
        val connection = connectConnection(mapOf(OnePasswordAuthMethods.OPTION_TAGS to "env, payments"))
        assertThat(service.folders(connection, SecretScope(DEV, "default", "/")).map { it.name }).containsExactly("Database", "Stripe")
        val all = service.secrets(connection, SecretScope(DEV, "default", "/", recursive = true))
        assertThat(all.map { it.path }.distinct()).containsExactlyInAnyOrder("/Database", "/Database/Extra", "/Stripe", "/Stripe/Webhooks")

        val onlyDb = connectConnection(mapOf(OnePasswordAuthMethods.OPTION_TAGS to "db"))
        assertThat(service.folders(onlyDb, SecretScope(DEV, "default", "/")).map { it.name }).containsExactly("Database")
        // An item read by name isn't filtered (references name it explicitly)
        assertThat(service.secrets(onlyDb, SecretScope(DEV, "default", "/Stripe")).map { it.key }).contains("credential")
    }

    fun `test 1Password tags and Only items tagged through the CLI`() {
        if (SystemInfo.isWindows) return
        val connection = cliConnection(mapOf(OnePasswordAuthMethods.OPTION_TAGS to "payments"))
        val folders = service.folders(connection, SecretScope(DEV, "default", "/"))
        assertThat(folders.map { it.name }).containsExactly("Stripe")
        assertThat(folders.single().metadata).isEqualTo(SecretMetadata(tags = mapOf("payments" to "")))
        assertThat(service.secrets(connection, SecretScope(DEV, "default", "/Stripe")).map { it.metadata }.distinct())
            .containsExactly(SecretMetadata(tags = mapOf("payments" to "")))
    }

    // ---- Shared UI text ------------------------------------------------------------------------------------------------

    fun `test metadata reads as tooltip lines and badges`() {
        val metadata = SecretMetadata(
            description = "Main <db>",
            tags = mapOf("owner" to "team-a", "critical" to ""),
            rotation = RotationInfo(true, Instant.parse("2026-11-01T10:00:00Z"), null, managed = true, name = "db-credentials"),
            reminder = SecretReminder(30, "Ask the vendor"),
        )
        val lines = SecretMetadataText.lines(metadata, ZoneOffset.UTC)
        assertThat(lines[0]).isEqualTo("Description: Main <db>")
        assertThat(lines[1]).isEqualTo("Tags: owner=team-a, critical")
        assertThat(lines[2]).isEqualTo("Managed by the secret rotation db-credentials: its value changes when it rotates")
        assertThat(lines[3]).isEqualTo("Automatic rotation: on")
        assertThat(lines[4]).startsWith("Last rotated: ")
        assertThat(lines[5]).isEqualTo("Reminder to rotate it every 30 days: Ask the vendor")
        assertThat(lines).hasSize(6)
        assertThat(SecretMetadataText.tooltip("a\nb", lines.take(1))).isEqualTo("<html>a<br>b<br>Description: Main &lt;db&gt;</html>")
        assertThat(SecretMetadataText.badge(metadata)).isEqualTo("rotated  #owner #critical")
        assertThat(SecretMetadataText.badge(SecretMetadata(rotation = RotationInfo(true)))).isEmpty()
        // A managed rotation from a listing (no details yet) doesn't claim on or off
        assertThat(SecretMetadataText.lines(SecretMetadata(rotation = RotationInfo(false, managed = true))))
            .containsExactly("Managed by a secret rotation: its value changes when it rotates")
    }

    fun `test free-form metadata lines are parsed and checked`() {
        assertThat(MetadataFields.parseFreeForm("owner = team-a\n\n tier=1\nflag").getOrThrow()).isEqualTo(mapOf("owner" to "team-a", "tier" to "1", "flag" to ""))
        assertThat(MetadataFields.parseFreeForm("=x").exceptionOrNull()!!.message).contains("has no key")
        assertThat(MetadataFields.parseFreeForm("a=1\na=2").exceptionOrNull()!!.message).contains("a is there twice")
        assertThat(MetadataFields.parseFreeForm("description=x").exceptionOrNull()!!.message).contains("own field")
    }
}
