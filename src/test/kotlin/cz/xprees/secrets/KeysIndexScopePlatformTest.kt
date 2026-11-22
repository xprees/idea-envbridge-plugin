package cz.xprees.secrets

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.OnePasswordFixtures.DATABASE
import cz.xprees.secrets.OnePasswordFixtures.DEV
import cz.xprees.secrets.OnePasswordFixtures.STRIPE
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.doppler.DopplerAuthMethods
import cz.xprees.secrets.doppler.DopplerProvider
import cz.xprees.secrets.editor.EditorReferences
import cz.xprees.secrets.editor.MissingSecretInspection
import cz.xprees.secrets.onepassword.OnePasswordAuthMethods
import cz.xprees.secrets.onepassword.OnePasswordItemMemory
import cz.xprees.secrets.onepassword.OnePasswordProvider
import cz.xprees.secrets.vault.VaultAuth
import cz.xprees.secrets.vault.VaultAuthMethods
import cz.xprees.secrets.vault.VaultProvider
import org.assertj.core.api.Assertions.assertThat

/**
 * What the keys index reads per provider ([cz.xprees.secrets.core.provider.SecretProvider.keysIndexScope]) and how often it
 * reads (the cache rule in [KeysIndex]), counted on the fakes. "Before" is what the index read before providers chose its
 * scope: the root of every environment plus the bound environment walked from `/`, read here the same way.
 */
class KeysIndexScopePlatformTest : BasePlatformTestCase() {
    private val connections = mutableListOf<SecretConnection>()
    private val closeables = mutableListOf<AutoCloseable>()

    override fun setUp() {
        super.setUp()
        VaultAuth.cliTokenFile = null
    }

    override fun tearDown() {
        try {
            val secretKeys = listOf(
                VaultAuthMethods.OPTION_SECRET_ID, DopplerAuthMethods.OPTION_TOKEN,
                OnePasswordAuthMethods.OPTION_CONNECT_TOKEN, OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN,
            )
            connections.forEach { CredentialVault.removeAll(it.id, secretKeys) }
            ProjectSecretsSettings.getInstance(project).update { connectionId = null; projectId = null; environment = null; path = null }
            SecretsAppSettings.getInstance().setConnections(emptyList())
            SecretsAppSettings.getInstance().state.cacheTtlSeconds = SecretsAppSettings.DEFAULT_CACHE_TTL_SECONDS
            KeysIndex.getInstance(project).refreshNow()
            SecretsService.getInstance().invalidate()
            closeables.forEach { it.close() }
        } finally {
            super.tearDown()
        }
    }

    private fun <T : AutoCloseable> closing(value: T): T = value.also { closeables += it }

    private fun connection(providerId: String, baseUrl: String, method: String, options: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap()): SecretConnection {
        val connection = SecretConnection("index-${System.nanoTime()}", providerId, "Index ${connections.size}", baseUrl, method, options)
        secrets.forEach { (key, value) -> CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), value) }
        connections += connection
        SecretsAppSettings.getInstance().setConnections(connections.toList())
        return connection
    }

    private fun bind(connection: SecretConnection, projectId: String, environment: String?, path: String?) {
        ProjectSecretsSettings.getInstance(project).update {
            connectionId = connection.id
            this.projectId = projectId
            this.environment = environment
            this.path = path
            useProjectFile = false
        }
        // Waits for the background load the binding change started, so only the measured loads below count
        KeysIndex.getInstance(project).refreshNow()
    }

    /** Requests [count] reports while a periodic index refresh runs: one whose cached listings are all too old. */
    private fun <T> periodicRefresh(count: () -> Int, then: (KeysIndex.Snapshot) -> T): Pair<Int, T> {
        SecretsService.getInstance().invalidate()
        val before = count()
        val snapshot = KeysIndex.getInstance(project).refreshNow()!!
        return (count() - before) to then(snapshot)
    }

    /** The reads the index made before providers chose its scope, with an empty cache, counted with [count]. */
    private fun legacyReads(connection: SecretConnection, projectId: String, environment: String, count: () -> Int): Int {
        val service = SecretsService.getInstance()
        service.invalidate()
        OnePasswordItemMemory.forget(connection.id)
        val before = count()
        val environments = service.projects(connection).first { it.id == projectId }.environments.map { it.slug }
        for (env in (listOf(environment) + environments).distinct()) runCatching { service.secrets(connection, SecretScope(projectId, env, "/")) }
        runCatching { service.secrets(connection, SecretScope(projectId, environment, "/", recursive = true)) }
        return count() - before
    }

    private fun resolved(text: String, snapshot: KeysIndex.Snapshot): EditorReferences.Resolved? =
        EnvReferences.find(text, EditorReferences.schemes()).single().let { EditorReferences.resolve(project, it, snapshot) }

    private fun missingWarnings(text: String): List<String> {
        myFixture.enableInspections(MissingSecretInspection::class.java)
        myFixture.configureByText("refs.txt", text)
        return myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull(HighlightInfo::getDescription)
    }

    private object Interaction : SignInInteraction {
        override fun openBrowser(url: String) = Unit
        override fun isCancelled(): Boolean = false
    }

    // ---- Vault ------------------------------------------------------------------------------------------------------------

    fun `test Vault indexes only the bound secret path, and hover still knows its fields`() {
        val mock = closing(MockVault())
        val connection = connection(
            VaultProvider.ID, mock.baseUrl, VaultAuthMethods.APPROLE,
            mapOf(VaultAuthMethods.OPTION_ROLE_ID to mock.roleId, VaultAuthMethods.OPTION_NAMESPACES to "team-a"),
            mapOf(VaultAuthMethods.OPTION_SECRET_ID to mock.secretId),
        )
        // Secrets and listings only: sign-in and token renewal aren't what the index changes
        val kvRequests = { mock.requests.count { !it.path.startsWith("/v1/auth/") } }
        bind(connection, "secret", null, null)
        // mounts, team-a's KV version (for its root, which holds no fields), LIST /, app, other, GET app, app/api, app/db, other/x
        val legacy = legacyReads(connection, "secret", "root", kvRequests)
        assertThat(legacy).isEqualTo(9)

        // Bound to the mount's root: the whole mount, as before, but no namespace's root read on its own
        val (atRoot, rootSnapshot) = periodicRefresh(kvRequests) { it }
        assertThat(atRoot).isEqualTo(8)
        assertThat(rootSnapshot.folders.keys).containsExactlyInAnyOrder("/app", "/app/api", "/app/db", "/other/x")

        bind(connection, "secret", null, "/app/db")
        val (bound, snapshot) = periodicRefresh(kvRequests) { it }
        // mounts, LIST app/db (no folders below it), GET app/db
        assertThat(bound).isEqualTo(3)
        assertThat(snapshot.folders.keys).containsExactly("/app/db")
        assertThat(snapshot.folders.getValue("/app/db").keys).containsExactlyInAnyOrder("password", "user")
        assertThat(snapshot.environmentNames).containsExactly("root", "team-a")

        // Hover / inlays find the bound secret's fields; another path is unknown, not missing
        assertThat(resolved("vault://secret/app/db#password", snapshot)!!.info).isNotNull()
        assertThat(resolved("vault://secret/other/x#y", snapshot)!!.indexed).isFalse()
        val warnings = missingWarnings("A=vault://secret/app/db#nope\nB=vault://secret/other/x#y\nC=vault://secret/app/db#password\n")
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("nope")

        // Launches don't use the index: a secret outside the bound path still resolves
        val resolver = LaunchSecretResolver.getInstance(project)
        assertThat(resolver.resolve(resolver.plan(mapOf("Y" to "vault://secret/other/x#y"))).environment).containsEntry("Y", "z")
    }

    // ---- 1Password ----------------------------------------------------------------------------------------------------------

    private val canRunFakeOp: Boolean get() = !SystemInfo.isWindows

    private fun desktop(fake: FakeOp): SecretConnection =
        connection(OnePasswordProvider.ID, "http://localhost:8080", OnePasswordAuthMethods.DESKTOP_APP, mapOf(OnePasswordAuthMethods.OPTION_OP_PATH to fake.executable.toString()))
            .also { SecretsService.getInstance().client(it).signIn(Interaction) }

    fun `test 1Password CLI indexes only the bound item`() {
        if (!canRunFakeOp) return
        val fake = closing(FakeOp())
        val connection = desktop(fake)
        val itemCalls = { fake.calls().count { it.startsWith("item ") } }
        bind(connection, DEV, null, null)
        // item list + one item get per item (Dev has four)
        assertThat(legacyReads(connection, DEV, "default", itemCalls)).isEqualTo(5)

        bind(connection, DEV, null, "/Database")
        val before = fake.calls().size
        val (bound, snapshot) = periodicRefresh(itemCalls) { it }
        assertThat(bound).isEqualTo(1)
        assertThat(fake.calls().drop(before).filter { it.startsWith("item ") }.single()).startsWith("item get Database")
        assertThat(snapshot.folders.keys).containsExactlyInAnyOrder("/Database", "/Database/Extra")
        assertThat(snapshot.folders.getValue("/Database").keys).containsExactlyInAnyOrder("username", "password", "port")
        assertThat(snapshot.folders.getValue("/Database/Extra").keys).containsExactly("api key")
        // References as Copy reference writes them (the vault by its ID)
        assertThat(resolved("op://$DEV/Database/password", snapshot)!!.info).isNotNull()
        assertThat(resolved("op://$DEV/Stripe/credential", snapshot)!!.indexed).isFalse()

        // Launches read other items all the same
        val resolver = LaunchSecretResolver.getInstance(project)
        assertThat(resolver.resolve(resolver.plan(mapOf("S" to "op://Dev/Stripe/credential"))).environment).containsEntry("S", "sk_test_123")
    }

    fun `test 1Password CLI bound to a vault re-reads only items whose version changed`() {
        if (!canRunFakeOp) return
        val fake = closing(FakeOp())
        val connection = desktop(fake)
        bind(connection, DEV, null, null)
        val itemCalls = { fake.calls().count { it.startsWith("item ") } }

        // Nothing changed since the load the binding started: the listing only
        val before = fake.calls().size
        val (unchanged, _) = periodicRefresh(itemCalls) { it }
        assertThat(unchanged).isEqualTo(1)
        assertThat(fake.calls().drop(before).filter { it.startsWith("item ") }.single()).startsWith("item list")

        // Another client changes Stripe: it alone is read again, with its new value
        fake.touch(DEV, STRIPE) { item ->
            item.getAsJsonArray("fields").map { it.asJsonObject }.first { it.get("id").asString == "credential" }.addProperty("value", "sk_test_456")
        }
        val (changed, snapshot) = periodicRefresh(itemCalls) { it }
        assertThat(changed).isEqualTo(2)
        assertThat(fake.calls().takeLast(2)).anySatisfy { assertThat(it).startsWith("item get $STRIPE") }
        assertThat(snapshot.folders.getValue("/Stripe").keys).contains("credential")
        val values = SecretsService.getInstance().secrets(connection, SecretScope(DEV, "default", "/", recursive = true))
        assertThat(values.single { it.path == "/Stripe" && it.key == "credential" }.value.reveal()).isEqualTo("sk_test_456")

        // Signing out forgets the items: the next whole-vault listing reads them all
        SecretsService.getInstance().client(connection).signOut()
        SecretsService.getInstance().client(connection).signIn(Interaction)
        SecretsService.getInstance().invalidate()
        val afterSignIn = itemCalls()
        SecretsService.getInstance().secrets(connection, SecretScope(DEV, "default", "/", recursive = true))
        assertThat(itemCalls() - afterSignIn).isEqualTo(5)
    }

    fun `test 1Password Connect bound to a vault re-reads only items whose version changed`() {
        val connect = closing(MockOnePasswordConnect())
        val connection = connection(
            OnePasswordProvider.ID, connect.baseUrl, OnePasswordAuthMethods.CONNECT,
            secrets = mapOf(OnePasswordAuthMethods.OPTION_CONNECT_TOKEN to connect.token),
        )
        val itemRequests = { connect.requests.count { it.path.contains("/items") } }
        bind(connection, DEV, null, null)
        assertThat(legacyReads(connection, DEV, "default", itemRequests)).isEqualTo(5)

        val (unchanged, _) = periodicRefresh(itemRequests) { it }
        assertThat(unchanged).isEqualTo(1)
        assertThat(connect.requests.last().path).isEqualTo("/v1/vaults/$DEV/items")

        connect.touch(DEV, DATABASE)
        val (changed, _) = periodicRefresh(itemRequests) { it }
        assertThat(changed).isEqualTo(2)
        assertThat(connect.requests.takeLast(2).map { it.path }).containsExactly("/v1/vaults/$DEV/items", "/v1/vaults/$DEV/items/$DATABASE")

        // Bound to one item: that item only (named by its title, so Connect lists the vault to find its ID, then reads it)
        bind(connection, DEV, null, "/Stripe")
        val (bound, snapshot) = periodicRefresh(itemRequests) { it }
        assertThat(bound).isEqualTo(2)
        assertThat(snapshot.folders.keys).containsExactlyInAnyOrder("/Stripe", "/Stripe/Webhooks")
    }

    fun `test a write through this IDE makes the next whole-vault listing read the item again`() {
        if (!canRunFakeOp) return
        // The fake op's listing keeps the item's version after an edit: only forgetting the written item shows the new value
        val connection = desktop(closing(FakeOp()))
        val service = SecretsService.getInstance()
        val vault = SecretScope(DEV, "default", "/", recursive = true)
        service.secrets(connection, vault)
        service.updateSecret(connection, SecretScope(DEV, "default", "/Stripe"), "credential", cz.xprees.secrets.core.model.SecretValue("sk_live_1"))
        val values = service.secrets(connection, vault)
        assertThat(values.single { it.path == "/Stripe" && it.key == "credential" }.value.reveal()).isEqualTo("sk_live_1")
    }

    // ---- Doppler ------------------------------------------------------------------------------------------------------------

    private fun doppler(mock: MockDoppler) =
        connection(DopplerProvider.ID, mock.baseUrl, DopplerAuthMethods.TOKEN, secrets = mapOf(DopplerAuthMethods.OPTION_TOKEN to "dp.pt.personal"))

    private fun MockDoppler.secretReads() = requests.count { it.path == "/v3/configs/config/secrets" }

    fun `test Doppler indexes root configs and the bound one, not branch or personal configs`() {
        val mock = closing(MockDoppler())
        val connection = doppler(mock)
        bind(connection, "backend", "dev", null)
        // dev, dev_personal, stg, prd, and dev again for the folder walk
        assertThat(legacyReads(connection, "backend", "dev") { mock.secretReads() }).isEqualTo(5)

        val (reads, snapshot) = periodicRefresh({ mock.secretReads() }) { it }
        assertThat(reads).isEqualTo(3)
        assertThat(mock.requests.filter { it.path == "/v3/configs/config/secrets" }.takeLast(3).map { it.query["config"] })
            .containsExactlyInAnyOrder("dev", "stg", "prd")
        assertThat(snapshot.environments.keys).containsExactly("dev", "stg", "prd")
        // Hover's "defined in" and completion still cover the root configs; switching still offers every config
        assertThat(snapshot.coverage("DB_PASSWORD")).containsExactlyInAnyOrder("dev", "stg", "prd")
        assertThat(snapshot.coverage("API_KEY")).containsExactly("prd")
        assertThat(snapshot.environmentNames).containsExactly("dev", "dev_personal", "stg", "prd")

        // Bound to the personal config: it is indexed too
        bind(connection, "backend", "dev_personal", null)
        assertThat(KeysIndex.getInstance(project).current()!!.environments.keys).containsExactly("dev_personal", "dev", "stg", "prd")
    }

    // ---- The cache rule ---------------------------------------------------------------------------------------------------

    fun `test the index reuses listings younger than its refresh interval, and a cleared cache reads fresh`() {
        val mock = closing(MockDoppler())
        val connection = doppler(mock)
        bind(connection, "backend", "dev", null)
        val index = KeysIndex.getInstance(project)
        assertThat(KeysIndex.refreshIntervalMs()).isEqualTo(10 * 60 * 1000L)

        // Browsing and editor reads keep nothing (TTL 0), but a reload of the index (after a settings or binding change)
        // within its refresh interval reuses what it read
        SecretsAppSettings.getInstance().state.cacheTtlSeconds = 0
        val before = mock.requests.size
        index.invalidate()
        assertNotNull(index.refreshNow())
        assertThat(mock.requests.size - before).isEqualTo(0)
        SecretsService.getInstance().secrets(connection, SecretScope("backend", "dev", "/"))
        assertThat(mock.secretReads()).isGreaterThan(0)

        // Refresh in the tool window clears the cache: the index reads fresh
        val reads = mock.secretReads()
        SecretsService.getInstance().invalidate()
        index.refreshNow()
        assertThat(mock.secretReads() - reads).isEqualTo(3)

        // A cache TTL longer than 10 minutes makes the index refresh less often, never more often than the cache
        SecretsAppSettings.getInstance().state.cacheTtlSeconds = 3600
        assertThat(KeysIndex.refreshIntervalMs()).isEqualTo(3_600_000L)
    }
}
