package cz.xprees.secrets

import cz.xprees.secrets.aws.AwsGrammar
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.SecretReference
import cz.xprees.secrets.core.varlock.DEFAULT_INSTANCE
import cz.xprees.secrets.core.varlock.SecretCall
import cz.xprees.secrets.core.varlock.SecretUse
import cz.xprees.secrets.core.varlock.VarlockCompiler
import cz.xprees.secrets.core.varlock.VarlockEvaluator
import cz.xprees.secrets.core.varlock.VarlockException
import cz.xprees.secrets.core.varlock.VarlockExpr
import cz.xprees.secrets.core.varlock.VarlockProblem
import cz.xprees.secrets.core.varlock.VarlockProblem.Code
import cz.xprees.secrets.core.varlock.VarlockProvider
import cz.xprees.secrets.core.varlock.VarlockSecrets
import cz.xprees.secrets.core.varlock.VarlockValue
import cz.xprees.secrets.doppler.DopplerGrammar
import cz.xprees.secrets.onepassword.OnePasswordGrammar
import cz.xprees.secrets.vault.VaultGrammar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The varlock mode's pure parts: compiling values, refusals, evaluation, and the function <-> reference mapping. */
class VarlockTest {
    @Before
    fun grammars() {
        SecretReference.grammarOverrides["vault"] = VaultGrammar
        SecretReference.grammarOverrides["op"] = OnePasswordGrammar
        SecretReference.grammarOverrides["doppler"] = DopplerGrammar
        SecretReference.grammarOverrides["aws-sm"] = AwsGrammar
    }

    @After
    fun reset() {
        listOf("vault", "op", "doppler", "aws-sm").forEach { SecretReference.grammarOverrides.remove(it) }
    }

    private fun item(text: String) = EnvSpecParser.parse(text).items.last()

    private fun compile(line: String, instances: Map<VarlockProvider, Set<String>> = emptyMap()): VarlockCompiler.Result {
        val item = item(line)
        return VarlockCompiler(instances).compile(item.value, item.key)
    }

    private fun expr(line: String, instances: Map<VarlockProvider, Set<String>> = emptyMap()): VarlockExpr =
        compile(line, instances).also { assertThat(it.problems).isEmpty() }.expr!!

    private fun problem(line: String): VarlockProblem = compile(line).problems.single()

    @Test
    fun `interpolation expands into concat, ref and fallback, single quotes and escapes do not`() {
        assertThat(expr("A=plain")).isEqualTo(VarlockExpr.Text("plain"))
        assertThat(expr("A=\$B")).isEqualTo(VarlockExpr.Ref("B"))
        assertThat(expr("A=\${B}")).isEqualTo(VarlockExpr.Ref("B"))
        assertThat(expr("A=\"postgres://\${USER}:\$PASS@db\"")).isEqualTo(
            VarlockExpr.Pure("concat", listOf(VarlockExpr.Text("postgres://"), VarlockExpr.Ref("USER"), VarlockExpr.Text(":"), VarlockExpr.Ref("PASS"), VarlockExpr.Text("@db"))),
        )
        assertThat(expr("A=\${PORT:-5432}")).isEqualTo(VarlockExpr.Pure("fallback", listOf(VarlockExpr.Ref("PORT"), VarlockExpr.Text("5432"))))
        assertThat(expr("A='\$NOT_A_REF'")).isEqualTo(VarlockExpr.Text("\$NOT_A_REF"))
        assertThat(expr("A=price\\\$5")).isEqualTo(VarlockExpr.Text("price\\\$5"))
        assertThat(expr("A=true")).isEqualTo(VarlockExpr.Text("true", true))
        assertThat(expr("A=\"true\"")).isEqualTo(VarlockExpr.Text("true"))
        assertThat(expr("A=undefined")).isEqualTo(VarlockExpr.Undefined)
        assertThat(expr("A=")).isEqualTo(VarlockExpr.Undefined)
        // Arguments are expanded too
        val call = (expr("A=infisical(\"DB_\${ENV}\")") as VarlockExpr.Secret).call
        assertThat(call.positional.single()).isEqualTo(VarlockExpr.Pure("concat", listOf(VarlockExpr.Text("DB_"), VarlockExpr.Ref("ENV"))))
    }

    @Test
    fun `refused and unsupported functions name the key and the function, never a value`() {
        assertThat(problem("TOKEN=exec(\"op read op://x/y/z\")")).isEqualTo(VarlockProblem(Code.REFUSED, "TOKEN", listOf("exec()", "COMMAND")))
        assertThat(problem("TOKEN=\"prefix-\$(whoami)\"")).isEqualTo(VarlockProblem(Code.REFUSED, "TOKEN", listOf("$(…)", "COMMAND")))
        assertThat(problem("K=keychain(\"svc\")").args).containsExactly("keychain()", "KEYCHAIN")
        assertThat(problem("K=varlock(\"local:abc\")").args).containsExactly("varlock()", "LOCAL_ENCRYPTION")
        assertThat(problem("K=generateOtp(\"seed\")").args).containsExactly("generateOtp()", "OTP")
        // exec() nested anywhere, also inside cache() or fallback()
        assertThat(problem("K=fallback(\$A, cache(exec(\"x\"), ttl=1h))").code).isEqualTo(Code.REFUSED)
        assertThat(problem("K=randomUuid()")).isEqualTo(VarlockProblem(Code.UNSUPPORTED, "K", listOf("randomUuid()")))
        assertThat(problem("K=bitwardenSecret(\"x\")").args).containsExactly("bitwardenSecret()")
        assertThat(problem("K=awsParam(\"/x\")").code).isEqualTo(Code.UNSUPPORTED)
        assertThat(problem("K=eq(a)").code).isEqualTo(Code.INVALID_CALL)
        assertThat(problem("K=infisical(prod, \"KEY\", \"/p\")")).isEqualTo(VarlockProblem(Code.UNKNOWN_INSTANCE, "K", listOf("infisical()", "prod")))
        assertThat(problem("K=op()").code).isEqualTo(Code.INVALID_CALL)
        // cache() around something supported is just that value
        assertThat(expr("K=cache(infisical(), ttl=1h)")).isInstanceOf(VarlockExpr.Secret::class.java)
    }

    @Test
    fun `instance IDs come first where varlock puts them`() {
        fun call(line: String, instances: Map<VarlockProvider, Set<String>> = emptyMap()) = (expr(line, instances) as VarlockExpr.Secret).call
        assertThat(call("K=infisical()")).isEqualTo(SecretCall("infisical", VarlockProvider.INFISICAL, DEFAULT_INSTANCE, emptyList(), emptyMap(), "K"))
        // Two arguments: an instance only when declared, else name and path
        assertThat(call("K=infisical(\"DB\", \"/database\")").instance).isEqualTo(DEFAULT_INSTANCE)
        assertThat(call("K=infisical(\"DB\", \"/database\")").positional).hasSize(2)
        val withInstance = call("K=infisical(dev, \"DB\")", mapOf(VarlockProvider.INFISICAL to setOf("dev")))
        assertThat(withInstance.instance).isEqualTo("dev")
        assertThat(withInstance.positional).containsExactly(VarlockExpr.Text("DB"))
        assertThat(call("K=vaultSecret(prod, \"secret/a#b\")", mapOf(VarlockProvider.VAULT to setOf("prod"))).instance).isEqualTo("prod")
        assertThat(call("K=op(prod, op://Dev/Db/password)", mapOf(VarlockProvider.ONEPASSWORD to setOf("prod"))).positional)
            .containsExactly(VarlockExpr.Text("op://Dev/Db/password"))
        assertThat(call("K=vaultSecret(\"secret/a\", raw=true)").named).containsEntry("raw", VarlockExpr.Text("true", true))
    }

    private fun map(provider: VarlockProvider, vararg args: String, named: Map<String, String> = emptyMap(), init: Map<String, String> = emptyMap(), key: String = "DB_PASSWORD", connection: String? = null) =
        VarlockSecrets.map(provider, key, args.toList(), named, init, connection)

    @Test
    fun `infisical maps to infisical references with the header's project and environment`() {
        assertThat(map(VarlockProvider.INFISICAL).reference.format()).isEqualTo("infisical://DB_PASSWORD")
        assertThat(map(VarlockProvider.INFISICAL, "STRIPE").reference.format()).isEqualTo("infisical://STRIPE")
        val init = mapOf("projectId" to "p1", "environment" to "prod", "secretPath" to "/app")
        assertThat(map(VarlockProvider.INFISICAL, init = init).reference.format()).isEqualTo("infisical://prod:app/DB_PASSWORD?project=p1")
        assertThat(map(VarlockProvider.INFISICAL, "DB", "/database", init = init, connection = "Work").reference)
            .isEqualTo(SecretReference("infisical", "DB", "prod", "/database", projectId = "p1", connectionId = "Work"))
        assertThat(map(VarlockProvider.INFISICAL, named = mapOf("allowMissing" to "true")).allowMissing).isTrue()
        assertThat(map(VarlockProvider.INFISICAL, init = mapOf("allowMissing" to "true")).allowMissing).isTrue()
        assertThatThrownBy { map(VarlockProvider.INFISICAL, "") }.hasMessage("names no secret")
    }

    @Test
    fun `vaultSecret maps paths, keys, prefixes, default paths and raw`() {
        assertThat(map(VarlockProvider.VAULT, "secret/db/config#password").reference.format()).isEqualTo("vault://secret/db/config#password")
        // Without a key the item name is the field
        assertThat(map(VarlockProvider.VAULT, "secret/db/config").reference.key).isEqualTo("DB_PASSWORD")
        assertThat(map(VarlockProvider.VAULT, "secret/db/config", named = mapOf("key" to "PORT")).reference.key).isEqualTo("PORT")
        val raw = map(VarlockProvider.VAULT, "secret/db/config", named = mapOf("raw" to "true"))
        assertThat(raw.whole).isTrue()
        assertThat(raw.reference.format()).isEqualTo("vault://secret/db/config#*")
        assertThatThrownBy { map(VarlockProvider.VAULT, "secret/db#x", named = mapOf("raw" to "true")) }.hasMessageContaining("raw=true")
        assertThat(map(VarlockProvider.VAULT, init = mapOf("defaultPath" to "secret/myapp/config")).reference.format())
            .isEqualTo("vault://secret/myapp/config#DB_PASSWORD")
        assertThat(map(VarlockProvider.VAULT, "#stripe", init = mapOf("defaultPath" to "secret/myapp/config")).reference.key).isEqualTo("stripe")
        assertThat(map(VarlockProvider.VAULT, "db/config#HOST", init = mapOf("pathPrefix" to "secret/myapp/", "namespace" to "team-a")).reference.format())
            .isEqualTo("vault://secret/myapp/db/config#HOST?namespace=team-a")
        assertThatThrownBy { map(VarlockProvider.VAULT) }.hasMessageContaining("defaultPath")
        assertThatThrownBy { map(VarlockProvider.VAULT, "secret") }.hasMessageContaining("mount and a secret path")
    }

    @Test
    fun `op, doppler and awsSecret map to their references, unsupported op attributes are refused`() {
        assertThat(map(VarlockProvider.ONEPASSWORD, "op://Dev/Database/password").reference)
            .isEqualTo(SecretReference.parse("op://Dev/Database/password", listOf("op")))
        assertThat(map(VarlockProvider.ONEPASSWORD, "op://Dev/Database/password", connection = "Work Account").reference.connectionId).isEqualTo("Work Account")
        assertThatThrownBy { map(VarlockProvider.ONEPASSWORD, "op://Dev/Database/one-time password?attribute=otp") }.hasMessageContaining("attribute=otp")
        assertThatThrownBy { map(VarlockProvider.ONEPASSWORD, "Dev/Database") }.hasMessageContaining("op://")

        assertThat(map(VarlockProvider.DOPPLER, init = mapOf("project" to "backend", "config" to "prd")).reference.format()).isEqualTo("doppler://prd:DB_PASSWORD?project=backend")
        assertThat(map(VarlockProvider.DOPPLER, "STRIPE").reference.format()).isEqualTo("doppler://STRIPE")

        assertThat(map(VarlockProvider.AWS, "database-creds#host").reference.format()).isEqualTo("aws-sm://database-creds#host")
        assertThat(map(VarlockProvider.AWS, "database-creds", named = mapOf("key" to "port")).reference.key).isEqualTo("port")
        val whole = map(VarlockProvider.AWS, "payments/stripe-key")
        assertThat(whole.whole).isTrue()
        assertThat(whole.plainKey).isEqualTo("stripe-key")
        assertThat(whole.reference.format()).isEqualTo("aws-sm://payments/stripe-key#*")
        assertThat(map(VarlockProvider.AWS, "stripe-key#k", init = mapOf("namePrefix" to "prod/api/")).reference.format()).isEqualTo("aws-sm://prod/api/stripe-key#k")
    }

    @Test
    fun `references are written back as varlock calls where the instance can name them`() {
        fun ref(text: String) = SecretReference.parse(text, listOf("infisical", "vault", "op", "doppler", "aws-sm"))!!
        assertThat(VarlockSecrets.call(ref("infisical://DB_PASSWORD"), "DB_PASSWORD")).isEqualTo("infisical()")
        assertThat(VarlockSecrets.call(ref("infisical://API_KEY"), "DB_PASSWORD")).isEqualTo("infisical(\"API_KEY\")")
        assertThat(VarlockSecrets.call(ref("infisical://backend/API_KEY"), "X")).isEqualTo("infisical(\"API_KEY\", \"/backend\")")
        assertThat(VarlockSecrets.call(ref("infisical://API_KEY"), "API_KEY", instance = "prod")).isEqualTo("infisical(prod, \"API_KEY\")")
        // Another environment than the header's can't be named
        assertThat(VarlockSecrets.call(ref("infisical://prod:API_KEY"), "API_KEY", mapOf("environment" to "dev"))).isNull()
        assertThat(VarlockSecrets.call(ref("infisical://prod:API_KEY"), "API_KEY", mapOf("environment" to "prod"))).isEqualTo("infisical()")
        assertThat(VarlockSecrets.call(ref("infisical://*"), "X")).isNull()

        assertThat(VarlockSecrets.call(ref("vault://secret/app/db#password"), "PW")).isEqualTo("vaultSecret(\"secret/app/db#password\")")
        assertThat(VarlockSecrets.call(ref("vault://secret/app/db#PW"), "PW")).isEqualTo("vaultSecret(\"secret/app/db\")")
        assertThat(VarlockSecrets.call(ref("vault://secret/app/db#PW"), "PW", mapOf("defaultPath" to "secret/app/db"))).isEqualTo("vaultSecret()")
        assertThat(VarlockSecrets.call(ref("vault://secret/app/db#password"), "PW", mapOf("pathPrefix" to "secret/app"))).isEqualTo("vaultSecret(\"db#password\")")
        assertThat(VarlockSecrets.call(ref("vault://./db#password"), "PW")).isNull()

        assertThat(VarlockSecrets.call(ref("op://Dev/Database/password"), "PW")).isEqualTo("op(op://Dev/Database/password)")
        assertThat(VarlockSecrets.call(ref("op://Dev/Database/api key"), "PW")).isEqualTo("op(\"op://Dev/Database/api key\")")
        assertThat(VarlockSecrets.call(ref("doppler://STRIPE"), "STRIPE")).isEqualTo("doppler()")
        assertThat(VarlockSecrets.call(ref("aws-sm://myapp/dev#DB_PASSWORD"), "X")).isEqualTo("awsSecret(\"myapp/dev#DB_PASSWORD\")")
        assertThat(VarlockSecrets.call(ref("aws-sm://#DB_PASSWORD"), "X")).isNull()

        // Round trip: the written call maps back to the same reference
        for ((text, key) in listOf("infisical://backend/API_KEY" to "X", "vault://secret/app/db#password" to "PW", "doppler://STRIPE" to "STRIPE")) {
            val call = VarlockSecrets.call(ref(text), key)!!
            val parsed = (VarlockCompiler().compile(item("$key=$call").value, key).expr as VarlockExpr.Secret).call
            val args = parsed.positional.map { (it as VarlockExpr.Text).value }
            assertThat(VarlockSecrets.map(parsed.provider, key, args, emptyMap()).reference).isEqualTo(ref(text))
        }
    }

    @Test
    fun `the editor finds secret functions with static arguments`() {
        val text = """
            # @initInfisical(projectId=p1, environment=dev, clientId=${'$'}ID, clientSecret=${'$'}SECRET)
            # @initHcpVault(id=prod, url="https://vault.example.com")
            # ---
            DB_PASSWORD=infisical()
            API=infisical("API_KEY", "/backend")
            DYNAMIC=infisical(${'$'}NAME)
            V=vaultSecret(prod, "secret/app/db#password")
            P=op(op://Dev/Database/password)
            URL="https://x:${'$'}{DB_PASSWORD}@db"
        """.trimIndent()
        val found = VarlockSecrets.find(text)
        assertThat(found.map { it.itemKey to it.reference.format() }).containsExactly(
            "DB_PASSWORD" to "infisical://dev:DB_PASSWORD?project=p1",
            "API" to "infisical://dev:backend/API_KEY?project=p1",
            "V" to "vault://secret/app/db#password?connection=prod",
        )
        val first = found.first()
        assertThat(text.substring(first.start, first.end)).isEqualTo("infisical()")
    }

    @Test
    fun `renames and moves rewrite varlock functions that read the secret`() {
        val text = "# @initInfisical(projectId=p1, environment=dev)\n# ---\n" +
            "DB_PASSWORD=infisical()\nCOPY=infisical(\"DB_PASSWORD\")\nOTHER=infisical(\"API_KEY\")\nNAMED=infisical(prod, \"DB_PASSWORD\")\n"
        val defaults = SecretReference.Defaults("p1", "dev")
        val rename = cz.xprees.secrets.core.resolve.MovedReferences.renamed("infisical", SecretScope("p1", "dev"), "DB_PASSWORD", "DATABASE_PASSWORD") { it == null }
        val edits = rename.varlockEdits(text) { defaults }
        assertThat(edits.map { it.old to it.replacement }).containsExactly(
            "infisical()" to "infisical(\"DATABASE_PASSWORD\")",
            "infisical(\"DB_PASSWORD\")" to "infisical(\"DATABASE_PASSWORD\")",
        )
        assertThat(rename.apply(text, edits)).contains("COPY=infisical(\"DATABASE_PASSWORD\")").contains("NAMED=infisical(prod, \"DB_PASSWORD\")")
        // A move to a folder: the path is written; a move to another environment can't be named by the header: left alone
        val toBackend = cz.xprees.secrets.core.resolve.MovedReferences("infisical", setOf("DB_PASSWORD"), SecretScope("p1", "dev"), SecretScope("p1", "dev", "/backend"), { it == null })
        assertThat(toBackend.varlockEdits(text) { defaults }.map { it.replacement }).containsExactly("infisical(\"DB_PASSWORD\", \"/backend\")", "infisical(\"DB_PASSWORD\", \"/backend\")")
        val toProd = cz.xprees.secrets.core.resolve.MovedReferences("infisical", setOf("DB_PASSWORD"), SecretScope("p1", "dev"), SecretScope("p1", "prod"), { it == null })
        assertThat(toProd.varlockEdits(text) { defaults }).isEmpty()
    }

    private fun evaluator(definitions: Map<String, String>, external: Map<String, String> = emptyMap(), environment: String? = "dev", secrets: Map<String, String> = emptyMap()): VarlockEvaluator {
        val compiler = VarlockCompiler()
        val exprs = definitions.mapValues { (key, value) -> compiler.compile(item("$key=$value").value, key).expr!! }
        return VarlockEvaluator(exprs, { external[it]?.let(::VarlockValue) }, environment) { call, args, _ ->
            val name = args.firstOrNull() ?: call.itemKey
            val value = secrets[name] ?: return@VarlockEvaluator VarlockValue.UNDEFINED
            VarlockValue(value, setOf(SecretUse(SecretReference("infisical", name), SecretScope("p1", "dev"), value)))
        }
    }

    @Test
    fun `evaluation follows varlock's semantics and tracks the secrets a value is made from`() {
        val e = evaluator(
            mapOf(
                "DB_PASSWORD" to "infisical()",
                "URL" to "\"postgres://app:\${DB_PASSWORD}@\${HOST}/db\"",
                "MISSING_OR_DEFAULT" to "fallback(infisical(\"NOPE\"), \"\", default-value)",
                "IS_DEV" to "forEnv(dev, test)",
                "LEVEL" to "if(forEnv(prod), warn, debug)",
                "EMPTY" to "isEmpty(\$UNSET)",
                "SAME" to "eq(\$HOST, localhost)",
                "PICK" to "ifs(eq(\$HOST, x), a, not(\$UNSET), b, c)",
                "BOTH" to "and(true, or(false, \$HOST))",
                "JOINED" to "concat(a, \$UNSET, b)",
            ),
            external = mapOf("HOST" to "localhost"),
            secrets = mapOf("DB_PASSWORD" to "s3cret"),
        )
        val url = e.value("URL")
        assertThat(url.text).isEqualTo("postgres://app:s3cret@localhost/db")
        assertThat(url.secrets.map { it.value }).containsExactly("s3cret")
        assertThat(url.toString()).doesNotContain("s3cret")
        assertThat(e.value("MISSING_OR_DEFAULT").text).isEqualTo("default-value")
        assertThat(e.value("IS_DEV").text).isEqualTo("true")
        assertThat(e.value("LEVEL").text).isEqualTo("debug")
        assertThat(e.value("EMPTY").text).isEqualTo("true")
        assertThat(e.value("SAME").text).isEqualTo("true")
        assertThat(e.value("PICK").text).isEqualTo("b")
        assertThat(e.value("BOTH").text).isEqualTo("true")
        assertThat(e.value("JOINED").text).isEqualTo("ab")
        assertThat(e.value("JOINED").secrets).isEmpty()
        assertThat(e.value("UNKNOWN").text).isNull()
    }

    @Test
    fun `cycles and forEnv without an environment are reported`() {
        val e = evaluator(mapOf("A" to "\$B", "B" to "concat(x, \$A)", "F" to "forEnv(dev)"), environment = null)
        assertThatThrownBy { e.value("A") }.isInstanceOf(VarlockException::class.java)
            .satisfies({ assertThat((it as VarlockException).problem.code).isEqualTo(Code.CYCLE) })
        assertThatThrownBy { e.value("F") }.satisfies({ assertThat((it as VarlockException).problem.code).isEqualTo(Code.NO_ENVIRONMENT) })
        // A secret function's arguments may not come from secrets (they would end up in request URLs and messages)
        val fromSecret = evaluator(mapOf("NAME" to "infisical()", "X" to "infisical(\$NAME)"), secrets = mapOf("NAME" to "SECRET_NAME_VALUE"))
        assertThatThrownBy { fromSecret.value("X") }.satisfies({
            val problem = (it as VarlockException).problem
            assertThat(problem.code).isEqualTo(Code.INVALID_CALL)
            assertThat(problem.toString()).doesNotContain("SECRET_NAME_VALUE")
        })
    }
}
