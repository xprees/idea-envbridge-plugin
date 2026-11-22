package cz.xprees.secrets.run

import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import cz.xprees.secrets.core.envspec.EnvSpecFile
import cz.xprees.secrets.core.envspec.EnvSpecLayers
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.DotEnv
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * `.env` files may hold references instead of secrets, e.g. a committed `.env` with `DB_PASSWORD=infisical://DB_PASSWORD`.
 *
 * - **Attached files** (a configuration's *Environment file(s)* option, or the *Environment file* field of Node.js and npm):
 *   when one holds references, the plugin reads the files itself, resolves the references, puts all their variables into the
 *   copy of the configuration and detaches the files from the copy, so the IDE doesn't load the unresolved values again.
 * - **The program's own `.env`** (Node.js tools such as Nuxt, Vite and Next.js read `.env` and `.env.local` from their
 *   directory by themselves, see [EnvPatcher.dotenvDirectory]): only its references are resolved and passed as process
 *   variables, which those tools keep over the file. Its other lines stay the program's business.
 * - **Layered files** (setting *Layered .env files*, off by default): `.env.schema` < `.env` < `.env.local` < `.env.[env]` <
 *   `.env.[env].local`, `[env]` being the launch's environment ([layerEnvironment]). They replace the program's own pair, and an
 *   attached file named `.env` brings its layered siblings along. EnvBridge then reads them itself and passes **all** their
 *   variables (an attached file is detached from the copy). `.env.schema` contributes only static values (defaults); its
 *   undefined items (`KEY=`) and function calls are not passed on.
 * - **env-spec decorators** (setting on by default, see [EnvSpecLayers]): the decorators of all these files (and of a
 *   `.env.schema` next to them, layered or not) are merged into [Prepared.spec] for the launch checks.
 *
 * Values set on the configuration win over values from files.
 */
object EnvFileReferences {
    private const val MAX_BYTES = 256 * 1024L

    /** The files Node.js tools read from their directory, in the order they apply (later ones win). */
    val DOTENV_NAMES = listOf(".env", ".env.local")

    /** The lowest layer: defaults and decorators (varlock's schema file). */
    const val SCHEMA_NAME = ".env.schema"

    private val SAFE_ENVIRONMENT = Regex("""[A-Za-z0-9_][A-Za-z0-9_\-]*(?:\.[A-Za-z0-9_\-]+)*""")

    /** The layered file names, lowest precedence first; without an environment only the first three. */
    fun layerNames(environment: String?): List<String> {
        val env = environment?.takeIf { SAFE_ENVIRONMENT.matches(it) && it != "local" && it != "schema" }
        return listOfNotNull(SCHEMA_NAME, ".env", ".env.local", env?.let { ".env.$it" }, env?.let { ".env.$it.local" })
    }

    private data class Cached(val modified: Long, val size: Long, val variables: Map<String, String>, val spec: EnvSpecFile)

    private val cache = ConcurrentHashMap<Path, Cached>()

    private val settings get() = SecretsAppSettings.getInstance().state

    /**
     * The environment whose `.env.[env]` files a launch reads: [override] ("Run with environment…"), `ENVBRIDGE_ENVIRONMENT` on
     * the configuration, else the environment the bound provider's binding selects (status bar, project file, default). Null
     * when none can be worked out or the name isn't a plain file name part.
     */
    fun layerEnvironment(profile: RunProfile, configured: Map<String, String>, override: String? = null): String? {
        val explicit = override?.takeIf { it.isNotBlank() } ?: SecretBindingService.environmentOverride(configured)
        val project = (profile as? RunConfiguration)?.project ?: return explicit
        val bindings = SecretBindingService.getInstance(project)
        val environment = runCatching { bindings.boundProvider()?.let { bindings.binding(it, explicit).environment } }.getOrNull()
            ?: explicit ?: ProjectSecretsSettings.getInstance(project).state.environment
        return environment?.takeIf { SAFE_ENVIRONMENT.matches(it) }
    }

    /**
     * The env files attached to [profile], resolved against the project directory; empty when it has none. With
     * [layeredFor] (the environment of layered files, or "" for layering without one), an attached `.env` brings its layered
     * siblings along.
     */
    fun files(profile: RunProfile, layeredFor: String? = null): List<Path> {
        if (profile !is RunConfiguration) return emptyList()
        val options = (profile as? EnvFilesOptions)?.envFilePaths.orEmpty()
        val own = EnvPatcher.EP_NAME.extensionList.firstOrNull { runCatching { it.read(profile) }.getOrNull() != null }
            ?.let { runCatching { it.attachedEnvFiles(profile) }.getOrNull() }.orEmpty()
        val attached = (options + own).filter { it.isNotBlank() }.mapNotNull { expand(profile, it) }.distinct()
        if (layeredFor == null) return attached
        return attached.flatMap { file ->
            if (file.fileName?.toString() != ".env") return@flatMap listOf(file)
            layerNames(layeredFor.ifEmpty { null }).map { file.resolveSibling(it) }.filter { it == file || Files.isRegularFile(it) }
        }.distinct()
    }

    /**
     * The `.env` files the program of [profile] reads by itself (existing ones that aren't also attached); with [layeredFor],
     * the layered files of that directory instead.
     */
    fun dotenvFiles(profile: RunProfile, layeredFor: String? = null): List<Path> {
        val directory = dotenvDirectory(profile) ?: return emptyList()
        val attached = files(profile, layeredFor).toSet()
        val names = if (layeredFor != null) layerNames(layeredFor.ifEmpty { null }) else DOTENV_NAMES
        return names.map { directory.resolve(it).normalize() }.filter { it !in attached && Files.isRegularFile(it) }
    }

    private fun dotenvDirectory(profile: RunProfile): Path? {
        if (profile !is RunConfiguration) return null
        val (patcher, _) = EnvPatcher.find(profile) ?: return null
        return runCatching { patcher.dotenvDirectory(profile) }.getOrNull()?.let { expand(profile, it) }
    }

    /** Variables of the files, in order (later files win), read with a small modification-time cache. */
    fun variables(files: List<Path>): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (file in files) result.putAll(read(file)?.variables.orEmpty())
        return result
    }

    /** Whether any env file of [profile] (attached or read by the program) contains a secret reference. Cheap enough for `canRun`. */
    fun hasReferences(profile: RunProfile, resolver: LaunchSecretResolver): Boolean {
        val layered = layeredFor(profile, EnvPatcher.find(profile)?.second.orEmpty(), null)
        val files = files(profile, layered) + dotenvFiles(profile, layered)
        return files.isNotEmpty() && resolver.hasReferences(variables(files))
    }

    /**
     * Whether the env files of [profile] use env-spec decorators EnvBridge acts on (and the setting is on): such launches go
     * through EnvBridge for the checks even without references. Cheap enough for `canRun` (parsed files are cached).
     */
    fun usesDecorators(profile: RunProfile): Boolean {
        if (!settings.envSpecDecorators) return false
        val layered = layeredFor(profile, EnvPatcher.find(profile)?.second.orEmpty(), null)
        return specFiles(profile, layered).any { file -> read(file)?.spec?.let(EnvSpecLayers::usesNative) == true }
    }

    /**
     * What a launch of [profile] starts from.
     *
     * @property envs [configured], under the references of the program's own `.env` files (all their variables when layered)
     *   and, when the attached files are used, all variables of the attached files
     * @property detach whether the attached files are used (and must be detached from the copy): they hold references,
     *   declare `@internal` keys, or layering added files to them
     * @property spec the merged decorators ([EnvSpecLayers.NONE] when off or unused)
     * @property fileValues every variable of every file read (references unresolved), for the checks
     * @property environment the environment used for the layered files and `forEnv(…)`, if any
     */
    class Prepared(
        val envs: Map<String, String>,
        val detach: Boolean,
        val spec: EnvSpecLayers,
        val fileValues: Map<String, String>,
        val environment: String?,
    ) {
        /**
         * Checks the launch's final environment: the files' values overlaid with [resolved] (resolved references and values set
         * on the configuration).
         */
        fun check(resolved: Map<String, String>): EnvSpecLayers.Check {
            if (!spec.active) return EnvSpecLayers.NONE.check(emptyMap())
            return spec.check(LinkedHashMap(fileValues).apply { putAll(resolved) })
        }

        override fun toString(): String = "Prepared(keys=${envs.keys}, detach=$detach, environment=$environment)"
    }

    /** Reads the env files of a launch of [profile]; [override] is the environment of "Run with environment…". Reads the disk. */
    fun prepare(profile: RunProfile, configured: Map<String, String>, resolver: LaunchSecretResolver, override: String? = null): Prepared {
        val layeredOn = settings.layeredEnvFiles
        val selected = if (layeredOn) layerEnvironment(profile, configured, override) else null
        val layered = if (layeredOn) selected.orEmpty() else null
        val plainAttached = files(profile)
        val attachedFiles = files(profile, layered)
        val dotenvFiles = dotenvFiles(profile, layered)
        val attached = variables(attachedFiles)
        val dotenv = variables(dotenvFiles)
        val specs = if (settings.envSpecDecorators) specFiles(profile, layered).mapNotNull { read(it)?.spec } else emptyList()
        // The environment also answers forEnv(…) in decorators
        val environment = if (layeredOn) selected else if (specs.any(EnvSpecLayers::usesNative)) layerEnvironment(profile, configured, override) else null
        val spec = EnvSpecLayers.of(specs, environment)
        val internal = spec.keys.values.filter { it.internal }.map { it.key }
        val useFiles = resolver.hasReferences(attached) || attachedFiles != plainAttached || internal.any { it in attached }
        val result = LinkedHashMap<String, String>()
        dotenv.forEach { (name, value) -> if (layered != null || resolver.hasReferences(mapOf(name to value))) result[name] = value }
        if (useFiles) result.putAll(attached)
        val fileValues = LinkedHashMap(dotenv).apply { putAll(attached) }
        if (result.isEmpty()) return Prepared(configured, false, spec, fileValues, environment)
        result.putAll(configured)
        return Prepared(result, useFiles, spec, fileValues, environment)
    }

    /**
     * The variables a launch of [profile] starts from and whether the attached files are used (see [prepare]).
     */
    fun effective(profile: RunProfile, configured: Map<String, String>, resolver: LaunchSecretResolver): Pair<Map<String, String>, Boolean> =
        prepare(profile, configured, resolver).let { it.envs to it.detach }

    /** A copy of [copy] without attached env files (only for copies made by an [EnvPatcher]). */
    fun detach(copy: RunProfile) {
        if (copy is EnvFilesOptions) copy.envFilePaths = emptyList()
        EnvPatcher.find(copy)?.first?.let { runCatching { it.detachEnvFiles(copy) } }
    }

    /** The environment for layered files ("" without one), or null when layering is off. */
    private fun layeredFor(profile: RunProfile, configured: Map<String, String>, override: String?): String? =
        if (settings.layeredEnvFiles) layerEnvironment(profile, configured, override).orEmpty() else null

    /** The files whose decorators count, lowest precedence first: schemas next to them, the program's files, attached files. */
    private fun specFiles(profile: RunProfile, layered: String?): List<Path> {
        val files = dotenvFiles(profile, layered) + files(profile, layered)
        val directories = (listOfNotNull(dotenvDirectory(profile)) + files.mapNotNull { it.parent }).distinct()
        val schemas = directories.map { it.resolve(SCHEMA_NAME).normalize() }.filter { it !in files && Files.isRegularFile(it) }
        return (schemas + files).distinct()
    }

    private fun expand(profile: RunConfiguration, raw: String): Path? {
        val base = profile.project.basePath
        val expanded = raw.trim()
            .replace("\$PROJECT_DIR\$", base.orEmpty())
            .replace("\$USER_HOME\$", System.getProperty("user.home"))
            .let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }
        return runCatching { Path.of(expanded).let { p -> if (p.isAbsolute || base == null) p else Path.of(base).resolve(p) }.normalize() }.getOrNull()
    }

    private fun read(file: Path): Cached? {
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size > MAX_BYTES) return null
        cache[file]?.takeIf { it.modified == modified && it.size == size }?.let { return it }
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val spec = runCatching { EnvSpecParser.parse(text) }.getOrDefault(EnvSpecFile.EMPTY)
        val variables = if (file.fileName?.toString() == SCHEMA_NAME) {
            schemaDefaults(spec)
        } else {
            runCatching { DotEnv.parse(text, SecretProvider.all().map { it.referenceScheme }) }.getOrDefault(LinkedHashMap())
        }
        return Cached(modified, size, variables, spec).also { cache[file] = it }
    }

    /** The static values of a schema: undefined items (`KEY=`), references and function calls are left out. */
    private fun schemaDefaults(spec: EnvSpecFile): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (item in spec.items) {
            val literal = item.value as? EnvSpecValue.Literal
            if (literal != null) result[item.key] = literal.value else result.remove(item.key)
        }
        return result
    }
}
