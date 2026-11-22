package cz.xprees.secrets.core.resolve

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretOrigin
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The secret **keys** (never values) of the bound project, for completion, hover and inspections: what the provider says to
 * index ([SecretProvider.keysIndexScope]; by default the root folder of every environment and every folder of the bound one).
 * Loaded in the background through the memory-only secrets cache and refreshed when the binding or the connections change or
 * the snapshot is older than [refreshIntervalMs]. Editor features never wait for it: they use what is loaded.
 *
 * Cache rule: the snapshot is reloaded once it is older than the refresh interval (10 minutes, or the cache TTL when that is
 * longer), and its reads accept cached listings up to that same age. So a reload that follows a binding or settings change,
 * or comes soon after the tool window or a hover read the same folder, costs no requests, while the periodic reload, whose
 * own listings are older than the interval by then, reads again. A user's Refresh clears the cache first, so it reads fresh.
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
        /**
         * Environment slug -> keys of its root folder (what a bare reference reads), for the indexed environments only (see
         * [SecretProvider.keysIndexScope]; environments that failed to load are missing).
         */
        val environments: Map<String, Map<String, KeyInfo>>,
        val loadedAt: Long,
        /** Folder path -> keys defined there, for every indexed folder of the bound environment (by default root and subfolders). */
        val folders: Map<String, Map<String, KeyInfo>> = emptyMap(),
        /** Every environment of the bound project, bound one first, indexed or not: for switching and "Run with environment…". */
        val environmentNames: List<String> = environments.keys.toList(),
        /** The folder [folders] was read from (with its subfolders); folders outside it aren't known, not empty. */
        val indexedFolder: String = "/",
        /** Other names a reference may give the bound project (its name and slug, unless another project shares them). */
        val projectAliases: Set<String> = emptySet(),
        /** [SecretProvider.namesIgnoreCase]: [projectAliases] (lowercase here) and folders match ignoring case when unambiguous. */
        val namesIgnoreCase: Boolean = false,
        /** [SecretProvider.keysInSubfolders]. */
        val keysInSubfolders: Boolean = false,
        /** Every listed project as references write it, for completing one (the bound one first). */
        val projectNames: List<String> = emptyList(),
    ) {
        /** Whether a reference naming the project [written] reads the bound one: its ID, or its name where unambiguous. */
        fun namesProject(written: String): Boolean =
            written == projectId || written in projectAliases || (namesIgnoreCase && written.lowercase() in projectAliases)

        /**
         * [path] as the index names that folder: itself, or with [namesIgnoreCase] the one indexed folder that equals it ignoring
         * case (`/stripe` for `/Stripe`).
         */
        fun canonicalFolder(path: String): String {
            val normalized = SecretScope.normalizePath(path)
            if (!namesIgnoreCase || normalized in folders) return normalized
            return folders.keys.singleOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
        }

        /** The keys a reference to [folder] can read, in order: the folder's own, then (with [keysInSubfolders]) its subfolders'. */
        fun keysOf(folder: String): Map<String, KeyInfo> {
            val canonical = canonicalFolder(folder)
            val own = folders[canonical].orEmpty()
            if (!keysInSubfolders) return own
            val result = LinkedHashMap(own)
            folders.filterKeys { it.startsWith(if (canonical == "/") "/" else "$canonical/") && it != canonical }
                .values.forEach { keys -> keys.forEach { (key, info) -> result.putIfAbsent(key, info) } }
            return result
        }

        /** Whether [path] is [indexedFolder] or below it, so [folders] says what it holds. */
        fun isIndexed(path: String): Boolean {
            val folder = SecretScope.normalizePath(path)
            return indexedFolder == "/" || folder == indexedFolder || folder.startsWith("$indexedFolder/")
        }

        /**
         * The text of a reference to [key] (every secret when null) in [path] of [environment] (the bound one when null), as
         * short as the provider's grammar allows.
         */
        fun reference(key: String?, path: String = "/", environment: String? = null): String =
            cz.xprees.secrets.core.reference.SecretReference.grammarFor(scheme)
                .build(scheme, SecretScope(projectId, environment ?: boundEnvironment, path), key, cz.xprees.secrets.core.reference.SecretReference.Defaults(projectId, boundEnvironment))
                .format()

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
        if (current == null || System.currentTimeMillis() - current.loadedAt > refreshIntervalMs()) refreshAsync()
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

    /**
     * Loads the snapshot now, on the calling thread (off the EDT), after a background load that is running has finished: for
     * explicit refreshes and tests.
     */
    fun refreshNow(): Snapshot? {
        while (!loading.compareAndSet(false, true)) Thread.sleep(5)
        try {
            return load().also { snapshot = it }
        } finally {
            loading.set(false)
        }
    }

    /** Off the EDT. Null when nothing is bound or the account is signed out. */
    private fun load(): Snapshot? {
        val provider = SecretBindingService.getInstance(project).boundProvider() ?: return null
        val binding = runCatching { SecretBindingService.getInstance(project).binding(provider) }.getOrNull() ?: return null
        val projectId = binding.projectId ?: return null
        val service = SecretsService.getInstance()
        if (runCatching { !service.client(binding.connection).hasCredentials() }.getOrDefault(true)) return null
        val maxAge = refreshIntervalMs()
        val projects = try {
            service.projects(binding.connection, maxAgeMs = maxAge)
        } catch (_: SecretProviderException) {
            null // least-privilege tokens may not list projects: index the bound environment only
        }
        val boundProject = projects?.firstOrNull { it.id == projectId }
        val listed = boundProject?.environments
        val others = projects.orEmpty().filter { it.id != projectId }
        // A name names the bound project only when no other project has it (ignoring case where the provider does)
        val ignoreCase = provider.namesIgnoreCase
        fun shared(name: String) = others.any { p -> listOf(p.id, p.name, p.slug).any { it.equals(name, ignoreCase = ignoreCase) } }
        val aliases = listOfNotNull(boundProject?.name, boundProject?.slug).filter { it.isNotBlank() && !shared(it) }
            .map { if (ignoreCase) it.lowercase() else it }.toSet()
        val grammar = provider.referenceGrammar
        val projectNames = (listOfNotNull(boundProject) + others).mapNotNull { grammar.projectName(it) }.distinct()
        val bound = SecretScope(projectId, binding.environment, binding.path)
        val environments = listed ?: listOf(ProviderEnvironment(binding.environment, binding.environment))
        val plan = provider.keysIndexScope(bound, environments)
        fun info(entry: SecretEntry) = KeyInfo(entry.key, entry.comment, entry.origin, entry.path)
        val keys = LinkedHashMap<String, Map<String, KeyInfo>>()
        for (environment in plan.environments.distinct()) {
            val secrets = runCatching { service.secrets(binding.connection, SecretScope(projectId, environment, "/"), maxAgeMs = maxAge) }.getOrNull() ?: continue
            keys[environment] = secrets.associate { it.key to info(it) }
        }
        // The indexed folders of the bound environment, so completion and the editor can tell same-named keys of different folders apart
        val folders = plan.folders?.let { scope ->
            runCatching { service.secrets(binding.connection, scope, maxAgeMs = maxAge) }.getOrNull().orEmpty()
                .filter { it.origin != SecretOrigin.IMPORTED }
                .groupBy { it.path }
                .mapValues { (_, secrets) -> secrets.associate { it.key to info(it) } }
        }.orEmpty()
        // Root not read on its own: its keys are what the folder listing found there (none when it starts below the root)
        if (binding.environment !in keys && binding.environment !in plan.environments && plan.folders?.environment == binding.environment) {
            keys[binding.environment] = folders["/"].orEmpty()
        }
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
            environmentNames = (listOf(binding.environment) + environments.map { it.slug }).distinct(),
            indexedFolder = plan.folders?.path?.let(SecretScope::normalizePath) ?: "/",
            projectAliases = aliases,
            namesIgnoreCase = ignoreCase,
            keysInSubfolders = provider.keysInSubfolders,
            projectNames = projectNames,
        )
    }

    override fun dispose() = Unit

    companion object {
        private const val MIN_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * How old a snapshot may get before it is reloaded, and how old cached listings its reads accept: 10 minutes, or the
         * cache TTL when that is longer (the index never refreshes more often than the cache would).
         */
        fun refreshIntervalMs(): Long = maxOf(MIN_REFRESH_INTERVAL_MS, SecretsService.getInstance().cacheTtlMs())

        fun getInstance(project: Project): KeysIndex = project.service()
    }
}
