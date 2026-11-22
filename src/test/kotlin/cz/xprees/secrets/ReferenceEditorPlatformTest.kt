package cz.xprees.secrets

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.OnePasswordFixtures.DEV
import cz.xprees.secrets.OnePasswordFixtures.DUP1
import cz.xprees.secrets.OnePasswordFixtures.DUP2
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.EditorReferences
import cz.xprees.secrets.editor.MissingSecretInspection
import cz.xprees.secrets.onepassword.OnePasswordAuthMethods
import cz.xprees.secrets.onepassword.OnePasswordProvider
import cz.xprees.secrets.vault.VaultAuth
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat

/**
 * The editor inside `op://` and `vault://` references: completion of vaults / mounts, items / paths and fields from the keys
 * index (names only), hover and the missing-secret inspection matching a vault named by its name, and 1Password labels with
 * spaces.
 */
class ReferenceEditorPlatformTest : BasePlatformTestCase() {
    private val connections = mutableListOf<SecretConnection>()
    private val closeables = mutableListOf<AutoCloseable>()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        VaultAuth.cliTokenFile = null
        // Show the list even for a single match, so tests see every variant
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            val secretKeys = listOf(VaultAuthMethods.OPTION_SECRET_ID, OnePasswordAuthMethods.OPTION_CONNECT_TOKEN)
            connections.forEach { CredentialVault.removeAll(it.id, secretKeys) }
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            KeysIndex.getInstance(project).refreshNow()
            SecretsService.getInstance().invalidate()
            closeables.forEach { it.close() }
        } finally {
            super.tearDown()
        }
    }

    private fun <T : AutoCloseable> closing(value: T): T = value.also { closeables += it }

    private fun connection(providerId: String, baseUrl: String, method: String, options: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap()): SecretConnection {
        val connection = SecretConnection("refs-${System.nanoTime()}", providerId, "Refs ${connections.size}", baseUrl, method, options)
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun bind(connection: SecretConnection, projectId: String, environment: String?, path: String?): KeysIndex.Snapshot {
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            this.projectId = projectId
            this.environment = environment
            this.path = path
            useProjectFile = false
        }
        return KeysIndex.getInstance(project).refreshNow()!!
    }

    /** The variants completion offers at `<caret>` in [text]. */
    private fun variants(text: String): List<String> {
        myFixture.configureByText("refs.txt", text)
        return myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    }

    /** [text] after completing at `<caret>` with a single match inserted right away. */
    private fun completed(text: String): String {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = true
        try {
            myFixture.configureByText("refs.txt", text)
            myFixture.completeBasic()
            return myFixture.editor.document.text
        } finally {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        }
    }

    private fun resolved(text: String, snapshot: KeysIndex.Snapshot): EditorReferences.Resolved? =
        EnvReferences.find(text, EditorReferences.schemes()).single().let { EditorReferences.resolve(project, it, snapshot) }

    private fun missingWarnings(text: String): List<String> {
        myFixture.enableInspections(MissingSecretInspection::class.java)
        myFixture.configureByText("refs.txt", text)
        return myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull(HighlightInfo::getDescription)
    }

    // ---- 1Password ----------------------------------------------------------------------------------------------------------

    private fun onePassword(): SecretConnection {
        val connect = closing(MockOnePasswordConnect())
        return connection(
            OnePasswordProvider.ID, connect.baseUrl, OnePasswordAuthMethods.CONNECT,
            secrets = mapOf(OnePasswordAuthMethods.OPTION_CONNECT_TOKEN to connect.token),
        )
    }

    fun `test op references complete the vault, then its items, then their fields`() {
        bind(onePassword(), DEV, null, null)

        assertThat(variants("X=op://<caret>")).containsExactlyInAnyOrder("Big", "Dev", "Forbidden", "Shared")
        assertThat(variants("X=op://D<caret>")).containsExactly("Dev")
        // items of the bound vault, named by its name (in any case) or its ID; items sharing a title by their IDs
        val items = listOf("Database", "Stripe", DUP1, DUP2)
        assertThat(variants("X=op://Dev/<caret>")).containsExactlyInAnyOrderElementsOf(items)
        assertThat(variants("X=op://dev/<caret>")).containsExactlyInAnyOrderElementsOf(items)
        assertThat(variants("X=op://$DEV/<caret>")).containsExactlyInAnyOrderElementsOf(items)
        assertThat(variants("X=op://Dev/St<caret>")).containsExactly("Stripe")
        // fields of the item, those in sections too (a reference without its section finds them); a section's own
        assertThat(variants("X=op://Dev/Database/<caret>")).containsExactlyInAnyOrder("username", "password", "port", "api key")
        assertThat(variants("X=op://Dev/database/<caret>")).containsExactlyInAnyOrder("username", "password", "port", "api key")
        assertThat(variants("X=op://Dev/Database/Extra/<caret>")).containsExactly("api key")
        assertThat(variants("X=\"op://$DEV/Stripe/<caret>\"")).containsExactlyInAnyOrder("credential", "secret")
        // another vault isn't indexed: nothing rather than the bound vault's names
        assertThat(variants("X=op://Shared/<caret>")).isEmpty()
        assertThat(variants("X=op://Shared/Infra/<caret>")).isEmpty()

        // a label with a space is completed and inserted as it is
        assertThat(completed("X=op://Dev/Database/api k<caret>")).isEqualTo("X=op://Dev/Database/api key")
        assertThat(completed("X=op://Dev/Str<caret>")).isEqualTo("X=op://Dev/Stripe")
    }

    fun `test hover and the inspection match a vault named by its name or its ID`() {
        val snapshot = bind(onePassword(), DEV, null, null)
        assertThat(snapshot.projectAliases).containsExactly("dev")

        // by ID (as Copy reference writes it), by name (as people write it), by name in another case
        assertThat(resolved("op://$DEV/Database/password", snapshot)!!.info).isNotNull()
        assertThat(resolved("op://Dev/Database/password", snapshot)!!.info).isNotNull()
        assertThat(resolved("op://DEV/database/password", snapshot)!!.info).isNotNull()
        // a field in a section, the section left out, and the section given
        assertThat(resolved("A=op://Dev/Database/api key", snapshot)!!.info!!.path).isEqualTo("/Database/Extra")
        assertThat(resolved("A=op://Dev/Database/Extra/api key", snapshot)!!.info).isNotNull()
        // another vault is unknown, not missing; a missing field of the bound vault is missing
        assertThat(resolved("op://Shared/Infra/password", snapshot)!!.indexed).isFalse()
        resolved("op://Dev/Database/nope", snapshot)!!.let { assertThat(it.indexed).isTrue(); assertThat(it.info).isNull() }

        val warnings = missingWarnings(
            "A=op://Dev/Database/nope\n" +
                "B=op://dev/Stripe/credential\n" +
                "C=\"op://Dev/Database/api key\"\n" +
                "D=op://Shared/Infra/password\n" +
                "E=op://Dev/Stripe/no such field # not there\n" +
                "F=op://Dev/Database/api key # in section Extra\n",
        )
        assertThat(warnings).hasSize(2)
        assertThat(warnings).anySatisfy { assertThat(it).contains("nope") }
        assertThat(warnings).anySatisfy { assertThat(it).contains("no such field") }
    }

    // ---- Vault --------------------------------------------------------------------------------------------------------------

    private fun vault(mock: MockVault, options: Map<String, String> = emptyMap()) = connection(
        VaultProvider.ID, mock.baseUrl, VaultAuthMethods.APPROLE,
        mapOf(VaultAuthMethods.OPTION_ROLE_ID to mock.roleId) + options,
        mapOf(VaultAuthMethods.OPTION_SECRET_ID to mock.secretId),
    )

    fun `test vault references complete the mount, path segments and fields after the hash`() {
        val mock = closing(MockVault())
        val snapshot = bind(vault(mock), "secret", null, null)

        assertThat(variants("X=vault://<caret>")).contains("secret")
        assertThat(variants("X=vault://secret/<caret>")).containsExactlyInAnyOrder("app", "other")
        assertThat(variants("X=vault://secret/app/<caret>")).containsExactlyInAnyOrder("db", "api")
        assertThat(variants("X=vault://secret/app/db#<caret>")).containsExactlyInAnyOrder("password", "user")
        assertThat(variants("X=vault://secret/app#<caret>")).containsExactly("name")
        assertThat(variants("X=vault://kv1/app/db#<caret>")).isEmpty()
        assertThat(completed("X=vault://secret/app/db#pa<caret>")).isEqualTo("X=vault://secret/app/db#password")
        assertThat(completed("export DB=vault://secret/app/db#pa<caret> # comment")).isEqualTo("export DB=vault://secret/app/db#password # comment")

        assertThat(resolved("vault://secret/app/db#password # comment", snapshot)!!.info).isNotNull()
    }

    fun `test the short forms of an environment layout complete the environment's paths and keys`() {
        val mock = closing(MockVault())
        mock.mounts["apps"] = "2"
        mock.secrets.getValue("root").putAll(
            mapOf(
                "apps/myapp/dev" to mapOf("MODE" to "development"),
                "apps/myapp/dev/db" to mapOf("password" to "dev-db-pass", "user" to "app"),
                "apps/myapp/prod" to mapOf("MODE" to "production", "PROD_ONLY" to "x"),
            ),
        )
        val connection = vault(mock, mapOf(VaultAuthMethods.OPTION_ENVIRONMENT_LAYOUT to "{project}/{env}", VaultAuthMethods.OPTION_MOUNTS to "apps"))
        bind(connection, "apps/myapp/{env}", "dev", null)

        assertThat(variants("X=vault://#<caret>")).containsExactly("MODE")
        assertThat(variants("X=vault://./<caret>")).containsExactly("db")
        assertThat(variants("X=vault://./db#<caret>")).containsExactlyInAnyOrder("password", "user")
        assertThat(completed("X=vault://./db#us<caret>")).isEqualTo("X=vault://./db#user")
        assertThat(variants("X=vault://<caret>")).contains("apps")
    }
}
