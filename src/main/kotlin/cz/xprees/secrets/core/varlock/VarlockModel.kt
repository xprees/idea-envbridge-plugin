package cz.xprees.secrets.core.varlock

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference

/**
 * The varlock plugins whose secret functions EnvBridge resolves itself, through its own connections (varlock 1.25.0 and its
 * plugins: `@varlock/infisical-plugin` 2.1, `@varlock/hashicorp-vault-plugin` 2.1, `@varlock/1password-plugin` 2.0,
 * `@varlock/doppler-plugin` 2.0, `@varlock/aws-secrets-plugin` 1.3). Nothing of the plugins is loaded or run.
 *
 * @property scheme the EnvBridge reference scheme the function maps to
 * @property initDecorator the root decorator that declares an instance (`@initInfisical(…)`)
 * @property functions the item functions of the plugin
 * @property connectionArgs arguments of [initDecorator] EnvBridge reads to map the instance to a connection or a reference;
 *   every other argument is ignored (credentials above all, see [credentialArgs])
 * @property credentialArgs arguments that carry credentials: never read; written inline they cause a warning
 */
enum class VarlockProvider(
    val scheme: String,
    val initDecorator: String,
    val functions: Set<String>,
    val connectionArgs: Set<String>,
    val credentialArgs: Set<String>,
) {
    INFISICAL(
        "infisical", "initInfisical", setOf("infisical"),
        setOf("projectId", "environment", "secretPath", "siteUrl", "allowMissing"),
        setOf("clientId", "clientSecret", "identityId", "oidcToken"),
    ),
    VAULT(
        "vault", "initHcpVault", setOf("vaultSecret"),
        setOf("url", "namespace", "defaultPath", "pathPrefix"),
        setOf("token", "roleId", "secretId", "jwtRole", "jwtAuthPath", "oidcToken"),
    ),
    ONEPASSWORD("op", "initOp", setOf("op"), setOf("account", "allowMissing"), setOf("token", "connectToken")),
    DOPPLER("doppler", "initDoppler", setOf("doppler"), setOf("project", "config"), setOf("serviceToken")),
    AWS(
        "aws-sm", "initAws", setOf("awsSecret"), setOf("region", "namePrefix"),
        setOf("accessKeyId", "secretAccessKey", "sessionToken", "oidcToken"),
    ),
    ;

    companion object {
        /** The provider of the item function [name], or null. */
        fun ofFunction(name: String): VarlockProvider? = entries.firstOrNull { name in it.functions }

        /** The provider declared by the root decorator [name], or null. */
        fun ofInit(name: String): VarlockProvider? = entries.firstOrNull { it.initDecorator == name }

        /** Credential arguments that carry a secret itself (warned about when written inline), not just an identifier. */
        val SECRET_CREDENTIALS = setOf("clientSecret", "oidcToken", "token", "secretId", "connectToken", "serviceToken", "secretAccessKey", "sessionToken")
    }
}

/** The instance a function names when it names none (varlock's own name for it). */
const val DEFAULT_INSTANCE = "_default"

/**
 * A compiled varlock value: the env-spec AST with string interpolation expanded (`"a-${B}"` is `concat("a-", ref(B))`), the
 * functions EnvBridge supports, and secret functions waiting for their arguments. Nothing here holds a secret value.
 */
sealed interface VarlockExpr {
    /** A string, or (unquoted `true` / `false`) a boolean. */
    data class Text(val value: String, val bool: Boolean? = null) : VarlockExpr

    /** No value (`KEY=`, unquoted `undefined`). */
    data object Undefined : VarlockExpr

    data class Ref(val name: String) : VarlockExpr

    /** A function EnvBridge evaluates itself: `concat`, `fallback`, `forEnv`, `eq`, `if`, `ifs`, `not`, `and`, `or`, `isEmpty`. */
    data class Pure(val function: String, val args: List<VarlockExpr>) : VarlockExpr

    /** A secret manager function (`infisical(…)`, `vaultSecret(…)`, …), resolved through an EnvBridge connection. */
    data class Secret(val call: SecretCall) : VarlockExpr
}

/**
 * A secret function call: [positional] are the arguments after the instance ID (if any), [named] the `key=value` ones.
 * [itemKey] is the item it is the value of (the default secret name of `infisical()`, `vaultSecret()`, …).
 */
data class SecretCall(
    val function: String,
    val provider: VarlockProvider,
    val instance: String,
    val positional: List<VarlockExpr>,
    val named: Map<String, VarlockExpr>,
    val itemKey: String,
)

/** Why a varlock value can't be resolved. [args] hold names (keys, functions, paths), never values. */
data class VarlockProblem(val code: Code, val key: String?, val args: List<String> = emptyList()) {
    enum class Code {
        /** A function EnvBridge never runs from the IDE (args: function, reason [RefusalReason]). */
        REFUSED,

        /** A function EnvBridge doesn't evaluate (args: function). */
        UNSUPPORTED,

        /** A supported function with arguments EnvBridge can't use (args: function, detail). */
        INVALID_CALL,

        /** A function names an instance no `@init…(id=…)` declares (args: function, instance). */
        UNKNOWN_INSTANCE,

        /** Items that refer to each other in a circle (args: the keys). */
        CYCLE,

        /** `forEnv(…)` without a current environment. */
        NO_ENVIRONMENT,

        /** A secret the function names doesn't exist (args: function, where it was looked for). */
        MISSING_SECRET,

        /** `@setValuesBulk(…)`: bulk loading through plugins isn't supported (args: function). */
        BULK,

        /** `@import(…)` names a file outside the project (args: path). */
        IMPORT_OUTSIDE,

        /** `@import(…)` names a file that doesn't exist (without `allowMissing=true`) or a directory (args: path). */
        IMPORT_MISSING,

        /** `@currentEnv` or an `@init…` argument depends on a secret function (args: decorator). */
        SECRET_IN_DECORATOR,

        /** An instance declares a URL (or region) no EnvBridge connection has (args: provider display, URL). */
        NO_CONNECTION,
    }

    /** Why a function is refused. */
    enum class RefusalReason { COMMAND, KEYCHAIN, LOCAL_ENCRYPTION, OTP }
}

/** Thrown while evaluating; carries the problem (no value). */
class VarlockException(val problem: VarlockProblem) : RuntimeException("${problem.code} ${problem.key.orEmpty()} ${problem.args}")

/**
 * One secret a value was made from: the reference it was read through and the secret's value (to mask it in consoles on its
 * own, also when it is only a part of the final value). `toString` leaves the value out.
 */
class SecretUse(val reference: SecretReference, val scope: SecretScope, val value: String) {
    override fun equals(other: Any?): Boolean = other is SecretUse && other.reference == reference && other.scope == scope && other.value == value

    override fun hashCode(): Int = (reference.hashCode() * 31 + scope.hashCode()) * 31 + value.hashCode()

    override fun toString(): String = "SecretUse(${reference.format()})"
}

/**
 * A value while evaluating: a string, a boolean or null (undefined), and the secrets it was made from. `toString` leaves the
 * value out.
 */
class VarlockValue(val value: Any?, val secrets: Set<SecretUse> = emptySet()) {
    /** The value as the process gets it (booleans as `true` / `false`), or null when undefined. */
    val text: String? get() = value?.toString()

    /** varlock's truthiness: undefined, false and "" are false. */
    val truthy: Boolean get() = value != null && value != false && value != ""

    override fun toString(): String = "VarlockValue(${if (value == null) "undefined" else "…"}, secrets=${secrets.size})"

    companion object {
        val UNDEFINED = VarlockValue(null)
    }
}
