package cz.xprees.secrets.aws

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Gets temporary credentials for a profile the AWS CLI understands better than EnvBridge (IAM Identity Center, assumed roles,
 * `credential_process`, web identity) by running `aws configure export-credentials --profile <name> --format process`
 * (AWS CLI v2.9+), which prints `{"Version": 1, "AccessKeyId", "SecretAccessKey", "SessionToken", "Expiration"}`.
 *
 * Security: argv carries only the profile name; stdin is closed at once (the CLI must never wait for input); stdout (the
 * credentials) is parsed in memory and never logged; inherited `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` /
 * `AWS_SESSION_TOKEN` / `AWS_PROFILE` are removed so the CLI uses the named profile only.
 */
class AwsCli(private val configuredPath: String?) {

    /** Temporary credentials of [profile]. */
    fun exportCredentials(profile: String, cancelled: () -> Boolean = { false }): AwsKeys {
        // Passed as an argument: a name that looks like an option is refused rather than read by the CLI as one
        if (profile.isBlank() || profile.startsWith("-")) {
            throw SecretProviderException(Kind.CONFIGURATION, "\"$profile\" isn't a usable AWS profile name")
        }
        val output = run(listOf("configure", "export-credentials", "--profile", profile, "--format", "process"), profile, cancelled)
        val o = runCatching { JsonParser.parseString(output).asJsonObject }.getOrNull()
            ?: throw SecretProviderException(Kind.SERVER, "The AWS CLI sent an unexpected answer to export-credentials (not JSON)")
        fun str(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val id = str("AccessKeyId")
        val secret = str("SecretAccessKey")
        if (id == null || secret == null) {
            throw SecretProviderException(Kind.SERVER, "The AWS CLI's export-credentials answer has no access key for profile $profile")
        }
        return AwsKeys(id, secret, str("SessionToken"), str("Expiration")?.let { runCatching { Instant.parse(it) }.getOrNull() })
    }

    private fun run(args: List<String>, profile: String, cancelled: () -> Boolean): String {
        val executable = executable()
        val builder = ProcessBuilder(listOf(executable.toString()) + args)
        builder.environment().keys.removeIf { it in CONFLICTING }
        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw SecretProviderException(Kind.CONFIGURATION, "Cannot run the AWS CLI ($executable): ${e.message ?: e.javaClass.simpleName}", e)
        }
        try {
            val stdout = Drain(process.inputStream, MAX_OUTPUT_BYTES)
            val stderr = Drain(process.errorStream, MAX_ERROR_BYTES)
            process.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled()) throw SecretProviderException(Kind.CANCELLED, "Cancelled")
                ProgressManager.checkCanceled()
                if (System.nanoTime() > deadline) {
                    throw SecretProviderException(Kind.AUTH_REQUIRED, "The AWS CLI did not answer in time for profile $profile")
                }
            }
            val exit = process.exitValue()
            if (exit != 0) {
                LOG.debug("aws configure export-credentials exited with $exit")
                throw failure(stderr.text(), profile)
            }
            return stdout.text()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SecretProviderException(Kind.CANCELLED, "Cancelled", e)
        } catch (e: ProcessCanceledException) {
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** The `aws` binary: the connection's, else found on PATH or in the usual install locations. */
    fun executable(): Path {
        configuredPath?.takeIf { it.isNotBlank() }?.let { configured ->
            val path = runCatching { Path.of(configured.trim()) }.getOrNull()
            if (path == null || !Files.isRegularFile(path)) {
                throw SecretProviderException(Kind.CONFIGURATION, "The AWS CLI was not found at '$configured'; check the connection's settings. $INSTALL_HINT")
            }
            return path
        }
        return findOnPath() ?: throw SecretProviderException(Kind.CONFIGURATION, "The AWS CLI (aws) was not found. $INSTALL_HINT")
    }

    /** Reads a stream to the end on its own thread, so the CLI never blocks on a full pipe. */
    private class Drain(stream: InputStream, private val limit: Int) {
        private val buffer = ByteArrayOutputStream()
        private val thread = Thread({
            try {
                stream.use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(buffer) { if (buffer.size() + n <= limit) buffer.write(chunk, 0, n) }
                    }
                }
            } catch (_: IOException) {
                // the process was killed
            }
        }, "EnvBridge aws reader").apply { isDaemon = true; start() }

        fun text(): String {
            thread.join(DRAIN_JOIN_MS)
            return synchronized(buffer) { buffer.toString(StandardCharsets.UTF_8) }
        }
    }

    companion object {
        private val LOG = logger<AwsCli>()

        /** Variables that would make the CLI use other credentials than the named profile's. */
        private val CONFLICTING = setOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_CREDENTIAL_EXPIRATION")

        private const val POLL_MS = 50L
        private const val DRAIN_JOIN_MS = 5_000L
        private const val MAX_OUTPUT_BYTES = 256 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024
        private const val MAX_MESSAGE = 300

        const val INSTALL_HINT = "Install AWS CLI v2 (https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) or enter its path in the connection's settings."

        /** How long the CLI may take (an assumed role or SSO token refresh goes over the network). */
        @Volatile
        var timeoutMs: Long = 60_000L

        /** Where to look for `aws` besides PATH (an IDE started from the Dock or Start menu may not have the shell's PATH). */
        private fun candidates(): List<Path> {
            val home = System.getProperty("user.home")
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
            val names = if (windows) listOf("aws.exe", "aws.cmd") else listOf("aws")
            val fromPath = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
                .flatMap { dir -> names.map { runCatching { Path.of(dir, it) }.getOrNull() } }.filterNotNull()
            val usual = if (windows) {
                listOfNotNull(System.getenv("ProgramFiles")).map { Path.of(it, "Amazon", "AWSCLIV2", "aws.exe") }
            } else {
                listOf("/opt/homebrew/bin/aws", "/usr/local/bin/aws", "/usr/bin/aws", "/snap/bin/aws", "$home/.local/bin/aws").map { Path.of(it) }
            }
            return fromPath + usual
        }

        private fun findOnPath(): Path? = candidates().firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        /** Classifies the CLI's error text, shown on one line and shortened (it holds no credentials). */
        fun failure(stderr: String, profile: String): SecretProviderException {
            val text = stderr.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                .let { if (it.length > MAX_MESSAGE) it.take(MAX_MESSAGE) + "…" else it }.ifEmpty { "no details" }
            val lower = text.lowercase()
            return when {
                "sso" in lower && ("expired" in lower || "token" in lower || "login" in lower) -> SecretProviderException(
                    Kind.AUTH_REQUIRED,
                    "The IAM Identity Center session of profile $profile has expired or was never started: run `aws sso login --profile $profile`, then try again ($text)",
                )
                "could not be found" in lower || "profile" in lower && "not found" in lower ->
                    SecretProviderException(Kind.CONFIGURATION, "The AWS CLI doesn't know profile $profile ($text)")
                "invalid choice" in lower ->
                    SecretProviderException(Kind.CONFIGURATION, "This AWS CLI can't export credentials; AWS CLI v2.9 or later is needed ($text)")
                "expired" in lower || "accessdenied" in lower || "invalidclienttokenid" in lower || "unable to locate credentials" in lower ->
                    SecretProviderException(Kind.AUTH_REQUIRED, "The AWS CLI couldn't get credentials for profile $profile ($text)")
                else -> SecretProviderException(Kind.CONFIGURATION, "The AWS CLI couldn't get credentials for profile $profile ($text)")
            }
        }
    }
}
