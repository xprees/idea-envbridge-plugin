package cz.xprees.secrets.ui.secrets

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.resolve.SecretsService
import cz.xprees.secrets.run.SecretsNotifications
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** Creates an environment in a project: a name, and a slug (filled in from the name until edited) used in references. */
class NewEnvironmentDialog(
    private val project: Project,
    private val connection: SecretConnection,
    private val projectId: String,
    private val projectName: String,
    private val existingSlugs: Collection<String>,
    private val onCreated: (ProviderEnvironment) -> Unit = {},
) : DialogWrapper(project) {
    private val nameField = JBTextField(30)
    private val slugField = JBTextField(30)
    private var slugEdited = false

    init {
        title = SecretsBundle.message("new.environment.title")
        setOKButtonText(SecretsBundle.message("new.secret.create"))
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!slugEdited) slugField.text = slugOf(nameField.text)
            }
        })
        slugField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (slugField.isFocusOwner) slugEdited = true
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SecretsBundle.message("new.environment.name")) { cell(nameField).align(AlignX.FILL).focused() }
        row(SecretsBundle.message("new.environment.slug")) { cell(slugField).align(AlignX.FILL) }
        row { comment(SecretsBundle.message("new.environment.note", projectName)) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val slug = slugField.text.trim()
        return when {
            nameField.text.isBlank() -> ValidationInfo(SecretsBundle.message("new.environment.invalid.name"), nameField)
            !SLUG.matches(slug) -> ValidationInfo(SecretsBundle.message("new.environment.invalid.slug"), slugField)
            slug in existingSlugs -> ValidationInfo(SecretsBundle.message("new.environment.exists", slug), slugField)
            else -> null
        }
    }

    override fun doOKAction() {
        val name = nameField.text.trim()
        val slug = slugField.text.trim()
        val created = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<ProviderEnvironment, SecretProviderException>(
                { SecretsService.getInstance().createEnvironment(connection, projectId, name, slug) },
                SecretsBundle.message("new.environment.creating", slug),
                true,
                project,
            )
        } catch (e: SecretProviderException) {
            setErrorText(e.message ?: e.kind.name)
            return
        }
        SecretsNotifications.info(project, SecretsBundle.message("new.environment.created", created.name, created.slug, projectName))
        super.doOKAction()
        onCreated(created)
    }

    companion object {
        private val SLUG = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")

        /** "QA Staging" -> "qa-staging". */
        fun slugOf(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}
