package cz.xprees.secrets.onepassword

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.onepassword.OnePasswordJson.str
import java.security.SecureRandom

/**
 * Changes to an item's JSON as 1Password returns it (CLI and Connect share the shape), for read-modify-write. Everything the
 * edits don't touch (URLs, tags, other fields, `version`, …) is kept as it was read. Values are never logged.
 */
object OnePasswordEdits {
    /** Category of the item created for a secret at a vault's root: a password item, one concealed field plus notes. */
    const val ROOT_CATEGORY = "PASSWORD"

    /** The field of a [ROOT_CATEGORY] item holding the secret. */
    const val ROOT_FIELD = "password"

    private const val NOTES_ID = "notesPlain"
    private const val NOTES_PURPOSE = "NOTES"

    private val random = SecureRandom()

    /** A new 1Password-style ID (26 lowercase letters and digits) for a field or section. */
    fun newId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return String(CharArray(26) { alphabet[random.nextInt(alphabet.length)] })
    }

    /** The template of a password item titled [title] holding [value], with [notes] when not empty. */
    fun rootItem(title: String, value: String, notes: String): JsonObject = JsonObject().apply {
        addProperty("title", title)
        addProperty("category", ROOT_CATEGORY)
        add("fields", JsonArray().apply {
            add(field(ROOT_FIELD, ROOT_FIELD, value, "CONCEALED", section = null).apply { addProperty("purpose", "PASSWORD") })
            if (notes.isNotEmpty()) add(notesField(notes))
        })
    }

    fun fields(item: JsonObject): JsonArray = item.getAsJsonArray("fields") ?: JsonArray().also { item.add("fields", it) }

    /** The field with [id] (field IDs are unique within an item). */
    fun field(item: JsonObject, id: String): JsonObject =
        fields(item).firstOrNull { it.isJsonObject && it.asJsonObject.str("id") == id }?.asJsonObject
            ?: throw SecretProviderException(Kind.NOT_FOUND, "The field was removed from item ${item.str("title").orEmpty()} meanwhile; refresh and try again")

    /** Adds a field [label] = [value] (of [type]) to [section] (null: the item's top level); returns its ID. */
    fun addField(item: JsonObject, label: String, value: String, type: String, section: OpSection?): String {
        val taken = fields(item).mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("id") }.toSet()
        val id = generateSequence { newId() }.first { it !in taken }
        fields(item).add(field(id, label, value, type, section))
        return id
    }

    fun setValue(field: JsonObject, value: String) = field.addProperty("value", value)

    /** Renames [field] (its label is its key in listings and `op://` references). */
    fun setLabel(field: JsonObject, label: String) = field.addProperty("label", label)

    /** Puts [field] into [section] (null: the item's top level). */
    fun setSection(field: JsonObject, section: OpSection?) {
        if (section == null) field.remove("section") else field.add("section", sectionRef(section))
    }

    /**
     * Removes the field with [id]. A built-in field (one with a `purpose`: username, password, notes) is emptied instead: items
     * keep their built-in fields, and empty fields aren't shown as secrets.
     */
    fun removeField(item: JsonObject, id: String) {
        val array = fields(item)
        val index = array.indexOfFirst { it.isJsonObject && it.asJsonObject.str("id") == id }
        if (index < 0) return
        val field = array[index].asJsonObject
        if (!field.str("purpose").isNullOrEmpty()) setValue(field, "") else array.remove(index)
    }

    /** The item's notes (its `NOTES` field). */
    fun notes(item: OpItem): String = item.fields.firstOrNull { it.purpose == NOTES_PURPOSE }?.value.orEmpty()

    /** Sets the item's notes, adding the notes field when the item has none. */
    fun setNotes(item: JsonObject, notes: String) {
        val existing = fields(item).firstOrNull { it.isJsonObject && it.asJsonObject.str("purpose") == NOTES_PURPOSE }?.asJsonObject
        if (existing != null) setValue(existing, notes) else if (notes.isNotEmpty()) fields(item).add(notesField(notes))
    }

    /** IDs of the item's fields (to check that a write took effect). */
    fun fieldIds(item: JsonObject): Set<String>? =
        item.getAsJsonArray("fields")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("id") }?.toSet()

    /** Labels of the item's fields by ID (to check that a rename took effect). */
    fun fieldLabels(item: JsonObject): Map<String, String> =
        item.getAsJsonArray("fields")?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let { o -> o.str("id")?.let { it to o.str("label").orEmpty() } } }?.toMap().orEmpty()

    private fun notesField(notes: String) = field(NOTES_ID, NOTES_ID, notes, "STRING", section = null).apply { addProperty("purpose", NOTES_PURPOSE) }

    private fun field(id: String, label: String, value: String, type: String, section: OpSection?) = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", type)
        addProperty("label", label)
        addProperty("value", value)
        section?.let { add("section", sectionRef(it)) }
    }

    // The CLI repeats the section's label on fields; Connect reads the ID
    private fun sectionRef(section: OpSection) = JsonObject().apply {
        addProperty("id", section.id)
        if (section.label.isNotEmpty()) addProperty("label", section.label)
    }
}
