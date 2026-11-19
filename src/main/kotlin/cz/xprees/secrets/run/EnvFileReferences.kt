package cz.xprees.secrets.run

import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import cz.xprees.secrets.core.resolve.DotEnv
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
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
 *
 * Values set on the configuration win over values from files.
 */
object EnvFileReferences {
    private const val MAX_BYTES = 256 * 1024L

    /** The files Node.js tools read from their directory, in the order they apply (later ones win). */
    val DOTENV_NAMES = listOf(".env", ".env.local")

    private data class Cached(val modified: Long, val size: Long, val variables: Map<String, String>)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /** The env files attached to [profile], resolved against the project directory; empty when it has none. */
    fun files(profile: RunProfile): List<Path> {
        if (profile !is RunConfiguration) return emptyList()
        val options = (profile as? EnvFilesOptions)?.envFilePaths.orEmpty()
        val own = EnvPatcher.EP_NAME.extensionList.firstOrNull { runCatching { it.read(profile) }.getOrNull() != null }
            ?.let { runCatching { it.attachedEnvFiles(profile) }.getOrNull() }.orEmpty()
        return (options + own).filter { it.isNotBlank() }.mapNotNull { expand(profile, it) }.distinct()
    }

    /** The `.env` files the program of [profile] reads by itself (existing ones that aren't also attached). */
    fun dotenvFiles(profile: RunProfile): List<Path> {
        if (profile !is RunConfiguration) return emptyList()
        val (patcher, _) = EnvPatcher.find(profile) ?: return emptyList()
        val directory = runCatching { patcher.dotenvDirectory(profile) }.getOrNull()?.let { expand(profile, it) } ?: return emptyList()
        val attached = files(profile).toSet()
        return DOTENV_NAMES.map { directory.resolve(it).normalize() }.filter { it !in attached && Files.isRegularFile(it) }
    }

    /** Variables of the files, in order (later files win), read with a small modification-time cache. */
    fun variables(files: List<Path>): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (file in files) result.putAll(read(file))
        return result
    }

    /** Whether any env file of [profile] (attached or read by the program) contains a secret reference. Cheap enough for `canRun`. */
    fun hasReferences(profile: RunProfile, resolver: LaunchSecretResolver): Boolean {
        val files = files(profile) + dotenvFiles(profile)
        return files.isNotEmpty() && resolver.hasReferences(variables(files))
    }

    /**
     * The variables a launch of [profile] starts from: [configured], under the references of the program's own `.env` files
     * and, when an attached file holds references, all variables of the attached files. The second value says whether the
     * attached files are used (and must be detached from the copy).
     */
    fun effective(profile: RunProfile, configured: Map<String, String>, resolver: LaunchSecretResolver): Pair<Map<String, String>, Boolean> {
        val result = LinkedHashMap<String, String>()
        variables(dotenvFiles(profile)).forEach { (name, value) -> if (resolver.hasReferences(mapOf(name to value))) result[name] = value }
        val attached = variables(files(profile))
        val useFiles = resolver.hasReferences(attached)
        if (useFiles) result.putAll(attached)
        if (result.isEmpty()) return configured to false
        result.putAll(configured)
        return result to useFiles
    }

    /** A copy of [copy] without attached env files (only for copies made by an [EnvPatcher]). */
    fun detach(copy: RunProfile) {
        if (copy is EnvFilesOptions) copy.envFilePaths = emptyList()
        EnvPatcher.find(copy)?.first?.let { runCatching { it.detachEnvFiles(copy) } }
    }

    private fun expand(profile: RunConfiguration, raw: String): Path? {
        val base = profile.project.basePath
        val expanded = raw.trim()
            .replace("\$PROJECT_DIR\$", base.orEmpty())
            .replace("\$USER_HOME\$", System.getProperty("user.home"))
            .let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }
        return runCatching { Path.of(expanded).let { p -> if (p.isAbsolute || base == null) p else Path.of(base).resolve(p) }.normalize() }.getOrNull()
    }

    private fun read(file: Path): Map<String, String> {
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return emptyMap()
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size > MAX_BYTES) return emptyMap()
        cache[file]?.takeIf { it.modified == modified && it.size == size }?.let { return it.variables }
        val variables = runCatching { DotEnv.parse(Files.readString(file)) }.getOrDefault(LinkedHashMap())
        cache[file] = Cached(modified, size, variables)
        return variables
    }
}
