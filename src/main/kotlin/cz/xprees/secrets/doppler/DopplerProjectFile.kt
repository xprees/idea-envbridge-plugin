package cz.xprees.secrets.doppler

import cz.xprees.secrets.core.provider.ProjectFileDefaults
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Doppler CLI's repository file `doppler.yaml` (read by `doppler setup`), which names the project and config:
 *
 * ```yaml
 * setup:
 *   project: backend
 *   config: dev
 * ```
 *
 * or, for a repository with several apps, a list whose entries name the directory (relative to the file) they are for:
 *
 * ```yaml
 * setup:
 *   - project: backend
 *     config: dev
 *     path: services/backend
 *   - project: web
 *     config: dev
 *     path: web/
 * ```
 *
 * Looked up in the project directory and its parents. Of a list, the deepest entry whose directory holds the project directory
 * wins (an entry also covers its subdirectories; `.` or no path is the file's own directory); when none does, the file binds
 * nothing. The user's `~/.doppler/.doppler.yaml` (which holds the CLI's tokens) is never read.
 */
data class DopplerProjectFile(val file: Path, val project: String?, val config: String?) {

    fun toDefaults() = ProjectFileDefaults(file = file, projectId = project, environment = config, branchEnvironment = null, path = null, domain = null)

    companion object {
        val NAMES = listOf("doppler.yaml", "doppler.yml")
        private const val MAX_FILE_BYTES = 256 * 1024L

        fun find(start: Path): DopplerProjectFile? {
            val project = start.toAbsolutePath().normalize()
            var dir: Path? = project
            while (dir != null) {
                for (name in NAMES) {
                    val file = dir.resolve(name)
                    if (Files.isRegularFile(file)) return runCatching { read(file, project) }.getOrNull()
                }
                dir = dir.parent
            }
            return null
        }

        fun read(file: Path, projectDir: Path): DopplerProjectFile? {
            if (Files.size(file) > MAX_FILE_BYTES) return null
            return parse(file, Files.readString(file), projectDir)
        }

        fun parse(file: Path, text: String, projectDir: Path): DopplerProjectFile? {
            // SafeConstructor: plain maps, lists and scalars only
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *> ?: return null
            val entry = when (val setup = root["setup"]) {
                is Map<*, *> -> setup
                is List<*> -> choose(setup.filterIsInstance<Map<*, *>>(), file.toAbsolutePath().normalize().parent, projectDir.toAbsolutePath().normalize())
                else -> null
            } ?: return null
            val project = entry["project"].str()
            val config = entry["config"].str()
            if (project == null && config == null) return null
            return DopplerProjectFile(file, project, config)
        }

        private fun choose(entries: List<Map<*, *>>, fileDir: Path, projectDir: Path): Map<*, *>? {
            fun dirOf(entry: Map<*, *>): Path = fileDir.resolve(entry["path"].str() ?: ".").normalize()
            // The deepest entry whose directory holds the project (an entry covers its subdirectories, as `doppler setup`
            // scopes do); none: no binding from the file (rather than another app's project)
            return entries.filter { projectDir.startsWith(dirOf(it)) }.maxByOrNull { dirOf(it).nameCount }
        }

        private fun Any?.str(): String? = this?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
