package cz.xprees.secrets.core.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The only place credentials are kept: the IDE's credential store (system keychain, KeePass or the master-password protected
 * store, as configured in Settings › Appearance & Behavior › System Settings › Passwords). Nothing here ever goes to XML
 * settings, logs or caches.
 *
 * Entries are named `Secrets for Infisical — <connection id>/<slot>` (the plugin's former name, kept so stored credentials
 * survive the rename to EnvBridge). Access may block (the keychain can ask the user), so
 * call it off the EDT.
 */
object CredentialVault {
    private const val SUBSYSTEM = "Secrets for Infisical"

    /** The session (tokens) of a connection. */
    const val SLOT_SESSION = "session"

    /** A secret option of an auth method (OAuth client secret, machine identity client secret, …). */
    fun secretOptionSlot(key: String) = "option.$key"

    private fun attributes(connectionId: String, slot: String) =
        CredentialAttributes(generateServiceName(SUBSYSTEM, "$connectionId/$slot"))

    fun get(connectionId: String, slot: String): String? =
        PasswordSafe.instance.get(attributes(connectionId, slot))?.getPasswordAsString()?.takeIf { it.isNotEmpty() }

    fun set(connectionId: String, slot: String, value: String?) {
        PasswordSafe.instance.set(attributes(connectionId, slot), value?.takeIf { it.isNotEmpty() }?.let { Credentials(slot, it) })
    }

    fun remove(connectionId: String, slot: String) = set(connectionId, slot, null)

    /** Forgets everything stored for a connection (when it is deleted). */
    fun removeAll(connectionId: String, secretOptionKeys: Collection<String>) {
        remove(connectionId, SLOT_SESSION)
        secretOptionKeys.forEach { remove(connectionId, secretOptionSlot(it)) }
    }
}
