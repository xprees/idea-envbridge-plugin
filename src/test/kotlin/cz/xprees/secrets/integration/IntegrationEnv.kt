package cz.xprees.secrets.integration

import java.nio.file.Files
import java.nio.file.Path

/**
 * Connection details of the real servers the integration tests talk to: environment variables, else the env file
 * `scripts/integration/setup.sh` wrote (`build/integration.env`, passed as the `envbridge.it.env` system property).
 */
object IntegrationEnv {
    private val file: Map<String, String> by lazy {
        val path = System.getProperty("envbridge.it.env")?.let(Path::of) ?: Path.of("build", "integration.env")
        if (!Files.isRegularFile(path)) return@lazy emptyMap()
        Files.readAllLines(path).filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    fun get(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: file[name]?.takeIf { it.isNotBlank() }

    /** [name], or a failure that says how to start the servers. */
    fun require(name: String): String = get(name) ?: throw AssertionError(
        "$name is not set: start the integration servers first (scripts/integration/start.sh), then run ./gradlew integrationTest",
    )
}
