package cz.xprees.secrets.infisical.config

import com.google.gson.JsonParser
import cz.xprees.secrets.core.provider.ProjectFileDefaults
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Infisical CLI's project config: `.infisical.yaml` (current) or `.infisical.json` (older `infisical init`).
 * Looked up like the CLI does: the directory, then each parent; in each directory `.infisical.yaml` before `.infisical.json`.
 * Only the documented keys are read; unknown keys are ignored like the CLI ignores them.
 */
data class InfisicalProjectConfig(
    val file: Path,
    val projectId: String?,
    val environment: String?,
    /** Branch name (last segment) -> environment slug, in file order. */
    val branchEnvironments: List<Pair<String, String>>,
    val secretPath: String?,
    val domain: String?,
) {
    /** The environment for [branch]: matched against the last segment after `/`, as the CLI does. */
    fun environmentForBranch(branch: String?): String? {
        val name = branch?.substringAfterLast('/') ?: return null
        return branchEnvironments.firstOrNull { it.first == name }?.second
    }

    fun toDefaults(branch: String?) = ProjectFileDefaults(
        file = file,
        projectId = projectId,
        environment = environment,
        branchEnvironment = environmentForBranch(branch),
        path = secretPath,
        domain = domain,
    )

    companion object {
        const val YAML = ".infisical.yaml"
        const val JSON = ".infisical.json"
        private const val MAX_FILE_BYTES = 256 * 1024L

        fun find(start: Path): InfisicalProjectConfig? {
            var dir: Path? = start.toAbsolutePath().normalize()
            while (dir != null) {
                for (name in listOf(YAML, JSON)) {
                    val file = dir.resolve(name)
                    if (Files.isRegularFile(file)) return runCatching { read(file) }.getOrNull()
                }
                dir = dir.parent
            }
            return null
        }

        fun read(file: Path): InfisicalProjectConfig? {
            if (Files.size(file) > MAX_FILE_BYTES) return null
            val text = Files.readString(file)
            return if (file.fileName.toString() == JSON) parseJson(file, text) else parseYaml(file, text)
        }

        fun parseYaml(file: Path, text: String): InfisicalProjectConfig? {
            // SafeConstructor: plain maps, lists and scalars only (no arbitrary object construction from tags)
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *> ?: return null
            val general = root["general"] as? Map<*, *>
            val sm = root["secrets-management"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val overrides = (sm["overrides"] as? Map<*, *>)?.get("git-branch") as? List<*>
            val branchList = overrides?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val branch = m["branch"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val env = m["environment"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                branch to env
            }.orEmpty()
            // Older keys, read only when the newer ones are absent (as the CLI does)
            val legacyBranches = ((sm["mappings"] as? Map<*, *>)?.get("git-branch-to-environment") as? Map<*, *>)
                ?.entries?.mapNotNull { (k, v) -> if (k != null && v != null) k.toString() to v.toString() else null }.orEmpty()
            return InfisicalProjectConfig(
                file = file,
                projectId = sm["project-id"].str(),
                environment = sm["environment"].str() ?: sm["default-environment"].str(),
                branchEnvironments = branchList.ifEmpty { legacyBranches },
                secretPath = sm["secret-path"].str() ?: sm["default-secret-path"].str(),
                domain = general?.get("domain").str()?.takeIf(::isHttpUrl),
            )
        }

        fun parseJson(file: Path, text: String): InfisicalProjectConfig? {
            val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
            fun s(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            val branches = o.get("gitBranchToEnvironmentMapping")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                ?.mapNotNull { (k, v) -> if (v.isJsonPrimitive) k to v.asString else null }.orEmpty()
            return InfisicalProjectConfig(
                file = file,
                projectId = s("workspaceId"),
                environment = s("defaultEnvironment"),
                branchEnvironments = branches,
                secretPath = s("defaultSecretPath"),
                domain = s("domain")?.takeIf(::isHttpUrl),
            )
        }

        private fun Any?.str(): String? = this?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        private fun isHttpUrl(value: String) = value.startsWith("https://") || value.startsWith("http://")
    }
}
