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
 * `.env` files attached to a run configuration (its *Environment file(s)* option) may hold references instead of secrets,
 * e.g. a committed `.env` with `DB_PASSWORD=infisical://DB_PASSWORD`. When one does, the plugin reads the files itself, resolves
 * the references, puts all variables into the copy of the configuration and detaches the files from the copy, so the IDE
 * doesn't load the unresolved values again. Values set on the configuration still win over values from files.
 */
object EnvFileReferences {
    private const val MAX_BYTES = 256 * 1024L

    private data class Cached(val modified: Long, val size: Long, val variables: Map<String, String>)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /** The env files of [profile], resolved against the project directory; empty when it has none. */
    fun files(profile: RunProfile): List<Path> {
        if (profile !is EnvFilesOptions || profile !is RunConfiguration) return emptyList()
        val base = profile.project.basePath
        return profile.envFilePaths.orEmpty().filter { it.isNotBlank() }.mapNotNull { raw ->
            val expanded = raw.trim()
                .replace("\$PROJECT_DIR\$", base.orEmpty())
                .replace("\$USER_HOME\$", System.getProperty("user.home"))
                .let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }
            runCatching { Path.of(expanded).let { p -> if (p.isAbsolute || base == null) p else Path.of(base).resolve(p) }.normalize() }.getOrNull()
        }
    }

    /** Variables of the files, in order (later files win), read with a small modification-time cache. */
    fun variables(files: List<Path>): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (file in files) result.putAll(read(file))
        return result
    }

    /** Whether any env file of [profile] contains a secret reference. Cheap enough for `canRun`. */
    fun hasReferences(profile: RunProfile, resolver: LaunchSecretResolver): Boolean {
        val files = files(profile)
        return files.isNotEmpty() && resolver.hasReferences(variables(files))
    }

    /**
     * The variables a launch of [profile] starts from: [configured] alone, or, when an env file holds references, the files'
     * variables overlaid with [configured]. The second value says whether the files are used (and must be detached).
     */
    fun effective(profile: RunProfile, configured: Map<String, String>, resolver: LaunchSecretResolver): Pair<Map<String, String>, Boolean> {
        val fileVariables = variables(files(profile))
        if (!resolver.hasReferences(fileVariables)) return configured to false
        return LinkedHashMap(fileVariables).apply { putAll(configured) } to true
    }

    /** A copy of [copy] without env files (only for copies made by an [EnvPatcher]). */
    fun detach(copy: RunProfile) {
        if (copy is EnvFilesOptions) copy.envFilePaths = emptyList()
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
