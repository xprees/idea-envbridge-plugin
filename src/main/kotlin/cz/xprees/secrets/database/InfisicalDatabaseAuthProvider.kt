package cz.xprees.secrets.database

import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseConnectionConfig
import com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.url.template.MutableParametersHolder
import com.intellij.database.dataSource.url.template.ParametersHolder
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import com.intellij.openapi.project.Project
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Database data sources (DataGrip and the Database tools of the other IDEs): an authentication option "Infisical" whose user
 * and password are secret references (`infisical://DB_USER`) or plain values. They are resolved when a connection opens,
 * passed to the driver and never stored; only the references are saved with the data source.
 */
class InfisicalDatabaseAuthProvider : DatabaseAuthProvider {
    override fun getId(): String = ID

    override fun getDisplayName(): String = SecretsBundle.message("database.auth.name")

    override fun getApplicability(
        point: DatabaseConnectionPoint,
        level: DatabaseAuthProvider.ApplicabilityLevel,
    ): DatabaseAuthProvider.ApplicabilityLevel.Result = DatabaseAuthProvider.ApplicabilityLevel.Result.APPLICABLE

    override suspend fun interceptConnection(proto: ProtoConnection, silent: Boolean): Boolean {
        val point = proto.connectionPoint
        val references = linkedMapOf(
            "user" to point.getAdditionalProperty(PROP_USER).orEmpty(),
            "password" to point.getAdditionalProperty(PROP_PASSWORD).orEmpty(),
        ).filterValues { it.isNotBlank() }
        if (references.isEmpty()) return true
        // Network and credential store access: off the calling thread
        val resolved = withContext(Dispatchers.IO) {
            val resolver = LaunchSecretResolver.getInstance(proto.project)
            resolver.resolve(resolver.plan(references)).environment
        }
        resolved["user"]?.let { proto.connectionProperties["user"] = it }
        resolved["password"]?.let { proto.connectionProperties["password"] = it }
        return true
    }

    /**
     * The user and password fields, holding references (stored as data source properties, never as credentials). This
     * overload exists in every supported IDE: 2026.1 deprecated it for `configureWidget`, which 2026.3 removed again.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun createWidget(project: Project?, credentials: DatabaseCredentials, config: DatabaseConnectionConfig): DatabaseAuthProvider.AuthWidget =
        InfisicalAuthWidget()

    companion object {
        const val ID = "cz.xprees.secrets.infisical"
        const val PROP_USER = "cz.xprees.secrets.user"
        const val PROP_PASSWORD = "cz.xprees.secrets.password"
    }
}

/** The "Infisical" authentication fields of a data source: references, not values. */
private class InfisicalAuthWidget : DatabaseAuthProvider.AuthWidget {
    private val user = JBTextField().apply { emptyText.text = "infisical://DB_USER" }
    private val password = JBTextField().apply { emptyText.text = "infisical://DB_PASSWORD" }
    private val component = panel {
        row(SecretsBundle.message("database.auth.user")) { cell(user).align(AlignX.FILL).resizableColumn() }
        row(SecretsBundle.message("database.auth.password")) { cell(password).align(AlignX.FILL).resizableColumn() }
        row { comment(SecretsBundle.message("database.auth.comment")) }
    }

    override fun onChanged(runnable: Runnable) {
        val listener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = runnable.run()
        }
        user.document.addDocumentListener(listener)
        password.document.addDocumentListener(listener)
    }

    override fun save(config: DatabaseConnectionConfig, copyCredentials: Boolean) {
        config.setAdditionalProperty(InfisicalDatabaseAuthProvider.PROP_USER, user.text.trim())
        config.setAdditionalProperty(InfisicalDatabaseAuthProvider.PROP_PASSWORD, password.text.trim())
    }

    override fun reset(point: DatabaseConnectionPoint, resetCredentials: Boolean) {
        user.text = point.getAdditionalProperty(InfisicalDatabaseAuthProvider.PROP_USER).orEmpty()
        password.text = point.getAdditionalProperty(InfisicalDatabaseAuthProvider.PROP_PASSWORD).orEmpty()
    }

    override fun isPasswordChanged(): Boolean = false

    override fun hidePassword() = Unit

    override fun reloadCredentials() = Unit

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = user

    override fun forceSave() = Unit

    override fun updateFromUrl(holder: ParametersHolder) = Unit

    override fun updateUrl(holder: MutableParametersHolder) = Unit
}
