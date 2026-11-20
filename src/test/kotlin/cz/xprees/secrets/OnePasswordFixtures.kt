package cz.xprees.secrets

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test data shaped like 1Password's JSON (`op … --format json` and the Connect API): vaults with items whose fields may be in
 * sections. The CLI repeats a field's section label on the field; Connect gives only the section's ID.
 */
object OnePasswordFixtures {
    /** A 1Password-style ID: 26 lowercase letters and digits. */
    fun id(prefix: String): String = (prefix + "0".repeat(26)).take(25) + "1"

    val DEV = id("dev")
    val SHARED = id("shared")
    val FORBIDDEN = id("forbidden")
    val BIG = id("big")
    val DATABASE = id("database")
    val STRIPE = id("stripe")
    val DUP1 = (id("dup").dropLast(1)) + "1"
    val DUP2 = (id("dup").dropLast(1)) + "2"
    val INFRA = id("infra")

    data class Field(val id: String, val label: String, val value: String?, val type: String = "CONCEALED", val purpose: String = "", val section: String? = null)

    data class Section(val id: String, val label: String?)

    data class Item(val id: String, val title: String, val category: String, val sections: List<Section>, val fields: List<Field>)

    data class Vault(val id: String, val name: String, val items: List<Item>)

    val vaults: List<Vault> = listOf(
        Vault(
            DEV, "Dev",
            listOf(
                Item(
                    DATABASE, "Database", "DATABASE",
                    sections = listOf(Section("add more", null), Section("s1", "Extra")),
                    fields = listOf(
                        Field("notesPlain", "notesPlain", null, type = "STRING", purpose = "NOTES"),
                        Field("username", "username", "app", type = "STRING", purpose = "USERNAME"),
                        Field("password", "password", "db-pass", purpose = "PASSWORD"),
                        Field("k1", "api key", "extra-key", section = "s1"),
                        Field("p1", "port", "5432", type = "STRING", section = "add more"),
                    ),
                ),
                Item(
                    STRIPE, "Stripe", "API_CREDENTIAL",
                    sections = listOf(Section("s2", "Webhooks")),
                    fields = listOf(
                        Field("credential", "credential", "sk_test_123"),
                        Field("w1", "secret", "whsec_1", section = "s2"),
                    ),
                ),
                Item(DUP1, "Dup", "PASSWORD", emptyList(), listOf(Field("password", "password", "dup-1", purpose = "PASSWORD"))),
                Item(DUP2, "Dup", "PASSWORD", emptyList(), listOf(Field("password", "password", "dup-2", purpose = "PASSWORD"))),
            ),
        ),
        Vault(SHARED, "Shared", listOf(Item(INFRA, "Infra", "LOGIN", emptyList(), listOf(Field("password", "password", "shared-pass", purpose = "PASSWORD"))))),
        Vault(BIG, "Big", (1..101).map { Item(id("big${it}x"), "Item $it", "PASSWORD", emptyList(), emptyList()) }),
    )

    fun vaultJson(v: Vault, cli: Boolean) = JsonObject().apply {
        addProperty("id", v.id)
        addProperty("name", v.name)
        if (cli) addProperty("content_version", 7) else {
            addProperty("contentVersion", 7)
            addProperty("type", "USER_CREATED")
        }
    }

    fun summaryJson(v: Vault, item: Item, cli: Boolean) = JsonObject().apply {
        addProperty("id", item.id)
        addProperty("title", item.title)
        addProperty("version", 1)
        add("vault", JsonObject().apply { addProperty("id", v.id); if (cli) addProperty("name", v.name) })
        addProperty("category", item.category)
        addProperty(if (cli) "updated_at" else "updatedAt", "2026-11-01T10:00:00Z")
    }

    fun itemJson(v: Vault, item: Item, cli: Boolean) = summaryJson(v, item, cli).apply {
        add("sections", JsonArray().apply {
            item.sections.forEach { s -> add(JsonObject().apply { addProperty("id", s.id); s.label?.let { addProperty("label", it) } }) }
        })
        add("fields", JsonArray().apply {
            item.fields.forEach { f ->
                add(JsonObject().apply {
                    addProperty("id", f.id)
                    f.section?.let { sid ->
                        add("section", JsonObject().apply {
                            addProperty("id", sid)
                            if (cli) item.sections.first { it.id == sid }.label?.let { addProperty("label", it) }
                        })
                    }
                    addProperty("type", f.type)
                    if (f.purpose.isNotEmpty()) addProperty("purpose", f.purpose)
                    addProperty("label", f.label)
                    f.value?.let { addProperty("value", it) }
                    if (cli) addProperty("reference", "op://${v.name}/${item.title}/${f.label}")
                })
            }
        })
    }
}

/**
 * A fake `op` executable (a POSIX shell script) answering from fixture files: `vault list`, `item list --vault`, `item get
 * <item> --vault`, `whoami`. It logs its command line and whether `OP_SERVICE_ACCOUNT_TOKEN` was set, can require a token,
 * and can act signed out.
 */
class FakeOp : AutoCloseable {
    val dir: Path = Files.createTempDirectory("fake-op")
    val executable: Path = dir.resolve("op")
    private val gson = Gson()

    init {
        val data = dir.resolve("data")
        Files.createDirectories(data.resolve("items"))
        write(data.resolve("vaults.json"), JsonArray().apply { OnePasswordFixtures.vaults.forEach { add(OnePasswordFixtures.vaultJson(it, cli = true)) } })
        whoami("""{"url":"https://my.1password.com","email":"alice@example.com","user_uuid":"U1","account_uuid":"A1","user_type":"HUMAN"}""")
        for (vault in OnePasswordFixtures.vaults) {
            val list = JsonArray().apply { vault.items.forEach { add(OnePasswordFixtures.summaryJson(vault, it, cli = true)) } }
            for (vaultName in listOf(vault.id, vault.name)) {
                write(data.resolve("items").resolve("$vaultName.json"), list)
                val itemDir = data.resolve("item").resolve(vaultName)
                Files.createDirectories(itemDir)
                val titles = vault.items.groupingBy { it.title }.eachCount()
                for (item in vault.items) {
                    val json = OnePasswordFixtures.itemJson(vault, item, cli = true)
                    write(itemDir.resolve("${item.id}.json"), json)
                    if (titles[item.title] == 1) write(itemDir.resolve("${item.title}.json"), json)
                    else Files.writeString(
                        itemDir.resolve("${item.title}.err"),
                        "[ERROR] 2026/11/20 10:00:00 More than one item matches \"${item.title}\". Try again and specify the item by its ID:\n",
                    )
                }
            }
        }
        Files.writeString(executable, SCRIPT)
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    }

    fun whoami(json: String) = Files.writeString(dir.resolve("data").resolve("whoami.json"), json)

    /** The service account token `op` accepts; null accepts any (or none). */
    var requiredToken: String? = null
        set(value) {
            field = value
            val file = dir.resolve("token")
            if (value == null) Files.deleteIfExists(file) else Files.writeString(file, value)
        }

    var signedOut: Boolean = false
        set(value) {
            field = value
            val file = dir.resolve("signed-out")
            if (value) Files.writeString(file, "") else Files.deleteIfExists(file)
        }

    /** Command lines `op` was run with, one per call. */
    fun calls(): List<String> = lines("argv.log")

    /** `OP_SERVICE_ACCOUNT_TOKEN` per call (`<unset>` when absent). */
    fun tokens(): List<String> = lines("env.log")

    private fun lines(name: String): List<String> = dir.resolve(name).let { if (Files.exists(it)) Files.readAllLines(it) else emptyList() }

    private fun write(path: Path, json: Any) = Files.writeString(path, gson.toJson(json))

    override fun close() {
        dir.toFile().deleteRecursively()
    }

    companion object {
        private val SCRIPT = """
            |#!/bin/sh
            |d="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            |printf '%s\n' "${'$'}*" >> "${'$'}d/argv.log"
            |printf '%s\n' "${'$'}{OP_SERVICE_ACCOUNT_TOKEN-<unset>}" >> "${'$'}d/env.log"
            |if [ -f "${'$'}d/signed-out" ]; then
            |  echo "[ERROR] 2026/11/20 10:00:00 You are not currently signed in. Please run \`op signin --help\` for instructions" >&2
            |  exit 1
            |fi
            |if [ -f "${'$'}d/token" ] && [ "${'$'}{OP_SERVICE_ACCOUNT_TOKEN-}" != "${'$'}(cat "${'$'}d/token")" ]; then
            |  echo "[ERROR] 2026/11/20 10:00:00 invalid service account token, please make sure you provide a valid service account token" >&2
            |  exit 1
            |fi
            |w1=""; w2=""; w3=""; vault=""
            |while [ ${'$'}# -gt 0 ]; do
            |  case "${'$'}1" in
            |    --format|--account) shift ;;
            |    --vault) vault="${'$'}2"; shift ;;
            |    --*) ;;
            |    *) if [ -z "${'$'}w1" ]; then w1="${'$'}1"; elif [ -z "${'$'}w2" ]; then w2="${'$'}1"; else w3="${'$'}1"; fi ;;
            |  esac
            |  shift
            |done
            |case "${'$'}w1 ${'$'}w2" in
            |  "vault list") f="${'$'}d/data/vaults.json" ;;
            |  "whoami ") f="${'$'}d/data/whoami.json" ;;
            |  "item list")
            |    f="${'$'}d/data/items/${'$'}vault.json"
            |    if [ ! -f "${'$'}f" ]; then echo "[ERROR] 2026/11/20 10:00:00 \"${'$'}vault\" isn't a vault in this account. Specify the vault with its ID or name." >&2; exit 1; fi ;;
            |  "item get")
            |    f="${'$'}d/data/item/${'$'}vault/${'$'}w3.json"
            |    if [ -f "${'$'}d/data/item/${'$'}vault/${'$'}w3.err" ]; then cat "${'$'}d/data/item/${'$'}vault/${'$'}w3.err" >&2; exit 1; fi
            |    if [ ! -f "${'$'}f" ]; then echo "[ERROR] 2026/11/20 10:00:00 \"${'$'}w3\" isn't an item in the \"${'$'}vault\" vault. Specify the item with its UUID, name, or domain." >&2; exit 1; fi ;;
            |  *) echo "[ERROR] 2026/11/20 10:00:00 unknown command \"${'$'}w1\" for \"op\"" >&2; exit 1 ;;
            |esac
            |cat "${'$'}f"
            |""".trimMargin()
    }
}

/** A fake 1Password Connect server: vaults, items and item details with a bearer token; [OnePasswordFixtures.FORBIDDEN] answers 403. */
class MockOnePasswordConnect : AutoCloseable {
    data class Request(val method: String, val path: String, val authorization: String?)

    val requests = CopyOnWriteArrayList<Request>()

    @Volatile
    var token = "connect-token"

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        val auth = exchange.requestHeaders.getFirst("Authorization")
        requests += Request(exchange.requestMethod, path, auth)
        if (auth != "Bearer $token") return error(exchange, 401, "Invalid token signature")
        val parts = path.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        if (parts.getOrNull(0) != "v1" || parts.getOrNull(1) != "vaults") return error(exchange, 404, "Not found")
        val all = OnePasswordFixtures.vaults + OnePasswordFixtures.Vault(OnePasswordFixtures.FORBIDDEN, "Forbidden", emptyList())
        if (parts.size == 2) return json(exchange, JsonArray().apply { all.forEach { add(OnePasswordFixtures.vaultJson(it, cli = false)) } })
        val vault = all.firstOrNull { it.id == parts[2] } ?: return error(exchange, 404, "Vault not found")
        if (vault.id == OnePasswordFixtures.FORBIDDEN) return error(exchange, 403, "Authorization: token is not authorized to access vault")
        if (parts.getOrNull(3) != "items") return error(exchange, 404, "Not found")
        if (parts.size == 4) return json(exchange, JsonArray().apply { vault.items.forEach { add(OnePasswordFixtures.summaryJson(vault, it, cli = false)) } })
        val item = vault.items.firstOrNull { it.id == parts[4] } ?: return error(exchange, 404, "Item not found")
        json(exchange, OnePasswordFixtures.itemJson(vault, item, cli = false))
    }

    private fun json(exchange: HttpExchange, body: Any) = send(exchange, 200, Gson().toJson(body))

    private fun error(exchange: HttpExchange, status: Int, message: String) =
        send(exchange, status, Gson().toJson(JsonObject().apply { addProperty("status", status); addProperty("message", message) }))

    private fun send(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}
