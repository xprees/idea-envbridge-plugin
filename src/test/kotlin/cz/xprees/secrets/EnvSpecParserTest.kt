package cz.xprees.secrets

import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Code
import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Severity
import cz.xprees.secrets.core.envspec.EnvSpecFile
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EnvSpecParserTest {
    private fun parse(text: String): EnvSpecFile = EnvSpecParser.parse(text)

    private fun EnvSpecFile.codes() = diagnostics.map { it.code }

    @Test
    fun `the example of the research notes`() {
        val text = """
            # @required @sensitive @type=string(startsWith=sk-)
            # @docs(https://docs.servicex.com/api-keys)
            SERVICE_X_API_KEY=
        """.trimIndent()
        val file = parse(text)
        assertThat(file.diagnostics).isEmpty()
        val item = file.items.single()
        assertThat(item.key).isEqualTo("SERVICE_X_API_KEY")
        assertThat(item.value).isNull()
        assertThat(item.decorators.map { it.name }).containsExactly("required", "sensitive", "type", "docs")
        assertThat(item.decorator("required")!!.booleanValue).isTrue()
        val type = item.decorator("type")!!.value as EnvSpecValue.Call
        assertThat(type.name).isEqualTo("string")
        assertThat(type.named("startsWith")).isEqualTo(EnvSpecValue.Literal("sk-", null, "sk-", type.named("startsWith")!!.range))
        val docs = item.decorator("docs")!!
        assertThat(docs.value).isNull()
        assertThat(docs.args!!.single().value).isInstanceOf(EnvSpecValue.Literal::class.java)
        assertThat((docs.args!!.single().value as EnvSpecValue.Literal).value).isEqualTo("https://docs.servicex.com/api-keys")
        assertThat(text.substring(docs.range.first, docs.range.last + 1)).isEqualTo("@docs(https://docs.servicex.com/api-keys)")
        assertThat(text.substring(item.keyRange.first, item.keyRange.last + 1)).isEqualTo("SERVICE_X_API_KEY")
    }

    @Test
    fun `header with root decorators ends at a divider`() {
        val file = parse(
            """
            # My app's environment
            # @defaultRequired=infer @defaultSensitive=false
            # @currentEnv=${'$'}APP_ENV
            # ---

            # The environment
            APP_ENV=dev
            """.trimIndent(),
        )
        assertThat(file.diagnostics).isEmpty()
        val header = file.header!!
        assertThat(header.decorators.map { it.name }).containsExactly("defaultRequired", "defaultSensitive", "currentEnv")
        assertThat(header.comments.first()).isEqualTo("My app's environment")
        assertThat((file.rootDecorator("defaultRequired")!!.value as EnvSpecValue.Literal).value).isEqualTo("infer")
        assertThat(file.rootDecorator("defaultSensitive")!!.booleanValue).isFalse()
        assertThat(file.rootDecorator("currentEnv")!!.value).isEqualTo(EnvSpecValue.Ref("APP_ENV", "\$APP_ENV", file.rootDecorator("currentEnv")!!.value!!.range))
        val item = file.items.single()
        assertThat(item.decorators).isEmpty()
        assertThat(item.comments).containsExactly("The environment")
        assertThat(item.literalValue).isEqualTo("dev")
    }

    @Test
    fun `equals divider and a header right above the first item`() {
        val file = parse("# @defaultSensitive=true\n# ===\nA=1\n")
        assertThat(file.rootDecorators.map { it.name }).containsExactly("defaultSensitive")
        assertThat(file.items.single().decorators).isEmpty()
    }

    @Test
    fun `without a divider the top block belongs to the first item`() {
        val file = parse("# @required\nA=1\n")
        assertThat(file.header).isNull()
        assertThat(file.items.single().has("required")).isTrue()
    }

    @Test
    fun `a blank line detaches decorators`() {
        val file = parse("A=1\n\n# @required\n\nB=2\n")
        assertThat(file.items.map { it.decorators }).allMatch { it.isEmpty() }
        assertThat(file.detachedDecorators.map { it.name }).containsExactly("required")
        assertThat(file.codes()).containsExactly(Code.DETACHED_DECORATORS)
        // Before the first item, without a header
        val top = parse("# @required\n\nA=1\n")
        assertThat(top.detachedDecorators.map { it.name }).containsExactly("required")
        assertThat(top.items.single().decorators).isEmpty()
    }

    @Test
    fun `a later divider only ends a comment block`() {
        val file = parse("# @defaultRequired=true\n# ---\nA=1\n# ---\n# section\n# @optional\nB=\n")
        assertThat(file.rootDecorators.map { it.name }).containsExactly("defaultRequired")
        assertThat(file.item("B")!!.has("optional")).isTrue()
        assertThat(file.item("B")!!.comments).containsExactly("section")
    }

    @Test
    fun `trailing decorators and several per line`() {
        val file = parse("ITEM1=foo # @required\nITEM2=\"x y\" # @sensitive @type=enum(a, b) # and a comment\nITEM3= # @optional\n")
        assertThat(file.diagnostics).isEmpty()
        assertThat(file.item("ITEM1")!!.decorators.map { it.name }).containsExactly("required")
        assertThat(file.item("ITEM1")!!.literalValue).isEqualTo("foo")
        val item2 = file.item("ITEM2")!!
        assertThat(item2.literalValue).isEqualTo("x y")
        assertThat(item2.decorators.map { it.name }).containsExactly("sensitive", "type")
        val enum = item2.decorator("type")!!.value as EnvSpecValue.Call
        assertThat(enum.positional.map { (it as EnvSpecValue.Literal).value }).containsExactly("a", "b")
        assertThat(file.item("ITEM3")!!.value).isNull()
        assertThat(file.item("ITEM3")!!.has("optional")).isTrue()
    }

    @Test
    fun `block decorators come before trailing ones`() {
        val item = parse("# @sensitive\nKEY=1 # @required\n").items.single()
        assertThat(item.decorators.map { it.name }).containsExactly("sensitive", "required")
    }

    @Test
    fun `decorator values`() {
        val file = parse(
            """
            # @a=false @b="quoted value" @c='single' @d=[A, B] @e=forEnv(prod, staging) @f=${'$'}{OTHER}
            # @type=string(matches=regex("^sk-[a-z]+${'$'}", i), minLength=10)
            # @import(./.env.other, pick=[K1, K2], allowMissing=true)
            KEY=
            """.trimIndent(),
        )
        assertThat(file.diagnostics).isEmpty()
        val item = file.items.single()
        assertThat(item.decorator("a")!!.booleanValue).isFalse()
        assertThat((item.decorator("b")!!.value as EnvSpecValue.Literal).value).isEqualTo("quoted value")
        assertThat((item.decorator("b")!!.value as EnvSpecValue.Literal).quote).isEqualTo("\"")
        assertThat(item.decorator("b")!!.booleanValue).isNull()
        assertThat((item.decorator("c")!!.value as EnvSpecValue.Literal).value).isEqualTo("single")
        assertThat((item.decorator("d")!!.value as EnvSpecValue.Array).items.map { (it as EnvSpecValue.Literal).value }).containsExactly("A", "B")
        val forEnv = item.decorator("e")!!.value as EnvSpecValue.Call
        assertThat(forEnv.name).isEqualTo("forEnv")
        assertThat(forEnv.positional.map { it.text }).containsExactly("prod", "staging")
        assertThat((item.decorator("f")!!.value as EnvSpecValue.Ref).name).isEqualTo("OTHER")
        val type = item.decorator("type")!!.value as EnvSpecValue.Call
        val regex = type.named("matches") as EnvSpecValue.Call
        assertThat(regex.name).isEqualTo("regex")
        assertThat(regex.positional.map { (it as EnvSpecValue.Literal).value }).containsExactly("^sk-[a-z]+\$", "i")
        assertThat((type.named("minLength") as EnvSpecValue.Literal).value).isEqualTo("10")
        val import = item.decorator("import")!!
        assertThat(import.args!!.map { it.key }).containsExactly(null, "pick", "allowMissing")
        assertThat((import.args!![0].value as EnvSpecValue.Literal).value).isEqualTo("./.env.other")
        assertThat(import.args!![1].value).isInstanceOf(EnvSpecValue.Array::class.java)
        assertThat(import.valueText).isEqualTo("./.env.other, pick=[K1, K2], allowMissing=true")
    }

    @Test
    fun `defaultRequired infer and inferFromPrefix`() {
        val file = parse("# @defaultRequired=infer @defaultSensitive=inferFromPrefix(PUBLIC_)\n# ---\n")
        val call = file.rootDecorator("defaultSensitive")!!.value as EnvSpecValue.Call
        assertThat(call.name).isEqualTo("inferFromPrefix")
        assertThat((call.positional.single() as EnvSpecValue.Literal).value).isEqualTo("PUBLIC_")
        assertThat(file.items).isEmpty()
    }

    @Test
    fun `item values - undefined, empty, quoted, references and function calls`() {
        val file = parse(
            """
            UNDEFINED=
            EMPTY=""
            SPACED=  padded value  # comment
            DOUBLE="a\nb \"q\""
            SINGLE='lit\n'
            BACKTICK=`x`
            HASH=#fff
            REF=${'$'}OTHER
            BRACED=${'$'}{OTHER}
            MIXED=${'$'}{A}-${'$'}{B}
            CALL=op(op://vault/item/field)
            CALL2=infisical("DB_PASSWORD", "/database") # @sensitive
            CALL3=fallback(${'$'}A, "x # y")
            export EXPORTED=1
            BINDING= # infisical://prod:DB
            """.trimIndent(),
        )
        assertThat(file.diagnostics).isEmpty()
        assertThat(file.item("UNDEFINED")!!.value).isNull()
        assertThat(file.item("EMPTY")!!.literalValue).isEqualTo("")
        assertThat(file.item("SPACED")!!.literalValue).isEqualTo("padded value")
        assertThat(file.item("SPACED")!!.trailingComment).isEqualTo("comment")
        assertThat(file.item("DOUBLE")!!.literalValue).isEqualTo("a\nb \"q\"")
        assertThat(file.item("SINGLE")!!.literalValue).isEqualTo("lit\\n")
        assertThat(file.item("BACKTICK")!!.literalValue).isEqualTo("x")
        assertThat(file.item("HASH")!!.literalValue).isEqualTo("#fff")
        assertThat((file.item("REF")!!.value as EnvSpecValue.Ref).name).isEqualTo("OTHER")
        assertThat((file.item("BRACED")!!.value as EnvSpecValue.Ref).name).isEqualTo("OTHER")
        assertThat(file.item("MIXED")!!.literalValue).isEqualTo("\${A}-\${B}")
        val call = file.item("CALL")!!.value as EnvSpecValue.Call
        assertThat(call.name).isEqualTo("op")
        assertThat((call.positional.single() as EnvSpecValue.Literal).value).isEqualTo("op://vault/item/field")
        val call2 = file.item("CALL2")!!
        assertThat((call2.value as EnvSpecValue.Call).positional.map { (it as EnvSpecValue.Literal).value }).containsExactly("DB_PASSWORD", "/database")
        assertThat(call2.has("sensitive")).isTrue()
        val call3 = file.item("CALL3")!!.value as EnvSpecValue.Call
        assertThat(call3.positional[0]).isInstanceOf(EnvSpecValue.Ref::class.java)
        assertThat((call3.positional[1] as EnvSpecValue.Literal).value).isEqualTo("x # y")
        assertThat(file.item("EXPORTED")!!.literalValue).isEqualTo("1")
        assertThat(file.item("BINDING")!!.value).isNull()
        assertThat(file.item("BINDING")!!.trailingComment).isEqualTo("infisical://prod:DB")
    }

    @Test
    fun `multi-line values`() {
        val text = "# @sensitive\nCERT=\"\"\"\nline1\nline2\n\"\"\" # @required\nNEXT=1\nQUOTED=\"a\nb\"\nFENCE=```\nx\n```\nLAST=2"
        val file = parse(text)
        assertThat(file.diagnostics).isEmpty()
        val cert = file.item("CERT")!!
        assertThat(cert.literalValue).isEqualTo("line1\nline2\n")
        assertThat(cert.decorators.map { it.name }).containsExactly("sensitive", "required")
        assertThat(file.item("NEXT")!!.line).isEqualTo(5)
        assertThat(file.item("QUOTED")!!.literalValue).isEqualTo("a\nb")
        assertThat(file.item("FENCE")!!.literalValue).isEqualTo("x\n")
        assertThat(file.item("LAST")!!.line).isEqualTo(11)
        assertThat(file.items.map { it.key }).containsExactly("CERT", "NEXT", "QUOTED", "FENCE", "LAST")
    }

    @Test
    fun `crlf line ends`() {
        val file = parse("# @defaultRequired=true\r\n# ---\r\n# @optional\r\nA=1\r\nB=\"x\" # @sensitive\r\n")
        assertThat(file.diagnostics).isEmpty()
        assertThat(file.rootDecorators.map { it.name }).containsExactly("defaultRequired")
        assertThat(file.item("A")!!.literalValue).isEqualTo("1")
        assertThat(file.item("A")!!.has("optional")).isTrue()
        assertThat(file.item("B")!!.literalValue).isEqualTo("x")
        assertThat(file.item("B")!!.has("sensitive")).isTrue()
    }

    @Test
    fun `plain comments and commented-out assignments are no decorators`() {
        val file = parse("# just a comment, mail me @ home\n# OLD=1\nA=1 # see @foo\n")
        assertThat(file.usesDecorators).isFalse()
        assertThat(file.diagnostics).isEmpty()
        assertThat(file.items.single().comments).containsExactly("just a comment, mail me @ home", "OLD=1")
    }

    @Test
    fun `bad input gives diagnostics, never exceptions`() {
        val file = parse(
            """
            # @required @ @1x @type=enum(a, b
            # @ok garbage here
            KEY='unterminated
            NEXT=fine
            not an assignment
            # @d=[a, b
            # @e='open
            OTHER=x
            """.trimIndent(),
        )
        assertThat(file.codes()).contains(
            Code.INVALID_DECORATOR, Code.UNCLOSED_PARENTHESIS, Code.UNEXPECTED_TEXT, Code.UNTERMINATED_QUOTE, Code.INVALID_LINE,
            Code.UNCLOSED_BRACKET,
        )
        assertThat(file.diagnostics.filter { it.code == Code.INVALID_LINE }.map { it.severity }).containsOnly(Severity.WARNING)
        assertThat(file.item("KEY")!!.decorators.map { it.name }).contains("required", "type", "ok")
        assertThat(file.item("KEY")!!.literalValue).isEqualTo("unterminated")
        assertThat(file.item("NEXT")!!.literalValue).isEqualTo("fine")
        assertThat(file.item("OTHER")!!.decorators.map { it.name }).containsExactly("d", "e")
        // Diagnostics point into the text
        file.diagnostics.forEach { assertThat(it.range.first).isBetween(0, 200) }
    }

    @Test
    fun `fuzzing never throws`() {
        val alphabet = "#@=()[]\"'`\$ {},\n\r\tAZaz09_-./:"
        val random = java.util.Random(42)
        repeat(3000) {
            val text = buildString { repeat(random.nextInt(60)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val file = parse(text)
            file.diagnostics.forEach { d -> assertThat(d.range.first).isBetween(0, text.length) }
            file.items.forEach { item -> assertThat(item.keyRange.last).isLessThan(text.length) }
        }
        listOf("", "\n", "#", "# @", "=", "A=", "A=\"\"\"", "A=(", "A=f(", "# @x(", "# @x=", "# @x=[", "# ---", "A=b(c)d").forEach { parse(it) }
    }

    @Test
    fun `call-like text that isn't a whole call stays text`() {
        val file = parse("A=f(x)y\nB=f(x) # @required\n")
        assertThat(file.item("A")!!.literalValue).isEqualTo("f(x)y")
        assertThat(file.item("B")!!.value).isInstanceOf(EnvSpecValue.Call::class.java)
        assertThat(file.diagnostics).isEmpty()
    }

    @Test
    fun `divider detection`() {
        assertThat(EnvSpecParser.isDivider(" ---")).isTrue()
        assertThat(EnvSpecParser.isDivider("=====")).isTrue()
        assertThat(EnvSpecParser.isDivider("--")).isFalse()
        assertThat(EnvSpecParser.isDivider("text ---")).isFalse()
    }
}
