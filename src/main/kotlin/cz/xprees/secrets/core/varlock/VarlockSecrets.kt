package cz.xprees.secrets.core.varlock

import cz.xprees.secrets.core.envspec.EnvSpecDecorator
import cz.xprees.secrets.core.envspec.EnvSpecFile
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecValue
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.ReferenceSyntax
import cz.xprees.secrets.core.reference.SecretReference

/**
 * How varlock's secret functions map to EnvBridge references, both ways. Pure: the reference text is built in the provider's
 * grammar and parsed by it ([SecretReference.parse]), so it is validated exactly like a reference written by hand.
 *
 * | varlock | EnvBridge |
 * |---|---|
 * | `infisical()`, `infisical("KEY")`, `infisical("KEY", "/path")`, `infisical(id, "KEY"[, "/path"])` | `infisical://[env:][path/]KEY?project=` with `environment`, `projectId` and `secretPath` of `@initInfisical` |
 * | `vaultSecret("mount/path#field")`, `vaultSecret("mount/path", key="F")`, `vaultSecret("mount/path")` (field = item name), `vaultSecret()` (`defaultPath`) | `vault://mount/path#field?namespace=` (`pathPrefix`, `defaultPath`, `namespace` of `@initHcpVault`) |
 * | `vaultSecret("mount/path", raw=true)` | every field of the secret, as a JSON object |
 * | `op(op://vault/item/field)`, `op(id, op://…)` | the `op://` reference itself |
 * | `doppler()`, `doppler("KEY")`, `doppler(id, "KEY")` | `doppler://config:KEY?project=` (`project`, `config` of `@initDoppler`) |
 * | `awsSecret()`, `awsSecret("name")`, `awsSecret("name#key")`, `awsSecret("name", key="k")` | `aws-sm://name#key` (`namePrefix` of `@initAws`); without a key the whole secret (a plain string, or its JSON) |
 */
object VarlockSecrets {

    /**
     * A secret function as an EnvBridge reference.
     *
     * @property whole the function reads a whole secret (Vault `raw=true`, AWS without a key): [reference] is a scope import of it
     * @property plainKey for [whole]: the key a plain-string secret has (AWS: the last segment of its name)
     * @property allowMissing a missing secret is undefined instead of an error
     */
    data class Mapped(val reference: SecretReference, val whole: Boolean = false, val plainKey: String? = null, val allowMissing: Boolean = false)

    /**
     * Maps a secret function call to a reference.
     *
     * @param args the positional arguments after the instance ID, evaluated (null: undefined)
     * @param named the named arguments, evaluated
     * @param init the arguments of the instance's `@init…` decorator EnvBridge reads ([VarlockProvider.connectionArgs]), evaluated
     * @param connection the EnvBridge connection the instance maps to (name or ID), or null for the binding's
     * @throws IllegalArgumentException with a message naming what is wrong (never a value) when it can't be mapped
     */
    fun map(
        provider: VarlockProvider,
        itemKey: String,
        args: List<String?>,
        named: Map<String, String?>,
        init: Map<String, String> = emptyMap(),
        connection: String? = null,
    ): Mapped {
        require(args.none { it == null }) { "an argument has no value (a variable it refers to is not set)" }
        val text = when (provider) {
            VarlockProvider.INFISICAL -> {
                val key = args.getOrNull(0) ?: itemKey
                require(key.isNotBlank()) { "names no secret" }
                val path = args.getOrNull(1) ?: init["secretPath"]
                buildString {
                    append("infisical://")
                    init["environment"]?.takeIf { it.isNotBlank() }?.let { append(it).append(':') }
                    pathSegments(path).forEach { append(ReferenceSyntax.encodeSegment(it)).append('/') }
                    append(ReferenceSyntax.encodeSegment(key))
                    append(ReferenceSyntax.formatQuery(listOfNotNull(init["projectId"]?.takeIf { it.isNotBlank() }?.let { "project" to it }, connection?.let { "connection" to it })))
                }
            }

            VarlockProvider.VAULT -> {
                val ref = args.getOrNull(0).orEmpty()
                val pathPart = ref.substringBefore('#')
                val explicitKey = ref.substringAfter('#', "").ifEmpty { null }
                val raw = named["raw"] == "true"
                val key = named["key"]
                require(!(raw && (key != null || explicitKey != null))) { "raw=true can't be combined with a key" }
                val base = pathPart.ifEmpty { init["defaultPath"].orEmpty() }
                require(base.isNotEmpty()) { "names no secret path, and its @initHcpVault has no defaultPath" }
                val full = init["pathPrefix"]?.takeIf { it.isNotBlank() }?.let { it.trimEnd('/') + "/" + base.trimStart('/') } ?: base
                val segments = pathSegments(full)
                require(segments.size >= 2) { "needs a mount and a secret path, as in vaultSecret(\"secret/app/db#password\")" }
                val field = key ?: explicitKey ?: if (raw) null else itemKey
                buildString {
                    append("vault://").append(ReferenceSyntax.encodeSegment(segments.first()))
                    segments.drop(1).forEach { append('/').append(ReferenceSyntax.encodeSegment(it)) }
                    append('#').append(field?.let(ReferenceSyntax::encodeSegment) ?: "*")
                    append(ReferenceSyntax.formatQuery(listOfNotNull(init["namespace"]?.takeIf { it.isNotBlank() }?.let { "namespace" to it }, connection?.let { "connection" to it })))
                }
            }

            VarlockProvider.ONEPASSWORD -> {
                val ref = args.getOrNull(0).orEmpty().trim()
                require(ref.startsWith("op://")) { "expects an op:// reference" }
                if (connection == null) ref else ref + (if ('?' in ref) "&" else "?") + "connection=" + java.net.URLEncoder.encode(connection, Charsets.UTF_8)
            }

            VarlockProvider.DOPPLER -> {
                val key = args.getOrNull(0) ?: itemKey
                require(key.isNotBlank()) { "names no secret" }
                buildString {
                    append("doppler://")
                    init["config"]?.takeIf { it.isNotBlank() }?.let { append(it).append(':') }
                    append(ReferenceSyntax.encodeSegment(key))
                    append(ReferenceSyntax.formatQuery(listOfNotNull(init["project"]?.takeIf { it.isNotBlank() }?.let { "project" to it }, connection?.let { "connection" to it })))
                }
            }

            VarlockProvider.AWS -> {
                val ref = args.getOrNull(0) ?: itemKey
                val name = init["namePrefix"].orEmpty() + ref.substringBefore('#')
                require(name.isNotBlank()) { "names no secret" }
                val key = named["key"] ?: ref.substringAfter('#', "").ifEmpty { null }
                "aws-sm://" + encodeAwsName(name) + "#" + (key?.let(ReferenceSyntax::encodeSegment) ?: "*") +
                    ReferenceSyntax.formatQuery(listOfNotNull(connection?.let { "connection" to it }))
            }
        }
        val reference = SecretReference.parse(text, listOf(provider.scheme)) ?: throw IllegalArgumentException("is not a ${provider.scheme} reference")
        val allowMissing = named["allowMissing"] == "true" || init["allowMissing"] == "true"
        return when {
            provider == VarlockProvider.VAULT && reference.key == null -> Mapped(reference, whole = true, allowMissing = allowMissing)
            provider == VarlockProvider.AWS && reference.key == null ->
                Mapped(reference, whole = true, plainKey = (init["namePrefix"].orEmpty() + (args.getOrNull(0) ?: itemKey)).substringAfterLast('/'), allowMissing = allowMissing)
            else -> Mapped(reference, allowMissing = allowMissing)
        }
    }

    /**
     * The varlock function that reads [reference] as the value of item [itemKey], in the form a person would write it
     * (`infisical()` when the secret has the item's name), or null when varlock can't name it with the given instance (another
     * environment or project than its `@init…` says, a Vault short reference, a scope import, …).
     */
    fun call(reference: SecretReference, itemKey: String, init: Map<String, String> = emptyMap(), instance: String = DEFAULT_INSTANCE): String? {
        val key = reference.key ?: return null
        val prefix = if (instance == DEFAULT_INSTANCE) emptyList() else listOf(instance)
        fun fn(name: String, args: List<String>) = "$name(" + (prefix + args).joinToString(", ") + ")"
        return when (reference.scheme.lowercase()) {
            VarlockProvider.INFISICAL.scheme -> {
                if (reference.projectId != null && reference.projectId != init["projectId"]) return null
                if (reference.environment != null && reference.environment != init["environment"]) return null
                val path = SecretScope.normalizePath(reference.path)
                when {
                    path != SecretScope.normalizePath(init["secretPath"]) -> fn("infisical", listOf(quote(key), quote(path)))
                    key == itemKey && instance == DEFAULT_INSTANCE -> "infisical()"
                    else -> fn("infisical", listOf(quote(key)))
                }
            }

            VarlockProvider.VAULT.scheme -> {
                val mount = reference.projectId?.takeIf { '{' !in it } ?: return null
                if (reference.environment != null && reference.environment.trim('/') != (init["namespace"]?.trim('/') ?: "root")) return null
                var path = mount + SecretScope.normalizePath(reference.path).trimEnd('/')
                init["pathPrefix"]?.takeIf { it.isNotBlank() }?.trim('/')?.let { prefixPath ->
                    if (!path.startsWith("$prefixPath/")) return null
                    path = path.removePrefix("$prefixPath/")
                }
                if (init["defaultPath"]?.trim('/') == path) path = ""
                val ref = if (key == itemKey) path else "$path#$key"
                if (ref.isEmpty() && instance == DEFAULT_INSTANCE) "vaultSecret()" else fn("vaultSecret", listOf(quote(ref)))
            }

            VarlockProvider.ONEPASSWORD.scheme -> {
                val text = reference.copy(connectionId = null).format()
                fn("op", listOf(if (text.any { it == ' ' || it == ',' || it == ')' || it == '#' || it == '"' }) quote(text) else text))
            }

            VarlockProvider.DOPPLER.scheme -> {
                if (reference.projectId != null && reference.projectId != init["project"]) return null
                if (reference.environment != null && reference.environment != init["config"]) return null
                if (key == itemKey && instance == DEFAULT_INSTANCE) "doppler()" else fn("doppler", listOf(quote(key)))
            }

            VarlockProvider.AWS.scheme -> {
                val project = reference.projectId ?: return null
                val environment = reference.environment ?: return null
                var name = when (project) {
                    "~" -> environment
                    "/" -> "/$environment"
                    else -> "$project/$environment"
                }
                init["namePrefix"]?.takeIf { it.isNotEmpty() }?.let { namePrefix ->
                    if (!name.startsWith(namePrefix)) return null
                    name = name.removePrefix(namePrefix)
                }
                // A plain-string secret's one key is named after the secret
                fn("awsSecret", listOf(quote(if (key == environment) name else "$name#$key")))
            }

            else -> null
        }
    }

    /** One secret function in a `.env` file, mapped to a reference (for the editor and reference updates). */
    data class Found(
        val itemKey: String,
        val provider: VarlockProvider,
        val instance: String,
        /** The call, from its name to `)` (end exclusive). */
        val start: Int,
        val end: Int,
        val reference: SecretReference,
        /** The static arguments of the instance's `@init…` decorator. */
        val init: Map<String, String>,
    )

    /**
     * The secret functions of [text] (a `.env` file) that map to a single secret with static arguments: those whose arguments
     * or `@init…` values depend on other variables are left out, as are whole-secret reads. `op(…)` is left out as well: its
     * `op://` reference is found as a reference already. The connection of a named instance is unknown here: such references
     * carry the instance ID as their connection.
     */
    fun find(text: String): List<Found> {
        val file = runCatching { EnvSpecParser.parse(text) }.getOrNull() ?: return emptyList()
        val inits = staticInits(file)
        val compiler = VarlockCompiler(inits.keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() })
        val result = mutableListOf<Found>()
        for (item in file.items) {
            fun visit(value: EnvSpecValue) {
                if (value is EnvSpecValue.Call) {
                    val provider = VarlockProvider.ofFunction(value.name)
                    if (provider != null && provider != VarlockProvider.ONEPASSWORD) {
                        mapStatic(compiler, value, item.key, inits)?.let { result += it }
                        return
                    }
                    value.args.forEach { visit(it.value) }
                }
            }
            item.value?.let(::visit)
        }
        return result
    }

    private fun mapStatic(compiler: VarlockCompiler, call: EnvSpecValue.Call, itemKey: String, inits: Map<Pair<VarlockProvider, String>, Map<String, String>>): Found? {
        val secret = (compiler.compile(call, itemKey).expr as? VarlockExpr.Secret)?.call ?: return null
        fun static(expr: VarlockExpr): String? = (expr as? VarlockExpr.Text)?.value
        val args = secret.positional.map { static(it) ?: return null }
        val named = secret.named.mapValues { (_, v) -> static(v) ?: return null }
        val init = inits[secret.provider to secret.instance].orEmpty()
        val connection = secret.instance.takeIf { it != DEFAULT_INSTANCE }
        val mapped = runCatching { map(secret.provider, itemKey, args, named, init, connection) }.getOrNull() ?: return null
        if (mapped.whole) return null
        return Found(itemKey, secret.provider, secret.instance, call.range.first, call.range.last + 1, mapped.reference, init)
    }

    /** The `@init…` decorators of [file] (provider, instance) -> their static arguments EnvBridge reads. */
    fun staticInits(file: EnvSpecFile): Map<Pair<VarlockProvider, String>, Map<String, String>> {
        val result = LinkedHashMap<Pair<VarlockProvider, String>, Map<String, String>>()
        for (decorator in file.rootDecorators) {
            val provider = VarlockProvider.ofInit(decorator.name) ?: continue
            val id = staticArg(decorator, "id") ?: DEFAULT_INSTANCE
            result[provider to id] = provider.connectionArgs.mapNotNull { arg -> staticArg(decorator, arg)?.let { arg to it } }.toMap()
        }
        return result
    }

    /** The value of argument [name] of [decorator] when it is static text (no reference, no function), else null. */
    fun staticArg(decorator: EnvSpecDecorator, name: String): String? {
        val value = decorator.args?.lastOrNull { it.key == name }?.value as? EnvSpecValue.Literal ?: return null
        return value.value.takeIf { value.quote == "'" || '$' !in it }
    }

    private fun pathSegments(path: String?): List<String> = path.orEmpty().split('/').filter { it.isNotEmpty() }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** An AWS secret name, raw where AWS allows the characters, percent-encoded elsewhere (as the AWS grammar writes it). */
    private fun encodeAwsName(name: String): String = buildString {
        name.codePoints().forEach { cp ->
            val text = String(Character.toChars(cp))
            if (cp < 0x80 && (text[0].isLetterOrDigit() || text[0] in "/_+=.@-")) append(text)
            else append(java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20"))
        }
    }
}
