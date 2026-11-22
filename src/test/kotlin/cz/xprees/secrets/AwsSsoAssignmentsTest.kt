package cz.xprees.secrets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import cz.xprees.secrets.aws.AwsSsoAssignments
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.provider.SignInInteraction
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Pagination of `ListAccounts` / `ListAccountRoles` and the account and role choice, with a fake portal (no HTTP). */
class AwsSsoAssignmentsTest {
    private data class Call(val path: String, val query: Map<String, String>)

    /** A fake portal: [accounts] (ID, name, email) and [roles] per account, [pageSize] per page, tokens `t<offset>`. */
    private class FakePortal(
        val accounts: List<Triple<String, String, String>>,
        val roles: Map<String, List<String>>,
        val pageSize: Int,
    ) {
        val calls = mutableListOf<Call>()

        fun page(path: String, query: List<Pair<String, String>>): JsonObject {
            val q = query.toMap()
            calls += Call(path, q)
            val from = q["next_token"]?.removePrefix("t")?.toInt() ?: 0
            val items: List<JsonObject> = when (path) {
                AwsSsoAssignments.ACCOUNTS_PATH -> accounts.map { (id, name, email) ->
                    JsonObject().apply { addProperty("accountId", id); addProperty("accountName", name); addProperty("emailAddress", email) }
                }
                AwsSsoAssignments.ROLES_PATH -> roles[q["account_id"]].orEmpty().map { JsonObject().apply { addProperty("roleName", it); addProperty("accountId", q["account_id"]) } }
                else -> error("unexpected path $path")
            }
            val to = minOf(items.size, from + pageSize)
            return JsonObject().apply {
                add(if (path == AwsSsoAssignments.ACCOUNTS_PATH) "accountList" else "roleList", JsonArray().apply { items.subList(from, to).forEach(::add) })
                if (to < items.size) addProperty("nextToken", "t$to")
            }
        }
    }

    /** Picks with [pick] (null = cancel) and records what it was asked. */
    private class Chooser(private val canAsk: Boolean = true, private val pick: (List<Any>) -> Any? = { it.last() }) : SignInInteraction {
        val asked = mutableListOf<Triple<String, List<String>, String?>>()

        override fun openBrowser(url: String) = Unit

        override fun isCancelled() = false

        override val canChoose: Boolean get() = canAsk

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> choose(title: String, items: List<T>, text: (T) -> String, selected: T?): T? {
            asked += Triple(title, items.map(text), selected?.let(text))
            return pick(items) as T?
        }
    }

    private fun failure(call: () -> Unit): SecretProviderException = try {
        call()
        throw AssertionError("expected a SecretProviderException")
    } catch (e: SecretProviderException) {
        e
    }

    private val many =(1..7).map { Triple("1000000000%02d".format(it), "Account $it", "team$it@example.test") }

    @Test
    fun `accounts are read page by page with nextToken`() {
        val portal = FakePortal(many, emptyMap(), pageSize = 3)
        val accounts = AwsSsoAssignments(portal::page).accounts()
        assertThat(accounts.map { it.id }).isEqualTo(many.map { it.first })
        assertThat(accounts.first()).isEqualTo(AwsSsoAssignments.Account("100000000001", "Account 1", "team1@example.test"))
        assertThat(accounts.first().label).isEqualTo("Account 1 · 100000000001 · team1@example.test")
        assertThat(portal.calls.map { it.query["next_token"] }).containsExactly(null, "t3", "t6")
        assertThat(portal.calls).allMatch { it.path == AwsSsoAssignments.ACCOUNTS_PATH && it.query["max_result"] == "100" }
    }

    @Test
    fun `roles are read page by page for the account, without duplicates`() {
        val portal = FakePortal(emptyList(), mapOf("111111111111" to listOf("Admin", "Developer", "ReadOnly", "Developer", "Billing")), pageSize = 2)
        assertThat(AwsSsoAssignments(portal::page).roles("111111111111")).containsExactly("Admin", "Developer", "ReadOnly", "Billing")
        assertThat(portal.calls).hasSize(3).allMatch { it.path == AwsSsoAssignments.ROLES_PATH && it.query["account_id"] == "111111111111" }
    }

    @Test
    fun `a server repeating its nextToken doesn't loop, and pages are capped`() {
        val repeating = mutableListOf<String?>()
        AwsSsoAssignments { _, query ->
            repeating += query.toMap()["next_token"]
            JsonObject().apply { add("accountList", JsonArray()); addProperty("nextToken", "same") }
        }.accounts()
        assertThat(repeating).containsExactly(null, "same")

        var calls = 0
        AwsSsoAssignments { _, _ -> JsonObject().apply { add("roleList", JsonArray()); addProperty("nextToken", "t${++calls}") } }.roles("1")
        assertThat(calls).isEqualTo(AwsSsoAssignments.MAX_PAGES)
    }

    @Test
    fun `the only account and role are taken without asking`() {
        val portal = FakePortal(many.take(1), mapOf("100000000001" to listOf("Developer")), pageSize = 10)
        val chooser = Chooser()
        val choice = AwsSsoAssignments(portal::page).choose(chooser, null, null)
        assertThat(choice).isEqualTo(AwsSsoAssignments.Choice("100000000001", "Developer", asked = false))
        assertThat(chooser.asked).isEmpty()
    }

    @Test
    fun `several accounts and roles are offered, from every page, with the current ones preselected`() {
        val portal = FakePortal(many, mapOf("100000000007" to listOf("Admin", "Developer")), pageSize = 2)
        val chooser = Chooser()
        val choice = AwsSsoAssignments(portal::page).choose(chooser, null, null, currentAccount = "100000000003", currentRole = "Admin")
        assertThat(choice).isEqualTo(AwsSsoAssignments.Choice("100000000007", "Developer", asked = true))
        val (accountTitle, accountRows, preselected) = chooser.asked[0]
        assertThat(accountTitle).contains("account")
        assertThat(accountRows).hasSize(7).contains("Account 7 · 100000000007 · team7@example.test")
        assertThat(preselected).isEqualTo("Account 3 · 100000000003 · team3@example.test")
        assertThat(chooser.asked[1]).isEqualTo(Triple("Choose the role in account 100000000007", listOf("Admin", "Developer"), "Admin"))
    }

    @Test
    fun `a set account or role isn't asked for`() {
        val portal = FakePortal(many, mapOf("100000000002" to listOf("Admin", "Developer")), pageSize = 3)
        val chooser = Chooser()
        assertThat(AwsSsoAssignments(portal::page).choose(chooser, "100000000002", null)).isEqualTo(AwsSsoAssignments.Choice("100000000002", "Developer", asked = true))
        assertThat(portal.calls).noneMatch { it.path == AwsSsoAssignments.ACCOUNTS_PATH }

        portal.calls.clear()
        assertThat(AwsSsoAssignments(portal::page).choose(chooser, "100000000002", "Admin")).isEqualTo(AwsSsoAssignments.Choice("100000000002", "Admin", asked = false))
        assertThat(portal.calls).isEmpty()
    }

    @Test
    fun `cancelling the choice cancels`() {
        val portal = FakePortal(many, emptyMap(), pageSize = 10)
        val e = failure({ AwsSsoAssignments(portal::page).choose(Chooser { null }, null, null) })
        assertThat(e.kind).isEqualTo(Kind.CANCELLED)
    }

    @Test
    fun `without a way to ask, the choices are listed in a configuration error`() {
        val portal = FakePortal(many.take(2), mapOf("100000000001" to listOf("Admin", "Developer")), pageSize = 1)
        val accounts = failure({ AwsSsoAssignments(portal::page).choose(Chooser(canAsk = false), null, null) })
        assertThat(accounts.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(accounts.message).contains("100000000001 (Account 1)").contains("100000000002 (Account 2)")

        val roles = failure({ AwsSsoAssignments(portal::page).choose(Chooser(canAsk = false), "100000000001", null) })
        assertThat(roles.kind).isEqualTo(Kind.CONFIGURATION)
        assertThat(roles.message).contains("Admin, Developer")
    }

    @Test
    fun `no account or no role is refused`() {
        val none = failure({ AwsSsoAssignments(FakePortal(emptyList(), emptyMap(), 5)::page).choose(Chooser(), null, null) })
        assertThat(none.kind).isEqualTo(Kind.FORBIDDEN)
        val noRole = failure({ AwsSsoAssignments(FakePortal(many.take(1), emptyMap(), 5)::page).choose(Chooser(), null, null) })
        assertThat(noRole.kind).isEqualTo(Kind.FORBIDDEN)
        assertThat(noRole.message).contains("100000000001")
    }

    @Test
    fun `portal errors propagate unchanged`() {
        val e = failure({
            AwsSsoAssignments { _, _ -> throw SecretProviderException(Kind.AUTH_REQUIRED, "sign in again") }.choose(Chooser(), null, null)
        })
        assertThat(e.kind).isEqualTo(Kind.AUTH_REQUIRED)
    }
}
