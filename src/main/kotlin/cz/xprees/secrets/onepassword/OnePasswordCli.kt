package cz.xprees.secrets.onepassword

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.onepassword.OnePasswordJson.str
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Reads and writes through the 1Password CLI (`op`), run as a child process with `--format json`:
 *
 * - **desktop app** ([serviceAccount] false): `op` asks the 1Password app to authorize it (Touch ID, Windows Hello, system
 *   password, …), so EnvBridge never sees a password or session token. An optional `--account` picks the account.
 * - **service account**: the token from the IDE's password storage is handed to `op` only in the environment variable
 *   `OP_SERVICE_ACCOUNT_TOKEN`, never on the command line (other processes can read command lines).
 *
 * Inherited variables that would make `op` use other credentials (`OP_SERVICE_ACCOUNT_TOKEN`, `OP_CONNECT_*`, `OP_SESSION_*`)
 * are removed. Nothing `op` prints is logged; its error text is shown shortened. Writes hand `op` the item's JSON on stdin,
 * never a value on the command line.
 */
class OnePasswordCli(private val connection: SecretConnection, private val serviceAccount: Boolean) : OnePasswordBackend {

    override fun signIn(interaction: SignInInteraction): String {
        interaction.status(if (serviceAccount) "Checking the service account token…" else "Waiting for the 1Password app to authorize the CLI…")
        // Listing vaults is what makes the app ask for authorization (whoami alone doesn't)
        run(listOf("vault", "list"), interaction::isCancelled)
        val whoami = runCatching { json(run(listOf("whoami"), interaction::isCancelled)).asJsonObject }.getOrNull()
        return describe(whoami) ?: connection.option(OnePasswordAuthMethods.OPTION_ACCOUNT) ?: connection.name
    }

    override fun vaults(): List<OpVault> = OnePasswordJson.vaults(json(run(listOf("vault", "list"))))

    override fun items(vault: String): List<OpItemSummary> = OnePasswordJson.items(json(run(listOf("item", "list", "--vault", vault))))

    override fun itemJson(vault: String, item: String): JsonObject {
        val args = listOf("item", "get", item, "--vault", vault)
        // `--reveal` (op 2.24+) makes sure concealed values are in the output; older versions don't know it (and include them)
        val output = try {
            run(args + "--reveal")
        } catch (e: SecretProviderException) {
            if (e.message?.contains("unknown flag", ignoreCase = true) != true) throw e
            run(args)
        }
        return json(output).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw SecretProviderException(Kind.SERVER, "The 1Password CLI sent an unexpected answer to item get")
    }

    /**
     * `op item create --vault <vault>` with the item's JSON template on stdin (as `op item get … | op item create` does), so
     * no value is ever on `op`'s command line.
     */
    override fun createItem(vault: String, template: JsonObject): JsonObject =
        written(run(listOf("item", "create", "--vault", vault), input = template.toString()), "item create")

    /**
     * `op item edit <id> --vault <vault>` with the whole changed item JSON on stdin (recent op 2.x edits an item from piped JSON).
     * Never `op item edit <item> field=value`: assignment statements would put the value on the command line.
     */
    override fun replaceItem(vault: String, item: JsonObject): JsonObject {
        val id = item.str("id")?.takeIf { it.isNotBlank() } ?: throw SecretProviderException(Kind.SERVER, "The item has no ID")
        refuseMaskedValues(item)
        val target = item.getAsJsonObject("vault")?.str("id")?.takeIf { it.isNotBlank() } ?: vault
        return written(run(listOf("item", "edit", id, "--vault", target), input = item.toString()), "item edit")
    }

    private fun written(output: String, what: String): JsonObject =
        json(output).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { LOG.debug("op $what printed no item") }

    /** Whether a token is stored (service account), or the app is used (no stored credentials needed). */
    fun hasToken(): Boolean = token() != null

    private fun token(): String? =
        CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(OnePasswordAuthMethods.OPTION_SERVICE_ACCOUNT_TOKEN))?.takeIf { it.isNotBlank() }

    /**
     * Runs `op <args> --format json` and returns what it printed on stdout. [input] (an item's JSON, values included) is
     * written to its stdin, else stdin is closed at once. Neither is ever logged.
     */
    fun run(args: List<String>, cancelled: () -> Boolean = { false }, input: String? = null): String {
        val executable = executable()
        val command = buildList {
            add(executable.toString())
            addAll(args)
            add("--format")
            add("json")
            if (!serviceAccount) connection.option(OnePasswordAuthMethods.OPTION_ACCOUNT)?.let { add("--account"); add(it) }
        }
        val builder = ProcessBuilder(command)
        val env = builder.environment()
        env.keys.removeIf { key -> key in CONFLICTING || key.startsWith("OP_SESSION_") || (serviceAccount && key == "OP_ACCOUNT") }
        if (serviceAccount) {
            env["OP_SERVICE_ACCOUNT_TOKEN"] = token() ?: throw SecretProviderException(
                Kind.CONFIGURATION,
                "Connection '${connection.name}' has no stored service account token; edit it in the settings",
            )
        }
        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw SecretProviderException(Kind.CONFIGURATION, "Cannot run the 1Password CLI ($executable): ${e.message ?: e.javaClass.simpleName}", e)
        }
        val what = args.take(2).joinToString(" ")
        try {
            val stdout = Drain(process.inputStream, MAX_OUTPUT_BYTES)
            val stderr = Drain(process.errorStream, MAX_ERROR_BYTES)
            if (input == null) {
                // No input: `op` must never wait for a password on stdin
                process.outputStream.close()
            } else {
                // On its own thread, so a process that doesn't read stdin can't block us past the timeout
                Thread({
                    try {
                        process.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
                    } catch (_: IOException) {
                        // the process ended or was killed
                    }
                }, "EnvBridge op writer").apply { isDaemon = true; start() }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs ?: if (serviceAccount) SERVICE_TIMEOUT_MS else APP_TIMEOUT_MS)
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled()) throw SecretProviderException(Kind.CANCELLED, "Cancelled")
                ProgressManager.checkCanceled()
                if (System.nanoTime() > deadline) {
                    throw SecretProviderException(
                        if (serviceAccount) Kind.NETWORK else Kind.AUTH_REQUIRED,
                        "The 1Password CLI did not answer in time (op $what)" +
                            if (serviceAccount) "" else "; is the 1Password app running and unlocked, with CLI integration on?",
                    )
                }
            }
            val out = stdout.text()
            val exit = process.exitValue()
            if (exit != 0) {
                LOG.debug("op $what exited with $exit")
                throw failure(stderr.text(), what)
            }
            if (stdout.truncated) throw SecretProviderException(Kind.SERVER, "The 1Password CLI's answer to op $what is too large")
            return out
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SecretProviderException(Kind.CANCELLED, "Cancelled", e)
        } catch (e: ProcessCanceledException) {
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** The `op` binary: the connection's, else found on PATH or in the usual install locations. */
    fun executable(): Path {
        connection.option(OnePasswordAuthMethods.OPTION_OP_PATH)?.let { configured ->
            val path = runCatching { Path.of(configured.trim()) }.getOrNull()
            if (path == null || !Files.isRegularFile(path)) {
                throw SecretProviderException(Kind.CONFIGURATION, "The 1Password CLI was not found at '$configured'; check the connection's settings. $INSTALL_HINT")
            }
            return path
        }
        return findOnPath() ?: throw SecretProviderException(Kind.CONFIGURATION, "The 1Password CLI (op) was not found. $INSTALL_HINT")
    }

    private fun json(text: String): JsonElement = try {
        if (text.isBlank()) com.google.gson.JsonNull.INSTANCE else JsonParser.parseString(text)
    } catch (e: Exception) {
        throw SecretProviderException(Kind.SERVER, "The 1Password CLI sent an unexpected answer (not JSON)", e)
    }

    /** Reads a stream to the end on its own thread (stdout and stderr together, so `op` never blocks on a full pipe). */
    private class Drain(stream: InputStream, private val limit: Int) {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var truncated = false
            private set

        private val thread = Thread({
            try {
                stream.use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(buffer) {
                            if (buffer.size() + n <= limit) buffer.write(chunk, 0, n) else truncated = true
                        }
                    }
                }
            } catch (_: IOException) {
                // the process was killed
            }
        }, "EnvBridge op reader").apply { isDaemon = true; start() }

        fun text(): String {
            thread.join(DRAIN_JOIN_MS)
            return synchronized(buffer) { buffer.toString(StandardCharsets.UTF_8) }
        }
    }

    companion object {
        private val LOG = logger<OnePasswordCli>()

        /** Variables that would make `op` use other credentials than the connection's. */
        private val CONFLICTING = setOf("OP_SERVICE_ACCOUNT_TOKEN", "OP_CONNECT_HOST", "OP_CONNECT_TOKEN")

        const val APP_TIMEOUT_MS = 120_000L // the user may have to approve in the app
        const val SERVICE_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 50L
        private const val DRAIN_JOIN_MS = 5_000L
        private const val MAX_OUTPUT_BYTES = 32 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024
        private const val MAX_MESSAGE = 300

        const val INSTALL_HINT = "Install it (https://developer.1password.com/docs/cli/get-started/) or enter its path in the connection's settings."

        /** What `op` 2.24+ prints instead of a concealed value when `--reveal` is missing. */
        private const val MASKED_PREFIX = "[use 'op item get"

        /**
         * Refuses to write an item whose concealed values came back masked: writing it would replace the real values with the
         * placeholder text.
         */
        fun refuseMaskedValues(item: JsonObject) {
            val masked = item.getAsJsonArray("fields")?.any { f ->
                f.isJsonObject && f.asJsonObject.str("value")?.startsWith(MASKED_PREFIX) == true
            } == true
            if (masked) {
                throw SecretProviderException(Kind.SERVER, "The 1Password CLI returned masked values; nothing was changed. Update the 1Password CLI")
            }
        }

        /** Overrides the timeout (tests). */
        @Volatile
        var timeoutMs: Long? = null

        /** Where to look for `op` besides PATH (an IDE started from the Dock or Start menu may not have the shell's PATH). */
        fun candidates(): List<Path> {
            val home = System.getProperty("user.home").orEmpty()
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
            val names = if (windows) listOf("op.exe") else listOf("op")
            val fromPath = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
                .flatMap { dir -> names.map { runCatching { Path.of(dir, it) }.getOrNull() } }.filterNotNull()
            val usual = if (windows) {
                listOfNotNull(
                    System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Microsoft", "WinGet", "Links", "op.exe") },
                    System.getenv("ProgramFiles")?.let { Path.of(it, "1Password CLI", "op.exe") },
                    System.getenv("LOCALAPPDATA")?.let { Path.of(it, "1Password", "app", "8", "op.exe") },
                )
            } else {
                listOf("/opt/homebrew/bin/op", "/usr/local/bin/op", "/usr/bin/op", "/snap/bin/op", "$home/.local/bin/op", "$home/bin/op")
                    .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
            }
            return (fromPath + usual).distinct()
        }

        fun findOnPath(): Path? = candidates().firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        /** "alice@example.com (my.1password.com)" or "Service account (my.1password.com)" from `op whoami`. */
        fun describe(whoami: com.google.gson.JsonObject?): String? {
            whoami ?: return null
            val host = whoami.str("url")?.removePrefix("https://")?.trimEnd('/')
            val who = when {
                whoami.str("user_type").equals("SERVICE_ACCOUNT", ignoreCase = true) -> "Service account"
                else -> whoami.str("email") ?: return host
            }
            return if (host.isNullOrEmpty()) who else "$who ($host)"
        }

        /**
         * Maps what `op` wrote to stderr to an exception. The text is shown without its `[ERROR] <date> <time>` prefix,
         * on one line and shortened; `op` doesn't print secret values in errors.
         */
        fun failure(stderr: String, what: String): SecretProviderException {
            val text = stderr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" ") { it.replace(Regex("""^\[(ERROR|WARN)]\s+\S+\s+\S+\s+"""), "") }
                .take(MAX_MESSAGE)
            val lower = text.lowercase()
            val kind = when {
                listOf(
                    "not signed in", "not currently signed in", "sign in", "signin", "session expired", "authorization prompt",
                    "authorization timeout", "locked", "no accounts configured", "invalid service account token",
                    "service account token is invalid", "token is invalid", "unauthorized", "authentication required",
                    "connecting to desktop app", "cannot connect to 1password app", "is the app running",
                ).any { it in lower } -> Kind.AUTH_REQUIRED
                listOf("isn't a vault", "isn't an item", "not found", "no item found", "doesn't exist").any { it in lower } -> Kind.NOT_FOUND
                listOf("more than one", "multiple items", "ambiguous").any { it in lower } -> Kind.CONFIGURATION
                listOf("forbidden", "permission", "not authorized", "access denied", "doesn't have access").any { it in lower } -> Kind.FORBIDDEN
                listOf("dial tcp", "no such host", "connection refused", "timeout", "timed out", "network").any { it in lower } -> Kind.NETWORK
                listOf("unknown flag", "unknown command", "unknown shorthand").any { it in lower } -> Kind.CONFIGURATION
                else -> Kind.SERVER
            }
            val hint = when {
                kind == Kind.CONFIGURATION && "more than one" in lower -> " (name it by its ID)"
                else -> ""
            }
            val detail = text.ifEmpty { "no details" }
            return SecretProviderException(kind, "1Password CLI (op $what): $detail$hint")
        }
    }
}
