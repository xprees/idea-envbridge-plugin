package cz.xprees.secrets.aws

import com.google.gson.JsonObject
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction

/**
 * The AWS accounts and roles IAM Identity Center assigns to the signed-in user (`ListAccounts`, `ListAccountRoles`, every page
 * followed by `nextToken`), and the choice of one account and one role among them.
 *
 * [page] is the HTTP layer: it GETs a portal path with the query parameters (the access token is added by the caller, in the
 * `x-amz-sso_bearer_token` header only) and returns the JSON object, or throws [SecretProviderException]. Tests fake it.
 */
internal class AwsSsoAssignments(private val page: (path: String, query: List<Pair<String, String>>) -> JsonObject) {

    /** An account as `ListAccounts` describes it. [name] and [email] may be empty. */
    data class Account(val id: String, val name: String, val email: String) {
        /** What the chooser shows: name, ID and email (never a secret). */
        val label: String get() = listOf(name, id, email).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The account and role chosen; [asked] when the user picked at least one of them (and the choice should be remembered). */
    data class Choice(val account: String, val role: String, val asked: Boolean)

    fun accounts(): List<Account> {
        val accounts = linkedMapOf<String, Account>()
        pages(ACCOUNTS_PATH, emptyList()) { o ->
            o.getAsJsonArray("accountList")?.forEach { a ->
                val account = a.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val id = account.str("accountId")?.takeIf { it.isNotBlank() } ?: return@forEach
                accounts.putIfAbsent(id, Account(id, account.str("accountName").orEmpty(), account.str("emailAddress").orEmpty()))
            }
        }
        return accounts.values.toList()
    }

    fun roles(account: String): List<String> {
        val roles = linkedSetOf<String>()
        pages(ROLES_PATH, listOf("account_id" to account)) { o ->
            o.getAsJsonArray("roleList")?.forEach { r -> r.takeIf { it.isJsonObject }?.asJsonObject?.str("roleName")?.takeIf { it.isNotBlank() }?.let(roles::add) }
        }
        return roles.toList()
    }

    /**
     * The account and role to use: [account] / [role] when set, else the only one there is, else the user's pick
     * ([interaction] permitting; without a way to ask, a CONFIGURATION error lists the choices). [current] values are
     * preselected in the lists. Cancelling throws CANCELLED.
     */
    fun choose(interaction: SignInInteraction, account: String?, role: String?, currentAccount: String? = null, currentRole: String? = null): Choice {
        var asked = false
        val accountId = account ?: run {
            val accounts = accounts()
            val picked = pick(
                accounts, interaction, "Choose the AWS account", Account::label, accounts.firstOrNull { it.id == currentAccount },
                none = { SecretProviderException(Kind.FORBIDDEN, "IAM Identity Center assigns no AWS account to you") },
                several = {
                    SecretProviderException(
                        Kind.CONFIGURATION,
                        "You can use several AWS accounts; set the account ID in the connection's settings: " +
                            accounts.joinToString { if (it.name.isEmpty()) it.id else "${it.id} (${it.name})" },
                    )
                },
            )
            asked = asked || picked.second
            picked.first.id
        }
        val roleName = role ?: run {
            val roles = roles(accountId)
            val picked = pick(
                roles, interaction, "Choose the role in account $accountId", { it }, roles.firstOrNull { it == currentRole },
                none = { SecretProviderException(Kind.FORBIDDEN, "IAM Identity Center assigns you no role in account $accountId") },
                several = {
                    SecretProviderException(Kind.CONFIGURATION, "You can use several roles in account $accountId; set the role name in the connection's settings: ${roles.joinToString()}")
                },
            )
            asked = asked || picked.second
            picked.first
        }
        return Choice(accountId, roleName, asked)
    }

    private fun pages(path: String, query: List<Pair<String, String>>, read: (JsonObject) -> Unit) {
        var next: String? = null
        var pages = 0
        do {
            val o = page(path, query + ("max_result" to PAGE_SIZE.toString()) + listOfNotNull(next?.let { "next_token" to it }))
            read(o)
            val token = o.str("nextToken")?.takeIf { it.isNotEmpty() }
            // A server repeating its token would loop forever
            next = token?.takeIf { it != next }
        } while (next != null && ++pages < MAX_PAGES)
    }

    companion object {
        const val ACCOUNTS_PATH = "/assignment/accounts"
        const val ROLES_PATH = "/assignment/roles"
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 50

        /** The only item, or the user's pick (second: whether the user was asked). */
        private fun <T : Any> pick(
            items: List<T>,
            interaction: SignInInteraction,
            title: String,
            text: (T) -> String,
            selected: T?,
            none: () -> SecretProviderException,
            several: () -> SecretProviderException,
        ): Pair<T, Boolean> = when {
            items.isEmpty() -> throw none()
            items.size == 1 -> items.single() to false
            !interaction.canChoose -> throw several()
            else -> (interaction.choose(title, items, text, selected) ?: throw SecretProviderException(Kind.CANCELLED, "Cancelled")) to true
        }

        private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
