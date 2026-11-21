package cz.xprees.secrets.aws

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads named profiles from the AWS shared files, as the AWS CLI does: `~/.aws/credentials` (sections `[name]`) and
 * `~/.aws/config` (sections `[default]` and `[profile name]`), or the files named by `AWS_SHARED_CREDENTIALS_FILE` /
 * `AWS_CONFIG_FILE`. Read at use time; nothing is copied anywhere.
 */
object AwsProfiles {
    /** A profile's settings from both files (credentials file first, as the CLI reads static keys from it first). */
    class Profile(val name: String, val credentials: Map<String, String>, val config: Map<String, String>) {
        /** Static keys of the credentials file, else of the config file, or null. */
        fun staticKeys(): AwsKeys? {
            for (section in listOf(credentials, config)) {
                val id = section["aws_access_key_id"]?.takeIf { it.isNotBlank() } ?: continue
                val secret = section["aws_secret_access_key"]?.takeIf { it.isNotBlank() } ?: continue
                return AwsKeys(id, secret, section["aws_session_token"]?.takeIf { it.isNotBlank() })
            }
            return null
        }

        /**
         * Whether the profile gets credentials in a way only the AWS CLI implements (IAM Identity Center, assumed roles, web
         * identity, an external process). Assumed roles and SSO take precedence over static keys, as in the AWS CLI.
         */
        val needsCli: Boolean
            get() = config.keys.any { it in CLI_SETTINGS_FIRST } || (staticKeys() == null && (config.containsKey("credential_process") || credentials.containsKey("credential_process")))

        override fun toString(): String = "Profile($name)"
    }

    private val CLI_SETTINGS_FIRST = setOf("role_arn", "web_identity_token_file", "sso_session", "sso_start_url", "sso_account_id")

    /** Environment of the IDE process; tests replace it. */
    @Volatile
    var environment: () -> Map<String, String> = System::getenv

    /** `AWS_SHARED_CREDENTIALS_FILE`, else `~/.aws/credentials`. */
    fun credentialsFile(): Path = environment()["AWS_SHARED_CREDENTIALS_FILE"]?.takeIf { it.isNotBlank() }?.let(::expand)
        ?: Path.of(System.getProperty("user.home"), ".aws", "credentials")

    /** `AWS_CONFIG_FILE`, else `~/.aws/config`. */
    fun configFile(): Path = environment()["AWS_CONFIG_FILE"]?.takeIf { it.isNotBlank() }?.let(::expand)
        ?: Path.of(System.getProperty("user.home"), ".aws", "config")

    private fun expand(path: String): Path = Path.of(if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path)

    /** The profile [name], or null when neither file has it. */
    fun find(name: String): Profile? {
        val credentials = read(credentialsFile())[name]
        val sections = read(configFile())
        val config = if (name == "default") sections["default"] ?: sections["profile default"] else sections["profile $name"]
        if (credentials == null && config == null) return null
        return Profile(name, credentials.orEmpty(), config.orEmpty())
    }

    /**
     * Parses an INI file into sections (name -> key -> value). Comments (`#`, `;`) and indented sub-settings (`s3 =` blocks)
     * are skipped; keys are lower-cased. A missing or unreadable file is empty; files over 1 MB are ignored.
     */
    fun read(file: Path): Map<String, Map<String, String>> {
        if (!Files.isRegularFile(file) || runCatching { Files.size(file) }.getOrDefault(Long.MAX_VALUE) > MAX_FILE_BYTES) return emptyMap()
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return emptyMap()
        return parse(lines)
    }

    /** Parses the lines of an INI file (see [read]). */
    fun parse(lines: List<String>): Map<String, Map<String, String>> {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in lines) {
            if (raw.isBlank()) continue
            // Indented lines belong to a nested block (e.g. `s3 =` followed by settings): not credentials
            if (raw.first().isWhitespace() && current != null && !raw.trimStart().startsWith("[")) continue
            val line = raw.trim()
            if (line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                val name = line.substring(1, line.length - 1).trim().replace(Regex("\\s+"), " ")
                current = sections.getOrPut(name) { linkedMapOf() }
                continue
            }
            val section = current ?: continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            section[line.substring(0, eq).trim().lowercase()] = line.substring(eq + 1).trim()
        }
        return sections
    }

    private const val MAX_FILE_BYTES = 1L shl 20
}
