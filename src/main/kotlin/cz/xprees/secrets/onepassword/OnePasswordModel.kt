package cz.xprees.secrets.onepassword

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction

/** A vault (`op vault list`, Connect `GET /v1/vaults`). */
data class OpVault(val id: String, val name: String)

/**
 * An item as listed (`op item list`, Connect `GET /v1/vaults/{id}/items`): no fields. [stamp] is what changes whenever the
 * item does (its `version` and update time, `updated_at` from the CLI, `updatedAt` from Connect), null when the listing has
 * neither. [tags] are the item's tags (`tags`, in both listings).
 */
data class OpItemSummary(val id: String, val title: String, val stamp: String? = null, val tags: List<String> = emptyList())

data class OpSection(val id: String, val label: String)

/** A field of an item. [value] is the plain value; never log it. */
data class OpField(
    val id: String,
    val label: String,
    val value: String,
    val type: String,
    val purpose: String,
    val sectionId: String?,
) {
    override fun toString(): String = "OpField(id=$id, label=$label, type=$type)"
}

data class OpItem(val id: String, val title: String, val sections: List<OpSection>, val fields: List<OpField>, val tags: List<String> = emptyList()) {
    fun section(id: String?): OpSection? = id?.let { sid -> sections.firstOrNull { it.id == sid } }
}

/**
 * Where a 1Password connection reads from: the `op` CLI (desktop app or service account) or a Connect server. Vaults and
 * items are given by name or ID. Every call blocks and throws [SecretProviderException].
 */
interface OnePasswordBackend {
    /** Checks access (may show the desktop app's authorization prompt) and returns a short account description. */
    fun signIn(interaction: SignInInteraction): String

    fun vaults(): List<OpVault>

    fun items(vault: String): List<OpItemSummary>

    fun item(vault: String, item: String): OpItem = OnePasswordJson.item(itemJson(vault, item))

    /** The item's whole JSON, values included, as 1Password returns it: the base of a read-modify-write. Never log it. */
    fun itemJson(vault: String, item: String): JsonObject

    /** Creates an item in [vault] from [template] (`title`, `category`, `fields`, …); returns the created item's JSON. */
    fun createItem(vault: String, template: JsonObject): JsonObject

    /**
     * Replaces the item [item]`.id` in [vault] with [item]: JSON read with [itemJson] and changed. Fields left out are removed.
     * Returns the item's JSON after the change (values may be left out).
     */
    fun replaceItem(vault: String, item: JsonObject): JsonObject
}

/**
 * Items a whole-vault listing read, per connection, vault (as the scope names it) and item ID, each with the [OpItemSummary.stamp]
 * it was listed with, so the next whole-vault listing re-reads only items whose stamp changed (`op item list` / one Connect
 * list request, then `op item get` / a request for each changed item). In memory only, values included like the secrets
 * cache; never persisted. Forgotten on sign-out, for an item this IDE writes, and for items no longer listed.
 */
object OnePasswordItemMemory {
    private data class Key(val connectionId: String, val vault: String, val itemId: String)

    private class Entry(val stamp: String, val item: OpItem)

    private val items = java.util.concurrent.ConcurrentHashMap<Key, Entry>()

    /** The item as last read, if [summary]'s stamp is the one it was read with. */
    fun get(connectionId: String, vault: String, summary: OpItemSummary): OpItem? {
        val stamp = summary.stamp ?: return null
        return items[Key(connectionId, vault, summary.id)]?.takeIf { it.stamp == stamp }?.item
    }

    /** Remembers [item] as read for [summary] (not when the listing gave no stamp). */
    fun put(connectionId: String, vault: String, summary: OpItemSummary, item: OpItem) {
        val stamp = summary.stamp ?: return
        items[Key(connectionId, vault, summary.id)] = Entry(stamp, item)
    }

    /** Forgets the items of [vault] that aren't in [listed] (deleted or moved away). */
    fun retain(connectionId: String, vault: String, listed: Set<String>) {
        items.keys.removeIf { it.connectionId == connectionId && it.vault == vault && it.itemId !in listed }
    }

    /** Forgets the item [itemId] in every vault of the connection (a write changed it). */
    fun forgetItem(connectionId: String, itemId: String) {
        items.keys.removeIf { it.connectionId == connectionId && it.itemId == itemId }
    }

    /** Forgets everything of the connection (sign-out). */
    fun forget(connectionId: String) {
        items.keys.removeIf { it.connectionId == connectionId }
    }
}

/**
 * Reads 1Password's JSON. The CLI (`--format json`) and Connect use the same item shape: `id`, `title`, `sections[]`
 * (`id`, `label`) and `fields[]` (`id`, `type`, `purpose`, `label`, `value`, `section` {`id`, `label` (CLI only)}). Vaults
 * have `id` and `name`.
 */
object OnePasswordJson {
    fun vaults(json: JsonElement): List<OpVault> = array(json, "vault list").mapNotNull { element ->
        val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        OpVault(id, o.str("name") ?: o.str("title") ?: id)
    }

    fun items(json: JsonElement): List<OpItemSummary> = array(json, "item list").mapNotNull { element ->
        val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val version = o.str("version")
        val updated = o.str("updated_at") ?: o.str("updatedAt")
        OpItemSummary(id, o.str("title").orEmpty(), if (version == null && updated == null) null else "$version|$updated", tags(o))
    }

    /** An item's `tags` (strings; 1Password nests them with `/`, as in `env/dev`). */
    fun tags(o: JsonObject): List<String> =
        o.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { t -> t.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank) }.orEmpty()

    /**
     * Whether an item with [tags] passes the connection's "Only items tagged" [filter] (any of its tags; a tag also matches its
     * nested tags: `env` matches `env/dev`). An empty filter passes everything.
     */
    fun matchesTagFilter(tags: List<String>, filter: List<String>): Boolean =
        filter.isEmpty() || tags.any { tag -> filter.any { wanted -> tag.equals(wanted, ignoreCase = true) || tag.startsWith("$wanted/", ignoreCase = true) } }

    fun item(json: JsonElement): OpItem {
        val o = json.takeIf { it.isJsonObject }?.asJsonObject ?: throw unexpected("item")
        val sections = o.getAsJsonArray("sections")?.mapNotNull { s ->
            val so = s.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            OpSection(so.str("id") ?: return@mapNotNull null, so.str("label").orEmpty())
        }.orEmpty().toMutableList()
        val fields = o.getAsJsonArray("fields")?.mapNotNull { f ->
            val fo = f.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val section = fo.get("section")?.takeIf { it.isJsonObject }?.asJsonObject
            val sectionId = section?.str("id")
            // The CLI repeats the section's label on the field; Connect only gives its ID
            if (sectionId != null && sections.none { it.id == sectionId }) sections += OpSection(sectionId, section.str("label").orEmpty())
            OpField(
                id = fo.str("id").orEmpty(),
                label = fo.str("label").orEmpty(),
                value = fo.get("value")?.takeIf { !it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty(),
                type = fo.str("type").orEmpty(),
                purpose = fo.str("purpose").orEmpty(),
                sectionId = sectionId,
            )
        }.orEmpty()
        return OpItem(o.str("id").orEmpty(), o.str("title").orEmpty(), sections, fields, tags(o))
    }

    private fun array(json: JsonElement, what: String) = when {
        json.isJsonArray -> json.asJsonArray.toList()
        // `op` prints nothing (or null) for an empty list
        json.isJsonNull -> emptyList()
        else -> throw unexpected(what)
    }

    private fun unexpected(what: String) = SecretProviderException(Kind.SERVER, "1Password sent an unexpected answer to $what")

    fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
