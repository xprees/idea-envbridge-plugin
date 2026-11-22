package cz.xprees.secrets.aws

import cz.xprees.secrets.core.auth.CredentialVault
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Credentials of one AWS connection.
 *
 * - **Access key**: access key ID, secret access key and optional session token, all kept in the IDE's password storage as
 *   secret options. Sign-out forgets them (the key is the user's own; it isn't deactivated).
 * - **AWS profile**: read from `~/.aws/credentials` / `~/.aws/config` at use time. Static keys are used as they are; SSO,
 *   assumed-role and `credential_process` profiles go through the AWS CLI ([AwsCli]), whose temporary credentials are kept in
 *   memory only, until shortly before they expire.
 * - **Environment variables**: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` of the IDE process.
 * - **IAM Identity Center**: signed in inside the IDE ([AwsSso]); tokens in the password storage, role credentials in memory.
 *
 * Profile and environment connections keep only a "signed in" marker in the session slot, so background features don't run
 * the AWS CLI for a connection nobody signed in to.
 */
class AwsAuth(private val connection: SecretConnection, private val check: (AwsKeys) -> Unit) {

    private val method: String get() = connection.authMethodId

    private val profileName: String get() = connection.option(AwsAuthMethods.OPTION_PROFILE)?.trim() ?: DEFAULT_PROFILE

    private val sso by lazy { AwsSso(connection, check) }

    /** Whether credentials are stored (access key) or the user signed in (profile, environment). */
    fun hasCredentials(): Boolean = when (method) {
        AwsAuthMethods.ACCESS_KEY -> secret(AwsAuthMethods.OPTION_ACCESS_KEY_ID) != null && secret(AwsAuthMethods.OPTION_SECRET_ACCESS_KEY) != null
        AwsAuthMethods.SSO -> sso.hasSession()
        else -> CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION) != null
    }

    /** The credentials to sign with, or AUTH_REQUIRED / CONFIGURATION. */
    fun keys(): AwsKeys = when (method) {
        AwsAuthMethods.ACCESS_KEY -> storedKeys()
        AwsAuthMethods.PROFILE -> {
            if (CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION) == null) throw notSignedIn()
            profileKeys()
        }
        AwsAuthMethods.ENVIRONMENT -> {
            if (CredentialVault.get(connection.id, CredentialVault.SLOT_SESSION) == null) throw notSignedIn()
            environmentKeys()
        }
        AwsAuthMethods.SSO -> sso.keys()
        else -> throw SecretProviderException(Kind.CONFIGURATION, "Unknown sign-in method '$method'")
    }

    /**
     * Runs [call] with the credentials. Temporary credentials from the AWS CLI that AWS no longer accepts are dropped and
     * fetched again once.
     */
    fun <T> withKeys(call: (AwsKeys) -> T): T {
        val keys = keys()
        try {
            return call(keys)
        } catch (e: SecretProviderException) {
            if (e.kind != Kind.AUTH_REQUIRED) throw e
            val dropped = when (method) {
                AwsAuthMethods.PROFILE -> cache.remove(cacheKey(), CachedKeys(keys))
                AwsAuthMethods.SSO -> sso.drop(keys)
                else -> false
            }
            if (!dropped) throw e
            return call(keys())
        }
    }

    /** Checks the credentials with one request and returns a short account description (never a secret). */
    fun signIn(interaction: SignInInteraction): String {
        if (method == AwsAuthMethods.SSO) return sso.signIn(interaction)
        interaction.status("Checking the AWS credentials…")
        val keys = when (method) {
            AwsAuthMethods.ACCESS_KEY -> storedKeys()
            AwsAuthMethods.PROFILE -> profileKeys(interaction::isCancelled)
            AwsAuthMethods.ENVIRONMENT -> environmentKeys()
            else -> throw SecretProviderException(Kind.CONFIGURATION, "Unknown sign-in method '$method'")
        }
        check(keys)
        val account = describe(keys)
        if (method != AwsAuthMethods.ACCESS_KEY) CredentialVault.set(connection.id, CredentialVault.SLOT_SESSION, account)
        return account
    }

    /** Forgets the stored keys (access key), the signed-in marker and temporary credentials in memory. */
    fun signOut() {
        if (method == AwsAuthMethods.SSO) return sso.signOut()
        CredentialVault.remove(connection.id, CredentialVault.SLOT_SESSION)
        cache.remove(cacheKey())
        if (method == AwsAuthMethods.ACCESS_KEY) {
            // The key is the user's own: forgotten here, never deactivated in IAM
            AwsAuthMethods.SECRET_KEYS.forEach { CredentialVault.remove(connection.id, CredentialVault.secretOptionSlot(it)) }
        }
    }

    private fun describe(keys: AwsKeys): String {
        val via = when (method) {
            AwsAuthMethods.PROFILE -> "profile $profileName, "
            AwsAuthMethods.ENVIRONMENT -> "environment, "
            else -> ""
        }
        return "${keys.shortId} ($via${AwsSecretsManagerProvider.region(connection)})"
    }

    private fun storedKeys(): AwsKeys {
        val id = secret(AwsAuthMethods.OPTION_ACCESS_KEY_ID)
        val secretKey = secret(AwsAuthMethods.OPTION_SECRET_ACCESS_KEY)
        if (id == null || secretKey == null) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "Connection '${connection.name}' has no access key ID and secret access key; edit it in Settings › Tools › EnvBridge",
            )
        }
        return AwsKeys(id, secretKey, secret(AwsAuthMethods.OPTION_SESSION_TOKEN))
    }

    private fun profileKeys(cancelled: () -> Boolean = { false }): AwsKeys {
        val name = profileName
        val profile = AwsProfiles.find(name) ?: throw SecretProviderException(
            Kind.CONFIGURATION,
            "No AWS profile '$name' in ${AwsProfiles.credentialsFile()} or ${AwsProfiles.configFile()}",
        )
        if (!profile.needsCli) {
            return profile.staticKeys() ?: throw SecretProviderException(
                Kind.CONFIGURATION,
                "AWS profile '$name' has no access keys, SSO, role or credential_process settings EnvBridge can use",
            )
        }
        cache[cacheKey()]?.takeIf { it.valid() }?.let { return it.keys }
        val keys = AwsCli(connection.option(AwsAuthMethods.OPTION_CLI_PATH)).exportCredentials(name, cancelled)
        cache[cacheKey()] = CachedKeys(keys)
        return keys
    }

    private fun environmentKeys(): AwsKeys {
        val env = AwsProfiles.environment()
        val id = env["AWS_ACCESS_KEY_ID"]?.takeIf { it.isNotBlank() }
        val secretKey = env["AWS_SECRET_ACCESS_KEY"]?.takeIf { it.isNotBlank() }
        if (id == null || secretKey == null) {
            throw SecretProviderException(
                Kind.AUTH_REQUIRED,
                "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY are not set in the IDE's environment (start the IDE from a shell that has them)",
            )
        }
        return AwsKeys(id, secretKey, env["AWS_SESSION_TOKEN"]?.takeIf { it.isNotBlank() })
    }

    private fun secret(key: String): String? =
        CredentialVault.get(connection.id, CredentialVault.secretOptionSlot(key))?.trim()?.takeIf { it.isNotEmpty() }

    private fun cacheKey() = "${connection.id}/$profileName"

    private fun notSignedIn() = SecretProviderException(Kind.AUTH_REQUIRED, "Not signed in to '${connection.name}'")

    /** Temporary credentials in memory; compared by identity of the keys object. */
    private class CachedKeys(val keys: AwsKeys) {
        private val until: Instant = keys.expiresAt?.minus(EXPIRY_MARGIN) ?: Instant.now().plus(NO_EXPIRY_TTL)

        fun valid() = Instant.now().isBefore(until)

        override fun equals(other: Any?) = other is CachedKeys && other.keys === keys

        override fun hashCode() = System.identityHashCode(keys)
    }

    companion object {
        const val DEFAULT_PROFILE = "default"
        private val EXPIRY_MARGIN: Duration = Duration.ofMinutes(5)
        private val NO_EXPIRY_TTL: Duration = Duration.ofMinutes(15)

        /** Temporary credentials from the AWS CLI, per connection and profile. Memory only. */
        private val cache = ConcurrentHashMap<String, CachedKeys>()
    }
}
