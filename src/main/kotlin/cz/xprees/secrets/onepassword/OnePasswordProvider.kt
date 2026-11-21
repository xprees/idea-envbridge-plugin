package cz.xprees.secrets.onepassword

import com.google.gson.JsonObject
import cz.xprees.secrets.SecretsBundle
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
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.renameTarget
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.reference.ReferenceGrammar
import javax.swing.Icon

/**
 * 1Password (unofficial integration): vaults are projects, items are folders and an item's fields are its secrets. Read
 * through the `op` CLI (with the 1Password desktop app, or a service account) or a 1Password Connect server, and writes
 * fields (and, at a vault's root, password items) through the same.
 */
class OnePasswordProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "1Password"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    // A namespace / the single environment is account structure, not a selection users switch
    override val switchesEnvironments: Boolean get() = false

    override val referenceScheme: String = "op"
    override val referenceGrammar: ReferenceGrammar get() = OnePasswordGrammar
    override val supportsWrites: Boolean get() = true

    // Only Connect uses the address: Connect servers are self-hosted, most often on the developer's machine or a local container
    override val instancePresets: List<Pair<String, String>> = listOf("1Password Connect on this computer" to "http://localhost:8080")

    override val authMethods: List<AuthMethod> = OnePasswordAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String = ENVIRONMENT

    override fun createClient(connection: SecretConnection): SecretsClient = OnePasswordClient(connection)

    /** A secret created at a vault's root becomes the password item `/<key>`, its value in the field `password`. */
    override fun createdSecretLocation(scope: SecretScope, key: String): Pair<String, String> =
        if (SecretScope.normalizePath(scope.path) == "/") "/$key" to OnePasswordEdits.ROOT_FIELD else scope.path to key

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

    private val OP_PATH = AuthOption(
        OPTION_OP_PATH,
        SecretsBundle.message("auth.onepassword.option.opPath.label"),
        required = false,
        hint = SecretsBundle.message("auth.onepassword.option.opPath.hint"),
    )

    val ALL = listOf(
        AuthMethod(
            id = DESKTOP_APP,
            displayName = SecretsBundle.message("auth.onepassword.desktop.app.name"),
            description = SecretsBundle.message("auth.onepassword.desktop.app.description"),
            options = listOf(
                AuthOption(
                    OPTION_ACCOUNT,
                    SecretsBundle.message("auth.onepassword.option.account.label"),
                    required = false,
                    hint = SecretsBundle.message("auth.onepassword.option.account.hint"),
                ),
                OP_PATH,
            ),
            usesBaseUrl = false,
        ),
        AuthMethod(
            id = SERVICE_ACCOUNT,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink.fixed(SecretsBundle.message("auth.link.op.service"), "https://developer.1password.com/docs/service-accounts/get-started/"),
            ),
            displayName = SecretsBundle.message("auth.onepassword.service.account.name"),
            description = SecretsBundle.message("auth.onepassword.service.account.description"),
            options = listOf(OP_PATH),
            secretOptions = listOf(
                AuthOption(OPTION_SERVICE_ACCOUNT_TOKEN, SecretsBundle.message("auth.onepassword.option.serviceAccountToken.label")),
            ),
            usesBaseUrl = false,
        ),
        AuthMethod(
            id = CONNECT,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink.fixed(SecretsBundle.message("auth.link.op.connect"), "https://developer.1password.com/docs/connect/get-started/"),
            ),
            displayName = SecretsBundle.message("auth.onepassword.connect.name"),
            description = SecretsBundle.message("auth.onepassword.connect.description"),
            secretOptions = listOf(AuthOption(OPTION_CONNECT_TOKEN, SecretsBundle.message("auth.onepassword.option.connectToken.label"))),
        ),
    )
}

/**
 * [SecretsClient] for one 1Password connection. Writes are read-modify-write of one item at a time (see docs/DESIGN.md,
 * "Writes"): a field is created, changed or removed in the item's JSON, which is then saved whole.
 */
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
        // 1Password fields have no comments: the item's notes are every field's comment (Edit Secret changes them)
        val notes = OnePasswordEdits.notes(item)
        return orderedFields(item)
            .filter { (field, _) -> field.value.isNotEmpty() }
            .map { (field, section) ->
                val path = "/" + itemSegment + (section?.let { "/" + sectionSegment(it) }.orEmpty())
                SecretEntry(
                    key = field.label.ifBlank { field.id },
                    value = SecretValue(field.value),
                    comment = notes,
                    path = path,
                    environment = scope.environment,
                    id = field.id,
                ) to section
            }
    }

    /** The item's fields, top-level first (a section without a label counts as top level), each with its labelled section. */
    private fun orderedFields(item: OpItem): List<Pair<OpField, OpSection?>> {
        val sectionOrder = item.sections.withIndex().associate { it.value.id to it.index }
        return item.fields
            .map { field -> field to item.section(field.sectionId)?.takeIf { it.label.isNotBlank() } }
            .sortedBy { (_, section) -> section?.let { sectionOrder[it.id]?.plus(1) } ?: 0 }
    }

    // ---- Writes ----

    /**
     * `/<item>[/<section>]`: adds the concealed field [key] (to the section, else the item's top level), refused when the item
     * has a field labelled [key] anywhere; a [comment] is added to the item's notes as `key: comment`. `/`: creates the
     * password item titled [key] holding [value] in its `password` field, [comment] as its notes; refused when an item with
     * that title exists.
     */
    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        val segments = writableSegments(scope, allowRoot = true)
        if (segments.isEmpty()) {
            if (backend.items(scope.projectId).any { it.title.equals(key, ignoreCase = true) }) {
                throw SecretProviderException(
                    Kind.CONFIGURATION,
                    "Vault ${scope.projectId} already has an item called \"$key\". To add a field to it, choose the folder /$key",
                )
            }
            val created = backend.createItem(scope.projectId, OnePasswordEdits.rootItem(key, value.reveal(), comment))
            OnePasswordEdits.fieldIds(created)?.let { ids ->
                if (OnePasswordEdits.ROOT_FIELD !in ids) throw notApplied("create item $key")
            }
            return WriteOutcome.APPLIED
        }
        edit(scope.projectId, segments[0]) { json, item ->
            val section = segments.getOrNull(1)?.let { requireSection(item, it, segments[0]) }
            if (item.fields.any { keyOf(it) == key }) {
                throw SecretProviderException(
                    Kind.CONFIGURATION,
                    "Item ${segments[0]} already has a field \"$key\" (field labels must be unique within an item for op:// references); edit that one instead",
                )
            }
            val id = OnePasswordEdits.addField(json, key, value.reveal(), "CONCEALED", section)
            if (comment.isNotBlank()) {
                val notes = OnePasswordEdits.notes(item)
                OnePasswordEdits.setNotes(json, (if (notes.isEmpty()) "" else "$notes\n") + "$key: $comment")
            }
            Expect(present = setOf(id))
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Changes the field's value; a [comment] that isn't null replaces the item's notes (shown as every field's comment). A
     * [newKey] changes the field's label in the same save; refused before saving when another field of the item has that
     * label, or when the field is a built-in one (a login's username or password, the notes), whose label 1Password keeps.
     */
    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?, newKey: String?): WriteOutcome {
        val segments = writableSegments(scope, allowRoot = false)
        val rename = renameTarget(key, newKey)
        edit(scope.projectId, segments[0]) { json, item ->
            val field = requireField(item, segments, key)
            if (rename != null) {
                if (field.purpose.isNotEmpty()) {
                    throw SecretProviderException(
                        Kind.CONFIGURATION,
                        "\"$key\" is a built-in field of item ${segments[0]} and 1Password keeps its name; add a field \"$rename\" with New Secret instead",
                    )
                }
                if (item.fields.any { it.id != field.id && keyOf(it) == rename }) {
                    throw SecretProviderException(
                        Kind.CONFIGURATION,
                        "Item ${segments[0]} already has a field \"$rename\" (field labels must be unique within an item for op:// references); choose another name",
                    )
                }
            }
            val target = OnePasswordEdits.field(json, field.id)
            OnePasswordEdits.setValue(target, value.reveal())
            rename?.let { OnePasswordEdits.setLabel(target, it) }
            comment?.let { OnePasswordEdits.setNotes(json, it) }
            Expect(present = setOf(field.id), labels = rename?.let { mapOf(field.id to it) }.orEmpty())
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Removes the field (a built-in one, such as a login's password, is emptied instead). Items themselves are never deleted:
     * at a vault's root there are no secrets to delete.
     */
    override fun deleteSecret(scope: SecretScope, key: String): WriteOutcome {
        val segments = segments(scope.path)
        if (segments.isEmpty()) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "EnvBridge doesn't delete 1Password items, only their fields; delete or archive the item \"$key\" in 1Password",
            )
        }
        writableSegments(scope, allowRoot = false)
        edit(scope.projectId, segments[0]) { json, item ->
            val field = requireField(item, segments, key)
            OnePasswordEdits.removeField(json, field.id)
            Expect(absent = if (field.purpose.isEmpty()) setOf(field.id) else emptySet())
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Copies the fields into the destination item (one save; with [overwrite], a field with the same label there gets the
     * value), then removes them from the source item (a second save). Nothing is removed unless the copy was saved; a failed
     * removal is reported in [MoveResult.notRemoved]. Within one item, the fields just change section (one save).
     */
    override fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        val from = writableSegments(source, allowRoot = false)
        val to = segments(destination.path)
        if (to.isEmpty()) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "Choose an item (or a section of one) as the destination: in 1Password a secret is a field of an item",
            )
        }
        writableSegments(destination, allowRoot = false)
        if (secrets.isEmpty()) return MoveResult(emptyList())

        val sourceJson = backend.itemJson(source.projectId, from[0])
        val sourceItem = OnePasswordJson.item(sourceJson)
        val picked = secrets.map { entry ->
            val field = sourceItem.fields.firstOrNull { entry.id.isNotEmpty() && it.id == entry.id }
                ?: findField(sourceItem, from.getOrNull(1)?.let { findSection(sourceItem, it) }, entry.key)
                ?: throw SecretProviderException(Kind.NOT_FOUND, "No field \"${entry.key}\" in item ${from[0]}; refresh and try again")
            entry.key to field
        }
        val keys = picked.map { it.first }
        val sourceVault = vaultOf(sourceJson, source.projectId)

        // Within one item: change the fields' section in one save
        val sameVault = sourceVault == destination.projectId || source.projectId == destination.projectId
        fun moveWithinSourceItem(): MoveResult {
            val section = to.getOrNull(1)?.let { requireSection(sourceItem, it, to[0]) }
            picked.forEach { (_, field) -> OnePasswordEdits.setSection(OnePasswordEdits.field(sourceJson, field.id), section) }
            save(source.projectId, sourceJson, Expect(present = picked.map { it.second.id }.toSet()))
            return MoveResult(keys)
        }
        if (sameVault && (to[0] == from[0] || to[0] == sourceItem.id || to[0] == sourceItem.title)) return moveWithinSourceItem()

        val destinationJson = backend.itemJson(destination.projectId, to[0])
        val destinationItem = OnePasswordJson.item(destinationJson)
        // The destination may name the source item another way (a title in other letter case, matched leniently): copying onto
        // the same fields and then removing them from the "source" would delete them
        if (sourceItem.id.isNotEmpty() && destinationItem.id == sourceItem.id && vaultOf(destinationJson, destination.projectId) == sourceVault) {
            return moveWithinSourceItem()
        }
        val section = to.getOrNull(1)?.let { requireSection(destinationItem, it, to[0]) }
        val existing = picked.associate { (key, _) -> key to findField(destinationItem, null, key) }
        val conflicts = existing.filterValues { it != null }.keys
        if (conflicts.isNotEmpty() && !overwrite) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "Item ${to[0]} already has ${conflicts.joinToString { "\"$it\"" }}; nothing was moved. Tick Overwrite to replace their values",
            )
        }
        val present = mutableSetOf<String>()
        for ((key, field) in picked) {
            val target = existing[key]
            present += if (target != null) {
                OnePasswordEdits.setValue(OnePasswordEdits.field(destinationJson, target.id), field.value)
                target.id
            } else {
                // A built-in field's type (a login's password…) is kept, not its purpose: the destination may have its own
                OnePasswordEdits.addField(destinationJson, key, field.value, field.type.ifEmpty { "CONCEALED" }, section)
            }
        }
        // Throws when the copy wasn't saved: then nothing changed
        save(destination.projectId, destinationJson, Expect(present = present))

        return try {
            // Read again: the version (and fields) as they are now
            val fresh = backend.itemJson(source.projectId, sourceItem.id.ifEmpty { from[0] })
            picked.forEach { (_, field) -> OnePasswordEdits.removeField(fresh, field.id) }
            save(source.projectId, fresh, Expect(absent = picked.filter { it.second.purpose.isEmpty() }.map { it.second.id }.toSet()))
            MoveResult(keys)
        } catch (e: SecretProviderException) {
            if (e.kind == Kind.CANCELLED) throw e
            MoveResult(emptyList(), notRemoved = keys.associateWith { e.message ?: e.kind.name })
        }
    }

    /** What a save must show afterwards: fields (by ID) that exist and that are gone, and new labels. Values aren't compared. */
    private data class Expect(val present: Set<String> = emptySet(), val absent: Set<String> = emptySet(), val labels: Map<String, String> = emptyMap())

    /** Reads the item, lets [change] edit its JSON (with the parsed item for lookups), and saves it whole. */
    private fun edit(vault: String, item: String, change: (JsonObject, OpItem) -> Expect) {
        val json = backend.itemJson(vault, item)
        save(vault, json, change(json, OnePasswordJson.item(json)))
    }

    /**
     * Saves [json] and checks the item 1Password returns: a CLI that ignored the piped JSON (or merged instead of replacing)
     * must not look like success. Skipped when the answer has no fields.
     */
    private fun save(vault: String, json: JsonObject, expect: Expect) {
        val result = backend.replaceItem(vault, json)
        val ids = OnePasswordEdits.fieldIds(result) ?: return
        val labelsApplied = expect.labels.isEmpty() || OnePasswordEdits.fieldLabels(result).let { labels -> expect.labels.all { (id, label) -> labels[id] == label } }
        if (!ids.containsAll(expect.present) || expect.absent.any { it in ids } || !labelsApplied) throw notApplied("save item ${json.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()}")
    }

    private fun notApplied(what: String) = SecretProviderException(
        Kind.SERVER,
        "1Password didn't apply the change ($what); update the 1Password CLI (it must accept the item as JSON on stdin) or use a Connect server",
    )

    private fun vaultOf(json: JsonObject, fallback: String): String =
        json.getAsJsonObject("vault")?.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: fallback

    /** The path's segments for a write: `/<item>[/<section>]`, or `/` when [allowRoot]. */
    private fun writableSegments(scope: SecretScope, allowRoot: Boolean): List<String> {
        val segments = segments(scope.path)
        if (segments.isEmpty() && !allowRoot) {
            throw SecretProviderException(Kind.CONFIGURATION, "A vault's root holds items, not secrets: choose an item (/<item>)")
        }
        if (segments.size > 2) {
            throw SecretProviderException(Kind.NOT_FOUND, "No such item or section: ${scope.path} (1Password paths are /<item>[/<section>])")
        }
        return segments
    }

    private fun requireSection(item: OpItem, wanted: String, itemName: String): OpSection =
        findSection(item, wanted)
            ?: throw SecretProviderException(Kind.NOT_FOUND, "No section \"$wanted\" in item $itemName; add it in 1Password first")

    /** The field [key] of `/<item>[/<section>]`, as listings and references find it. */
    private fun requireField(item: OpItem, segments: List<String>, key: String): OpField {
        val section = segments.getOrNull(1)?.let { requireSection(item, it, segments[0]) }
        return findField(item, section, key)
            ?: throw SecretProviderException(Kind.NOT_FOUND, "No field \"$key\" in ${segments.joinToString("/", prefix = "/")}; refresh and try again")
    }

    /** The first field labelled [key] (in [section] if given), top-level fields first, as resolving a reference does. */
    private fun findField(item: OpItem, section: OpSection?, key: String): OpField? =
        orderedFields(item).map { it.first }
            .filter { section == null || it.sectionId == section.id }
            .firstOrNull { keyOf(it) == key }

    /** A field's key in listings: its label, else its ID. */
    private fun keyOf(field: OpField) = field.label.ifBlank { field.id }

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
