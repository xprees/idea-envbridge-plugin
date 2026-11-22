package cz.xprees.secrets.run

import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.resolve.EnvResolution

/**
 * Applies the env-spec decorators of a launch's env files ([EnvFileReferences.Prepared.spec]) to its resolved environment,
 * before [SecretsProgramRunner] hands it to the process:
 *
 * - `@required` keys whose value is missing or empty stop the launch ([Outcome.missingRequired]);
 * - values of `@sensitive` keys are masked in the console like secrets from a secret manager ([Outcome.maskedValues]);
 * - `@internal` keys are left out of the process environment (a value set on the configuration itself is kept);
 * - values that don't fit their `@type` become warnings naming the key and the type, never the value; the launch goes on.
 */
object LaunchChecks {

    /** What the launch does. [environment] and [maskedValues] hold values: never log them (`toString` leaves them out). */
    class Outcome(
        val environment: Map<String, String>,
        val maskedValues: Set<String>,
        val missingRequired: List<String>,
        /** Values that don't fit their `@type`: key and type, never the value. */
        val typeWarnings: List<String>,
    ) {
        override fun toString(): String =
            "Outcome(keys=${environment.keys}, masked=${maskedValues.size}, missingRequired=$missingRequired, typeWarnings=$typeWarnings)"
    }

    fun apply(files: EnvFileReferences.Prepared, resolved: EnvResolution.Result, configured: Map<String, String>): Outcome {
        val check = files.check(resolved.environment)
        val warnings = check.typeMismatches.map { (key, expected) -> SecretsBundle.message("envspec.launch.type", key, expected) }
        val environment = resolved.environment.filterKeys { it !in check.internalKeys || it in configured }
        return Outcome(environment, resolved.secretValues + check.sensitiveValues, check.missingRequired, warnings)
    }
}
