package cz.xprees.secrets.aws

import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.SetupLink
import cz.xprees.secrets.core.reference.ReferenceGrammar
import java.net.URI
import java.net.URLEncoder
import javax.swing.Icon

/**
 * AWS Secrets Manager (unofficial integration). A connection is an AWS account in one region. A secret named `<prefix>/<env>`
 * (the default name layout, see [AwsNameLayout]) holding a JSON object is the environment `<env>` of the project `<prefix>`, and
 * its top-level fields are the keys. No folders. Reading and writing; requests are signed with [AwsSigV4] (no AWS SDK).
 */
class AwsSecretsManagerProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "AWS Secrets Manager"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    override val referenceScheme: String = "aws-sm"
    override val referenceGrammar: ReferenceGrammar get() = AwsGrammar

    /** The regional endpoints of the common regions; any other region or a custom endpoint (LocalStack, VPC) can be typed. */
    override val instancePresets: List<Pair<String, String>> = REGIONS.map { (code, place) -> "$code ($place)" to endpoint(code) }

    override val authMethods: List<AuthMethod> = AwsAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String = "dev"

    override fun createClient(connection: SecretConnection): SecretsClient = AwsClient(connection)

    /**
     * The secret in the AWS console of its region (`…/secretsmanager/secret?name=…&region=…`), or the region's secret list.
     * None for endpoints outside `amazonaws.com` (LocalStack has no console).
     */
    override fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String?, key: String?): String? {
        val host = runCatching { URI(connection.baseUrl).host }.getOrNull()?.lowercase() ?: return null
        if (!host.endsWith(".amazonaws.com")) return null
        val region = runCatching { region(connection) }.getOrNull() ?: return null
        val console = "https://$region.console.aws.amazon.com/secretsmanager"
        if (scope == null) return "$console/listsecrets?region=$region"
        val secretName = runCatching { AwsNameLayout.of(connection).join(scope.projectId, scope.environment) }.getOrNull() ?: return "$console/listsecrets?region=$region"
        val name = URLEncoder.encode(secretName, Charsets.UTF_8).replace("+", "%20")
        return "$console/secret?name=$name&region=$region"
    }

    /** Drops the values read ahead (and, for all connections, the listings); see [AwsMemory]. */
    override fun forget(connectionId: String?) = AwsMemory.forget(connectionId)

    /** How many secrets the last listing left out because their names don't follow the connection's name layout. */
    override fun listingNote(connection: SecretConnection): String? {
        val hidden = AwsMemory.listing(connection.id)?.hidden ?: return null
        if (hidden == 0) return null
        val layout = runCatching { AwsNameLayout.of(connection).template }.getOrDefault("?")
        return SecretsBundle.message("aws.listing.hidden", hidden, layout)
    }

    companion object {
        const val ID = "aws-secrets-manager"

        /** Regions offered as presets (code to place), the most used first. */
        val REGIONS = listOf(
            "us-east-1" to "N. Virginia",
            "us-east-2" to "Ohio",
            "us-west-1" to "N. California",
            "us-west-2" to "Oregon",
            "eu-west-1" to "Ireland",
            "eu-west-2" to "London",
            "eu-west-3" to "Paris",
            "eu-central-1" to "Frankfurt",
            "eu-north-1" to "Stockholm",
            "ap-south-1" to "Mumbai",
            "ap-northeast-1" to "Tokyo",
            "ap-southeast-1" to "Singapore",
            "ap-southeast-2" to "Sydney",
            "ca-central-1" to "Canada",
            "sa-east-1" to "São Paulo",
        )

        /** The regional Secrets Manager endpoint of [region]. */
        fun endpoint(region: String) = "https://secretsmanager.$region.amazonaws.com"

        private val REGIONAL_HOST = Regex("""^secretsmanager(?:-fips)?\.([a-z0-9-]+)\.amazonaws\.com(?:\.cn)?$""")
        private val VPC_HOST = Regex("""\.secretsmanager\.([a-z0-9-]+)\.vpce\.amazonaws\.com(?:\.cn)?$""")
        private val REGION = Regex("[a-z]{2}(?:-[a-z]+)+-\\d+")

        /** Whether [region] looks like an AWS region code (`eu-west-1`). */
        fun isRegion(region: String): Boolean = REGION.matches(region)

        /** The connection's region: its Region option, else read from the endpoint's host, else CONFIGURATION. */
        fun region(connection: SecretConnection): String {
            connection.option(AwsAuthMethods.OPTION_REGION)?.trim()?.lowercase()?.let { region ->
                if (!REGION.matches(region)) {
                    throw SecretProviderException(Kind.CONFIGURATION, "'$region' isn't an AWS region (for example eu-west-1); check the connection '${connection.name}'")
                }
                return region
            }
            val host = runCatching { URI(connection.baseUrl).host }.getOrNull()?.lowercase().orEmpty()
            return (REGIONAL_HOST.find(host) ?: VPC_HOST.find(host))?.groupValues?.get(1) ?: throw SecretProviderException(
                Kind.CONFIGURATION,
                "The region of '${connection.name}' can't be read from its endpoint ${connection.baseUrl}; set the Region in its settings",
            )
        }
    }
}

/** The sign-in methods of AWS connections and their option keys. */
object AwsAuthMethods {
    const val ACCESS_KEY = "access-key"
    const val PROFILE = "profile"
    const val ENVIRONMENT = "environment"
    const val SSO = "sso"

    const val OPTION_REGION = "region"
    const val OPTION_NAME_PREFIX = "namePrefix"
    const val OPTION_NAME_LAYOUT = "nameLayout"
    const val OPTION_SSO_START_URL = "ssoStartUrl"
    const val OPTION_SSO_REGION = "ssoRegion"
    const val OPTION_SSO_ACCOUNT_ID = "ssoAccountId"
    const val OPTION_SSO_ROLE_NAME = "ssoRoleName"
    const val OPTION_PROFILE = "profile"
    const val OPTION_CLI_PATH = "awsCliPath"
    const val OPTION_ACCESS_KEY_ID = "accessKeyId"
    const val OPTION_SECRET_ACCESS_KEY = "secretAccessKey"
    const val OPTION_SESSION_TOKEN = "sessionToken"

    /** Every secret option (all of the access key method's). */
    val SECRET_KEYS = listOf(OPTION_ACCESS_KEY_ID, OPTION_SECRET_ACCESS_KEY, OPTION_SESSION_TOKEN)

    private val COMMON = listOf(
        AuthOption(OPTION_REGION, SecretsBundle.message("auth.aws.option.region.label"), required = false, hint = SecretsBundle.message("auth.aws.option.region.hint")),
        AuthOption(OPTION_NAME_PREFIX, SecretsBundle.message("auth.aws.option.namePrefix.label"), required = false, hint = SecretsBundle.message("auth.aws.option.namePrefix.hint")),
        AuthOption(
            OPTION_NAME_LAYOUT, SecretsBundle.message("auth.aws.option.nameLayout.label"), required = false,
            hint = SecretsBundle.message("auth.aws.option.nameLayout.hint"),
            validate = { value -> AwsNameLayout.problem(value)?.let { SecretsBundle.message("auth.aws.option.nameLayout.invalid", it) } },
        ),
    )

    val ALL = listOf(
        AuthMethod(
            id = ACCESS_KEY,
            displayName = SecretsBundle.message("auth.aws.accessKey.name"),
            description = SecretsBundle.message("auth.aws.accessKey.description"),
            options = COMMON,
            secretOptions = listOf(
                AuthOption(OPTION_ACCESS_KEY_ID, SecretsBundle.message("auth.aws.option.accessKeyId.label"), hint = "AKIA…"),
                AuthOption(OPTION_SECRET_ACCESS_KEY, SecretsBundle.message("auth.aws.option.secretAccessKey.label")),
                AuthOption(OPTION_SESSION_TOKEN, SecretsBundle.message("auth.aws.option.sessionToken.label"), required = false, hint = SecretsBundle.message("auth.aws.option.sessionToken.hint")),
            ),
            setupLinks = listOf(
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.console"), "https://console.aws.amazon.com/iam/home#/security_credentials"),
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.accessKeys"), "https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys.html"),
            ),
        ),
        AuthMethod(
            id = PROFILE,
            displayName = SecretsBundle.message("auth.aws.profile.name"),
            description = SecretsBundle.message("auth.aws.profile.description"),
            options = listOf(
                AuthOption(OPTION_PROFILE, SecretsBundle.message("auth.aws.option.profile.label"), required = false, hint = AwsAuth.DEFAULT_PROFILE),
                AuthOption(OPTION_CLI_PATH, SecretsBundle.message("auth.aws.option.cliPath.label"), required = false, hint = SecretsBundle.message("auth.aws.option.cliPath.hint")),
            ) + COMMON,
            setupLinks = listOf(
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.profiles"), "https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-files.html"),
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.sso"), "https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html"),
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.console"), "https://console.aws.amazon.com/iam/home#/security_credentials"),
            ),
        ),
        AuthMethod(
            id = SSO,
            displayName = SecretsBundle.message("auth.aws.sso.name"),
            description = SecretsBundle.message("auth.aws.sso.description"),
            options = listOf(
                AuthOption(
                    OPTION_SSO_START_URL, SecretsBundle.message("auth.aws.option.ssoStartUrl.label"), hint = "https://my-company.awsapps.com/start",
                    validate = { value -> SecretsBundle.message("auth.aws.option.ssoStartUrl.invalid").takeIf { !AwsSso.isStartUrl(value) } },
                ),
                AuthOption(
                    OPTION_SSO_REGION, SecretsBundle.message("auth.aws.option.ssoRegion.label"), hint = SecretsBundle.message("auth.aws.option.ssoRegion.hint"),
                    validate = { value -> SecretsBundle.message("auth.aws.option.ssoRegion.invalid").takeIf { !AwsSecretsManagerProvider.isRegion(value.trim()) } },
                ),
                AuthOption(
                    OPTION_SSO_ACCOUNT_ID, SecretsBundle.message("auth.aws.option.ssoAccountId.label"), required = false,
                    hint = SecretsBundle.message("auth.aws.option.ssoAccountId.hint"),
                    validate = { value -> SecretsBundle.message("auth.aws.option.ssoAccountId.invalid").takeIf { !value.trim().matches(Regex("\\d{12}")) } },
                ),
                AuthOption(
                    OPTION_SSO_ROLE_NAME, SecretsBundle.message("auth.aws.option.ssoRoleName.label"), required = false,
                    hint = SecretsBundle.message("auth.aws.option.ssoRoleName.hint"),
                ),
            ) + COMMON,
            setupLinks = listOf(
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.identityCenterConsole"), "https://console.aws.amazon.com/singlesignon/home"),
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.identityCenter"), "https://docs.aws.amazon.com/singlesignon/latest/userguide/what-is.html"),
            ),
        ),
        AuthMethod(
            id = ENVIRONMENT,
            displayName = SecretsBundle.message("auth.aws.environment.name"),
            description = SecretsBundle.message("auth.aws.environment.description"),
            options = COMMON,
            setupLinks = listOf(
                SetupLink.fixed(SecretsBundle.message("auth.link.aws.environment"), "https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-envvars.html"),
            ),
        ),
    )
}
