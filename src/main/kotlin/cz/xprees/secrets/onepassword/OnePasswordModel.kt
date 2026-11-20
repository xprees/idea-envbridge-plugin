package cz.xprees.secrets.onepassword

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction

/** A vault (`op vault list`, Connect `GET /v1/vaults`). */
data class OpVault(val id: String, val name: String)

/** An item as listed (`op item list`, Connect `GET /v1/vaults/{id}/items`): no fields. */
data class OpItemSummary(val id: String, val title: String)

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

data class OpItem(val id: String, val title: String, val sections: List<OpSection>, val fields: List<OpField>) {
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

    fun item(vault: String, item: String): OpItem
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
        OpItemSummary(id, o.str("title").orEmpty())
    }

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
        return OpItem(o.str("id").orEmpty(), o.str("title").orEmpty(), sections, fields)
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
