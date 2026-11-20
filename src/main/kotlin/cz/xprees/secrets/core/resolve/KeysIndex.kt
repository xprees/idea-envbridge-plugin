package cz.xprees.secrets.core.resolve

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The secret **keys** (never values) of the bound folder in every environment of the bound project, for completion, hover
 * and inspections. Loaded in the background from the memory-only secrets cache and refreshed when the binding or the
 * connections change or the snapshot gets old. Editor features never wait for it: they use what is loaded.
 */
@Service(Service.Level.PROJECT)
class KeysIndex(private val project: Project) : Disposable {

    data class KeyInfo(val key: String, val comment: String, val origin: SecretOrigin, val path: String)

    data class Snapshot(
        val providerId: String,
        val scheme: String,
        val connectionName: String,
        val projectId: String,
        val path: String,
        /** The environment references fall back to. */
        val boundEnvironment: String,
        /** Environment slug -> keys of its root folder (what a bare reference reads; environments that failed to load are missing). */
        val environments: Map<String, Map<String, KeyInfo>>,
        val loadedAt: Long,
        /** Folder path -> keys defined there, for every folder of the bound environment (root and subfolders). */
        val folders: Map<String, Map<String, KeyInfo>> = emptyMap(),
    ) {
        /** The folders of the bound environment that define [key]. */
        fun foldersOf(key: String): List<String> = folders.filterValues { key in it }.keys.sorted()

        val boundKeys: Map<String, KeyInfo> get() = environments[boundEnvironment].orEmpty()

        /** Environments whose bound folder defines [key]. */
        fun coverage(key: String): List<String> = environments.filterValues { key in it }.keys.toList()
    }

    @Volatile
    private var snapshot: Snapshot? = null
    private val loading = AtomicBoolean(false)

    init {
        project.messageBus.connect(this).subscribe(ProjectSecretsSettings.BINDING_TOPIC, ProjectSecretsSettings.BindingListener { invalidate() })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SecretsAppSettings.TOPIC, SecretsAppSettings.Listener { invalidate() })
    }

    /** The loaded snapshot (may be null or slightly old); starts a refresh in the background when needed. Never blocks. */
    fun current(): Snapshot? {
        val current = snapshot
        if (current == null || System.currentTimeMillis() - current.loadedAt > MAX_AGE_MS) refreshAsync()
        return current
    }

    fun invalidate() {
        snapshot = null
        refreshAsync()
    }

    fun refreshAsync() {
        if (project.isDisposed || !loading.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loaded = load()
                if (loaded != snapshot) {
                    snapshot = loaded
                    if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("EnvBridge: secret keys reloaded")
                }
            } finally {
                loading.set(false)
            }
        }
    }

    /** Loads the snapshot now, on the calling thread (off the EDT): for explicit refreshes and tests. */
    fun refreshNow(): Snapshot? = load().also { snapshot = it }

    /** Off the EDT. Null when nothing is bound or the account is signed out. */
    private fun load(): Snapshot? {
        val provider = SecretProvider.all().firstOrNull() ?: return null
        val binding = runCatching { SecretBindingService.getInstance(project).binding(provider) }.getOrNull() ?: return null
        val projectId = binding.projectId ?: return null
        val service = SecretsService.getInstance()
        if (runCatching { !service.client(binding.connection).hasCredentials() }.getOrDefault(true)) return null
        val environments = try {
            service.projects(binding.connection).firstOrNull { it.id == projectId }?.environments?.map { it.slug }
        } catch (_: SecretProviderException) {
            null // least-privilege tokens may not list projects: index the bound environment only
        } ?: listOf(binding.environment)
        val keys = LinkedHashMap<String, Map<String, KeyInfo>>()
        for (environment in (listOf(binding.environment) + environments).distinct()) {
            val secrets = runCatching { service.secrets(binding.connection, SecretScope(projectId, environment, "/")) }.getOrNull() ?: continue
            keys[environment] = secrets.associate { it.key to KeyInfo(it.key, it.comment, it.origin, it.path) }
        }
        // Every folder of the bound environment, so completion and the editor can tell same-named keys of different folders apart
        val folders = runCatching { service.secrets(binding.connection, SecretScope(projectId, binding.environment, "/", recursive = true)) }
            .getOrNull().orEmpty()
            .filter { it.origin != SecretOrigin.IMPORTED }
            .groupBy { it.path }
            .mapValues { (_, secrets) -> secrets.associate { it.key to KeyInfo(it.key, it.comment, it.origin, it.path) } }
        return Snapshot(
            folders = folders,
            providerId = provider.id,
            scheme = provider.referenceScheme,
            connectionName = binding.connection.name,
            projectId = projectId,
            // References are absolute: a bare one reads the root folder
            path = "/",
            boundEnvironment = binding.environment,
            environments = keys,
            loadedAt = System.currentTimeMillis(),
        )
    }

    override fun dispose() = Unit

    companion object {
        private const val MAX_AGE_MS = 10 * 60 * 1000L

        fun getInstance(project: Project): KeysIndex = project.service()
    }
}
