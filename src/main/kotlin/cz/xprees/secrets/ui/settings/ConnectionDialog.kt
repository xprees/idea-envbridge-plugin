package cz.xprees.secrets.ui.settings

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.text.StringUtil
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.provider.SetupLink
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.OptionChoice
import cz.xprees.secrets.core.provider.OptionChooser
import cz.xprees.secrets.core.provider.PendingSession
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.ui.auth.IdeSignInInteraction
import java.util.UUID
import javax.swing.JComponent

/**
 * Creates or edits a connection: name, instance URL (cloud presets or a self-hosted URL) and sign-in method with its options.
 * Secret options (client secrets) go to the credential store, never into the settings file.
 */
class ConnectionDialog(
    private val project: Project?,
    private val existing: SecretConnection?,
    newProvider: SecretProvider? = null,
) : DialogWrapper(project) {
    private val provider: SecretProvider = existing?.let { SecretProvider.byId(it.providerId) } ?: newProvider ?: SecretProvider.all().first()

    private val nameField = JBTextField(existing?.name ?: provider.displayName)
    private val instanceCombo = ComboBox((provider.instancePresets.map { it.second }).toTypedArray()).apply {
        isEditable = true
        selectedItem = existing?.baseUrl ?: provider.instancePresets.firstOrNull()?.second ?: "https://"
        renderer = textListCellRenderer { url: String? ->
            provider.instancePresets.firstOrNull { it.second == url }?.let { "${it.first} — ${it.second}" } ?: url.orEmpty()
        }
    }
    private val methodCombo = ComboBox(provider.authMethods.toTypedArray()).apply {
        renderer = textListCellRenderer { method: AuthMethod? -> method?.displayName.orEmpty() }
        selectedItem = existing?.let { provider.authMethod(it.authMethodId) } ?: provider.authMethods.first()
    }

    /** One field per (method, option), shown only for the selected method. */
    private val optionFields = provider.authMethods.associate { method ->
        method.id to method.options.associate { option ->
            option.key to JBTextField(if (existing?.authMethodId == method.id) existing.option(option.key).orEmpty() else "").apply {
                emptyText.text = option.hint
            }
        }
    }
    private val secretFields = provider.authMethods.associate { method ->
        method.id to method.secretOptions.associate { option ->
            option.key to JBPasswordField().apply {
                if (existing?.authMethodId == method.id) emptyText.text = SecretsBundle.message("connection.secret.keep")
            }
        }
    }

    /** The connection's ID (new connections get theirs now: a "Choose…" sign-in stores its session under it). */
    private val connectionId = existing?.id ?: UUID.randomUUID().toString()

    /** The connection as the last successful [OptionChooser] saw it (with the values it filled in); null when none ran. */
    private var chooserDraft: SecretConnection? = null

    /** The session the last [OptionChooser] signed in for, stored only by [doOKAction] (Cancel drops it). */
    private var pendingSession: PendingSession? = null

    /** Whether [doOKAction] stored a session a "Choose…" signed in for: the connection is signed in already. */
    var signedIn: Boolean = false
        private set

    /** The connection as saved by [doOKAction]. */
    var result: SecretConnection? = null
        private set

    init {
        title = SecretsBundle.message(if (existing == null) "connection.title.add" else "connection.title.edit")
        init()
        // Fit the fields of the chosen method: the dialog shrinks back after a method with more fields (after the
        // panel's own visibility listeners ran)
        methodCombo.addActionListener { com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ if (isShowing) pack() }, com.intellij.openapi.application.ModalityState.any()) }
    }

    private val selectedMethod: AuthMethod get() = methodCombo.selectedItem as AuthMethod

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("connection.name")) { cell(nameField).align(AlignX.FILL) }
        val instanceUsed = UsesBaseUrl()
        row(SecretsBundle.message("connection.instance")) { cell(instanceCombo).align(AlignX.FILL) }.visibleIf(instanceUsed)
        row { comment(SecretsBundle.message("connection.instance.comment"), maxLineLength = 70) }.visibleIf(instanceUsed)
        row(SecretsBundle.message("connection.method")) { cell(methodCombo).align(AlignX.FILL) }
        for (method in provider.authMethods) {
            val visible = MethodSelected(method)
            // Plain text: comments render HTML, where `<port>` would vanish
            row { comment(StringUtil.escapeXmlEntities(method.description), maxLineLength = 70) }.visibleIf(visible)
            if (method.setupLinks.isNotEmpty()) {
                row {
                    // A page may depend on the instance typed in, so it is worked out on click
                    for (setup in method.setupLinks) link(setup.text) { setupPage(setup)?.let(BrowserUtil::browse) }.applyToComponent { setExternalLinkIcon() }
                }.visibleIf(visible)
            }
            for (option in method.options) {
                row(option.label) {
                    cell(optionFields.getValue(method.id).getValue(option.key)).align(AlignX.FILL)
                    method.chooser?.takeIf { it.keys.firstOrNull() == option.key }?.let { chooser -> button(chooser.label) { runChooser(method, chooser) } }
                }.visibleIf(visible)
            }
            for (option in method.secretOptions) {
                row(option.label) { cell(secretFields.getValue(method.id).getValue(option.key)).align(AlignX.FILL) }.visibleIf(visible)
            }
        }
        row { comment(StringUtil.escapeXmlEntities(SecretsBundle.message("connection.storage.comment")), maxLineLength = 70) }
    }.also { it.preferredSize = it.preferredSize.apply { width = maxOf(width, 560) } }

    private inner class MethodSelected(private val method: AuthMethod) : ComponentPredicate() {
        override fun invoke() = selectedMethod.id == method.id
        override fun addListener(listener: (Boolean) -> Unit) {
            methodCombo.addActionListener { listener(invoke()) }
        }
    }

    /** Whether the selected method talks to the instance address (a method going through a local CLI doesn't). */
    private inner class UsesBaseUrl : ComponentPredicate() {
        override fun invoke() = selectedMethod.usesBaseUrl
        override fun addListener(listener: (Boolean) -> Unit) {
            methodCombo.addActionListener { listener(invoke()) }
        }
    }

    /**
     * The base URL to save: the one entered, or for a method that doesn't use it whatever valid address there is (the
     * previous one, else the provider's first preset), so the connection stays valid if the method is changed later.
     */
    private fun baseUrlToSave(): String? = SecretConnection.normalizeBaseUrl(instanceUrl())
        ?: if (selectedMethod.usesBaseUrl) null
        else existing?.baseUrl ?: provider.instancePresets.firstNotNullOfOrNull { SecretConnection.normalizeBaseUrl(it.second) }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo(SecretsBundle.message("connection.validation.name"), nameField)
        if (baseUrlToSave() == null) {
            return ValidationInfo(SecretsBundle.message("connection.validation.url"), instanceCombo)
        }
        val method = selectedMethod
        for (option in method.options.filter(AuthOption::required)) {
            val field = optionFields.getValue(method.id).getValue(option.key)
            if (field.text.isBlank()) return ValidationInfo(SecretsBundle.message("connection.validation.required", option.label.trimEnd(':')), field)
        }
        for (option in method.options) {
            val field = optionFields.getValue(method.id).getValue(option.key)
            val text = field.text.trim().takeIf { it.isNotEmpty() } ?: continue
            option.validate?.invoke(text)?.let { return ValidationInfo(it, field) }
        }
        optionFields.getValue(method.id)["callbackPort"]?.text?.takeIf { it.isNotBlank() }?.let { port ->
            if (port.toIntOrNull() !in 1024..65535) {
                return ValidationInfo(SecretsBundle.message("connection.validation.port"), optionFields.getValue(method.id).getValue("callbackPort"))
            }
        }
        val keepingSecrets = existing?.authMethodId == method.id
        for (option in method.secretOptions.filter(AuthOption::required)) {
            val field = secretFields.getValue(method.id).getValue(option.key)
            if (field.password.isEmpty() && !keepingSecrets) {
                return ValidationInfo(SecretsBundle.message("connection.validation.required", option.label.trimEnd(':')), field)
            }
        }
        return null
    }

    /** The connection made of the dialog's fields for [method]. */
    private fun draft(method: AuthMethod, baseUrl: String) = SecretConnection(
        id = connectionId,
        providerId = provider.id,
        name = nameField.text.trim(),
        baseUrl = baseUrl,
        authMethodId = method.id,
        options = optionFields.getValue(method.id).mapValues { it.value.text.trim() }.filterValues { it.isNotEmpty() },
    )

    /**
     * Runs [chooser] under a modal progress for the connection as the fields describe it (it may sign in in the browser) and
     * puts the values chosen in the fields. Cancelling changes no field.
     */
    private fun runChooser(method: AuthMethod, chooser: OptionChooser) {
        val title = chooser.label.trimEnd('…')
        val baseUrl = baseUrlToSave() ?: return Messages.showErrorDialog(contentPanel, SecretsBundle.message("connection.validation.url"), title)
        val draft = draft(method, baseUrl)
        val choice = try {
            ProgressManager.getInstance().run(object : Task.WithResult<OptionChoice?, Exception>(project, contentPanel, method.displayName, true) {
                override fun compute(indicator: ProgressIndicator): OptionChoice? {
                    indicator.isIndeterminate = true
                    return chooser.choose(draft, IdeSignInInteraction(project, indicator))
                }
            })
        } catch (e: ProcessCanceledException) {
            return
        } catch (e: Exception) {
            if ((e as? SecretProviderException)?.kind != SecretProviderException.Kind.CANCELLED) {
                Messages.showErrorDialog(contentPanel, e.message ?: e.toString(), title)
            }
            return
        }
        choice ?: return
        val chosen = choice.values.filterKeys { it in chooser.keys }
        val fields = optionFields.getValue(method.id)
        chosen.forEach { (key, value) -> fields[key]?.text = value }
        chooserDraft = draft.copy(options = draft.options + chosen)
        pendingSession = choice.pendingSession
    }

    override fun doOKAction() {
        val method = selectedMethod
        val connection = draft(method, baseUrlToSave()!!)
        val secrets = secretFields.getValue(method.id).mapValues { (_, field) -> field.password.also { field.text = "" } }
        val secretsEntered = secrets.values.any { it.isNotEmpty() }
        val keepsSession = method.options.filter(AuthOption::keepsSession).map(AuthOption::key).toSet()
        // A session that a "Choose…" just used or made for these very settings stays (or is stored now)
        val chosenFor = chooserDraft?.let { !sessionStale(it, connection, secretsEntered, keepsSession) } == true
        val sessionStale = if (chosenFor) false else sessionStale(existing, connection, secretsEntered, keepsSession)
        val pending = pendingSession?.takeIf { chosenFor }
        val allSecretKeys = provider.authMethods.flatMap { m -> m.secretOptions.map { it.key } }.toSet()
        // Synchronously (under a short modal progress, off the EDT): a sign-in started right after OK must find the secrets
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            // A session belongs to the instance and method it was created for
            signedIn = applySession(pending, sessionStale) { CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION) }
            if (existing != null && existing.authMethodId != method.id) {
                (allSecretKeys - method.secretOptions.map { it.key }.toSet()).forEach {
                    CredentialVault.remove(connection.id, CredentialVault.secretOptionSlot(it))
                }
            }
            for ((key, value) in secrets) {
                if (value.isNotEmpty()) CredentialVault.set(connection.id, CredentialVault.secretOptionSlot(key), String(value))
                value.fill('\u0000')
            }
        }, SecretsBundle.message("connection.saving"), false, project)
        result = connection
        super.doOKAction()
    }

    /** [link]'s page for the instance in the dialog; only web pages (https, or http on this machine). */
    private fun setupPage(link: SetupLink): String? {
        val base = SecretConnection.normalizeBaseUrl(instanceUrl()) ?: provider.instancePresets.firstOrNull()?.second ?: return null
        return link.url(base)?.takeIf(::isWebPage)
    }

    private fun instanceUrl(): String = (instanceCombo.editor.item ?: instanceCombo.selectedItem)?.toString().orEmpty().trim()

    override fun getPreferredFocusedComponent(): JComponent = nameField

    companion object {
        /**
         * Updates the stored session when the dialog is confirmed: stores [pending] (a session a "Choose…" signed in for these
         * settings) or else [removes][remove] a [stale] one. Returns whether a session was stored (no sign-in needed).
         */
        fun applySession(pending: PendingSession?, stale: Boolean, remove: () -> Unit): Boolean {
            if (pending != null) {
                pending.store()
                return true
            }
            if (stale) remove()
            return false
        }

        /** Whether [url] is a page to open in the browser: https, or http on this machine. */
        fun isWebPage(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            return uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && (host == "localhost" || host == "127.0.0.1"))
        }

        /**
         * Whether [existing]'s stored session must be dropped when it is saved as [updated]: a session was created from the
         * method, instance, options and secrets, so any change makes it stale (a newly pasted token or password must be used
         * right away, not after the old session expires). Options in [keepsSession] (see [AuthOption.keepsSession]) don't count.
         */
        fun sessionStale(existing: SecretConnection?, updated: SecretConnection, secretsEntered: Boolean, keepsSession: Set<String> = emptySet()): Boolean =
            existing != null && (
                existing.authMethodId != updated.authMethodId || existing.baseUrl != updated.baseUrl ||
                    existing.options.filterKeys { it !in keepsSession } != updated.options.filterKeys { it !in keepsSession } || secretsEntered
                )
    }
}
