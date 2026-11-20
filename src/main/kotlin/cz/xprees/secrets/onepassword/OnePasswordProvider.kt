package cz.xprees.secrets.onepassword

import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.reference.ReferenceGrammar
import javax.swing.Icon

/**
 * 1Password (unofficial integration): vaults are projects, items are folders and an item's fields are its secrets. Read
 * through the `op` CLI (with the 1Password desktop app, or a service account) or a 1Password Connect server. Reading only.
 */
class OnePasswordProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "1Password"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    override val referenceScheme: String = "op"
    override val referenceGrammar: ReferenceGrammar get() = OnePasswordGrammar
    override val supportsWrites: Boolean get() = false

    // Only Connect uses the address: Connect servers are self-hosted, most often on the developer's machine or a local container
    override val instancePresets: List<Pair<String, String>> = listOf("1Password Connect on this computer" to "http://localhost:8080")

    override val authMethods: List<AuthMethod> = OnePasswordAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String = ENVIRONMENT

    override fun createClient(connection: SecretConnection): SecretsClient = OnePasswordClient(connection)

    companion object {
        const val ID = "onepassword"

        /** 1Password has no environments: every vault has this one. */
        const val ENVIRONMENT = "default"
    }
}

object OnePasswordAuthMethods {
    const val DESKTOP_APP = "desktop-app"
    const val SERVICE_ACCOUNT = "service-account"
    const val CONNECT = "connect"

    const val OPTION_OP_PATH = "opPath"
    const val OPTION_ACCOUNT = "account"
    const val OPTION_SERVICE_ACCOUNT_TOKEN = "serviceAccountToken"
    const val OPTION_CONNECT_TOKEN = "connectToken"

    private val OP_PATH = AuthOption(OPTION_OP_PATH, "1Password CLI (op):", required = false, hint = "found on PATH or in the usual install locations")

    val ALL = listOf(
        AuthMethod(
            id = DESKTOP_APP,
            displayName = "1Password app (through the CLI)",
            description = "The 1Password CLI (op) asks your 1Password app to approve access (Touch ID, Windows Hello, your system " +
                "password…), so no password or token is entered here. Needs the CLI and, in the app, Settings › Developer › " +
                "Integrate with 1Password CLI.",
            options = listOf(
                AuthOption(OPTION_ACCOUNT, "Account:", required = false, hint = "shorthand, sign-in address or ID; empty: the CLI's default"),
                OP_PATH,
            ),
            usesBaseUrl = false,
        ),
        AuthMethod(
            id = SERVICE_ACCOUNT,
            displayName = "Service account (through the CLI)",
            description = "A 1Password service account token, kept in the IDE's password storage and given to the CLI (op) only in " +
                "its environment (OP_SERVICE_ACCOUNT_TOKEN), never on its command line. The service account sees only the vaults " +
                "it was granted.",
            options = listOf(OP_PATH),
            secretOptions = listOf(AuthOption(OPTION_SERVICE_ACCOUNT_TOKEN, "Service account token:")),
            usesBaseUrl = false,
        ),
        AuthMethod(
            id = CONNECT,
            displayName = "1Password Connect server",
            description = "A self-hosted 1Password Connect server (its address above) and an access token for it.",
            secretOptions = listOf(AuthOption(OPTION_CONNECT_TOKEN, "Access token:")),
        ),
    )
}

/** [SecretsClient] for one 1Password connection (reading only). */
class OnePasswordClient(private val connection: SecretConnection) : SecretsClient {
    private val method = connection.authMethodId

    private val backend: OnePasswordBackend by lazy {
        when (method) {
            OnePasswordAuthMethods.DESKTOP_APP -> OnePasswordCli(connection, serviceAccount = false)
            OnePasswordAuthMethods.SERVICE_ACCOUNT -> OnePasswordCli(connection, serviceAccount = true)
            OnePasswordAuthMethods.CONNECT -> OnePasswordConnect(connection)
            else -> throw SecretProviderException(Kind.CONFIGURATION, "Unknown sign-in method '$method'")
        }
    }

    override fun signIn(interaction: SignInInteraction): String {
        val account = backend.signIn(interaction)
        // The desktop app keeps the session; this only remembers that the user chose to use it (see hasCredentials)
        if (method == OnePasswordAuthMethods.DESKTOP_APP) CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, SIGNED_IN)
        return account
    }

    /**
     * Desktop app: forgets that the user signed in, so nothing runs `op` in the background any more (the app's own
     * authorization of the CLI expires by itself). Service account and Connect: forgets the stored token.
     */
    override fun signOut() {
        when (method) {
            OnePasswordAuthMethods.SERVICE_ACCOUNT ->
                CredentialVault.remove(connection.id, CredentialVault.secretOptionSlot(OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN))
            OnePasswordAuthMethods.CONNECT ->
                CredentialVault.remove(connection.id, CredentialVault.secretOptionSlot(OnePasswordAuthMethods.OPTION_CONNECT_TOKEN))
        }
        CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION)
    }

    override fun hasCredentials(): Boolean = when (method) {
        OnePasswordAuthMethods.DESKTOP_APP -> CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION) != null
        OnePasswordAuthMethods.SERVICE_ACCOUNT -> (backend as OnePasswordCli).hasToken()
        OnePasswordAuthMethods.CONNECT -> (backend as OnePasswordConnect).hasToken()
        else -> false
    }

    override fun listProjects(): List<ProviderProject> {
        val environments = listOf(ProviderEnvironment(slug = OnePasswordProvider.ENVIRONMENT, name = OnePasswordProvider.ENVIRONMENT))
        return backend.vaults().sortedBy { it.name.lowercase() }
            .map { ProviderProject(id = it.id, name = it.name, slug = it.name, environments = environments) }
    }

    /** The vault's items (at `/`); an item's fields are its secrets, sections are not shown as folders. */
    override fun listFolders(scope: SecretScope): List<SecretFolder> {
        if (segments(scope.path).isNotEmpty()) return emptyList()
        return itemSegments(backend.items(scope.projectId)).map { (item, segment) ->
            val name = if (segment == item.id && item.title.isNotBlank()) "${item.title} (${item.id})" else item.title.ifBlank { item.id }
            SecretFolder(name, "/$segment")
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * `/<item>`: every field of the item (top-level fields first, then by section); `/<item>/<section>`: that section's;
     * `/` with `recursive`: every field of every item, at most [MAX_RECURSIVE_ITEMS] items. Fields without a value are left out.
     */
    override fun listSecrets(scope: SecretScope): List<SecretEntry> {
        val segments = segments(scope.path)
        return when {
            segments.isEmpty() -> if (scope.recursive) allFields(scope) else emptyList()
            segments.size <= 2 -> {
                val item = backend.item(scope.projectId, segments[0])
                val section = segments.getOrNull(1)?.let { wanted ->
                    findSection(item, wanted) ?: throw SecretProviderException(Kind.NOT_FOUND, "No section \"$wanted\" in item ${segments[0]}")
                }
                entries(item, segments[0], scope).filter { section == null || it.second?.id == section.id }.map { it.first }
            }
            else -> throw SecretProviderException(Kind.NOT_FOUND, "No such item or section: ${scope.path} (1Password paths are /<item>[/<section>])")
        }
    }

    private fun allFields(scope: SecretScope): List<SecretEntry> {
        val items = backend.items(scope.projectId)
        if (items.size > MAX_RECURSIVE_ITEMS) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "Vault ${scope.projectId} has more than $MAX_RECURSIVE_ITEMS items; import single items (op://<vault>/<item>/*)",
            )
        }
        return itemSegments(items).flatMap { (summary, segment) ->
            val item = try {
                backend.item(scope.projectId, summary.id)
            } catch (e: SecretProviderException) {
                if (e.kind != Kind.NOT_FOUND) throw e
                return@flatMap emptyList() // deleted meanwhile
            }
            entries(item, segment, scope).map { it.first }
        }
    }

    /** The item's fields with values, top-level first, each with its section (null for top-level fields). */
    private fun entries(item: OpItem, itemSegment: String, scope: SecretScope): List<Pair<SecretEntry, OpSection?>> {
        val sectionOrder = item.sections.withIndex().associate { it.value.id to it.index }
        return item.fields
            .filter { it.value.isNotEmpty() }
            .map { field -> field to item.section(field.sectionId)?.takeIf { it.label.isNotBlank() } }
            .sortedBy { (_, section) -> section?.let { sectionOrder[it.id]?.plus(1) } ?: 0 }
            .map { (field, section) ->
                val path = "/" + itemSegment + (section?.let { "/" + sectionSegment(it) }.orEmpty())
                SecretEntry(
                    key = field.label.ifBlank { field.id },
                    value = SecretValue(field.value),
                    path = path,
                    environment = scope.environment,
                    id = field.id,
                ) to section
            }
    }

    private fun findSection(item: OpItem, wanted: String): OpSection? =
        item.sections.firstOrNull { it.label == wanted }
            ?: item.sections.firstOrNull { it.id == wanted }
            ?: item.sections.singleOrNull { it.label.equals(wanted, ignoreCase = true) }

    companion object {
        /** More items than this aren't read for a whole-vault import (each one is a CLI call or request). */
        const val MAX_RECURSIVE_ITEMS = 100

        private const val SIGNED_IN = "desktop-app"

        private fun segments(path: String): List<String> = SecretScope.normalizePath(path).split('/').filter { it.isNotEmpty() }

        /** Whether a title can be a path segment (and a reference segment): no slash, no surrounding blanks, not a star. */
        private fun usable(title: String) = title.isNotBlank() && '/' !in title && title == title.trim() && title != "*" && title != "**"

        /** Each item with its path segment: its title, or its ID when the title is shared with another item or unusable. */
        fun itemSegments(items: List<OpItemSummary>): List<Pair<OpItemSummary, String>> {
            val counts = items.groupingBy { it.title.lowercase() }.eachCount()
            return items.map { it to if (usable(it.title) && counts[it.title.lowercase()] == 1) it.title else it.id }
        }

        private fun sectionSegment(section: OpSection) = if (usable(section.label)) section.label else section.id
    }
}
