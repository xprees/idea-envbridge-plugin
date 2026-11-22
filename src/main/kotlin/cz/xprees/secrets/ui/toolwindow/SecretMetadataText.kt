package cz.xprees.secrets.ui.toolwindow

import com.intellij.openapi.util.text.StringUtil
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.SecretMetadata
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How [SecretMetadata] reads in the tool window and the secret dialogs: tooltip lines, the short badge after a name. */
object SecretMetadataText {
    /** One line per fact: description, tags, rotation, reminder (empty when there is nothing to say). */
    fun lines(metadata: SecretMetadata, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(zone)
        return buildList {
            if (metadata.description.isNotBlank()) add(SecretsBundle.message("toolwindow.metadata.description", metadata.description))
            if (metadata.tags.isNotEmpty()) add(SecretsBundle.message("toolwindow.metadata.tags", tags(metadata)))
            metadata.rotation?.let { rotation ->
                when {
                    rotation.managed && rotation.name != null -> add(SecretsBundle.message("toolwindow.metadata.rotation.managed.by", rotation.name))
                    rotation.managed -> add(SecretsBundle.message("toolwindow.metadata.rotation.managed"))
                    else -> add(SecretsBundle.message(if (rotation.enabled) "toolwindow.metadata.rotation.on" else "toolwindow.metadata.rotation.off"))
                }
                // A managed rotation's on/off is known once its details were read (then it has a name)
                if (rotation.managed && rotation.name != null) {
                    add(SecretsBundle.message(if (rotation.enabled) "toolwindow.metadata.rotation.on" else "toolwindow.metadata.rotation.off"))
                }
                rotation.lastRotated?.let { add(SecretsBundle.message("toolwindow.metadata.rotation.last", format.format(it))) }
                rotation.nextRotation?.let { add(SecretsBundle.message("toolwindow.metadata.rotation.next", format.format(it))) }
            }
            metadata.reminder?.let { reminder ->
                add(
                    if (reminder.note.isBlank()) SecretsBundle.message("toolwindow.metadata.reminder", reminder.repeatDays)
                    else SecretsBundle.message("toolwindow.metadata.reminder.note", reminder.repeatDays, reminder.note),
                )
            }
        }
    }

    /** Tags as text: `name` or `key=value`, comma-separated. */
    fun tags(metadata: SecretMetadata): String = metadata.tags.entries.joinToString { (k, v) -> if (v.isEmpty()) k else "$k=$v" }

    /** The short text shown after a secret's or folder's name: `rotated`, then its tags (empty when there is nothing). */
    fun badge(metadata: SecretMetadata?): String {
        if (metadata == null) return ""
        return listOfNotNull(
            SecretsBundle.message("toolwindow.rotated").takeIf { metadata.rotation?.managed == true },
            metadata.tags.keys.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
        ).joinToString("  ")
    }

    /** Tooltip HTML from [comment] and lines, escaped; null when there is nothing. */
    fun tooltip(comment: String?, lines: List<String>): String? {
        val all = listOfNotNull(comment?.takeIf { it.isNotBlank() }) + lines
        if (all.isEmpty()) return null
        return all.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it).replace("\n", "<br>") }
    }
}
