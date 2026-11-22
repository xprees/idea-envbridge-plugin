package cz.xprees.secrets

import cz.xprees.secrets.core.envspec.EnvSpecDecorators
import cz.xprees.secrets.core.envspec.EnvSpecDiagnostic.Code
import cz.xprees.secrets.core.envspec.EnvSpecLayers
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecType
import cz.xprees.secrets.core.envspec.EnvSpecValidation
import cz.xprees.secrets.core.envspec.EnvSpecValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Validation, types and the merged meaning of layered files. */
class EnvSpecSemanticsTest {
    private fun validate(text: String) = EnvSpecValidation.check(EnvSpecParser.parse(text))

    private fun type(text: String): EnvSpecType.Parsed {
        val decorator = EnvSpecParser.parse("# @type=$text\nA=\n").items.single().decorator("type")!!
        return EnvSpecType.parse(decorator.value!!)
    }

    private fun accepts(type: String, value: String) = ((type(type) as EnvSpecType.Parsed.Ok).type).accepts(value)

    @Test
    fun `validation codes`() {
        val problems = validate(
            "# @defaultRequired=sometimes @defaultSensitive=inferFromPrefix(PUBLIC_) @required\n# ---\n" +
                "# @requried\nA=1\n# @required @optional @sensitive @public\nB=\n# @sensitive @sensitive @defaultSensitive=true\nC=\n" +
                "# @fooBarBaz\nD=\n# @type\nE=\n# @initOp(token=x) @generateDotEnvExample\nF=\n",
        )
        assertThat(problems.map { it.code to it.args }).containsExactlyInAnyOrder(
            Code.MISPLACED_DECORATOR to listOf("required"),
            Code.INVALID_DECORATOR_VALUE to listOf("defaultRequired", "sometimes"),
            Code.UNKNOWN_DECORATOR to listOf("requried", "required"),
            Code.CONFLICTING_DECORATORS to listOf("required", "optional"),
            Code.CONFLICTING_DECORATORS to listOf("sensitive", "public"),
            Code.DUPLICATE_DECORATOR to listOf("sensitive"),
            Code.MISPLACED_DECORATOR to listOf("defaultSensitive"),
            Code.UNKNOWN_DECORATOR to listOf("fooBarBaz", ""),
            Code.INVALID_DECORATOR_VALUE to listOf("type", ""),
            Code.MISPLACED_DECORATOR to listOf("initOp"),
            Code.MISPLACED_DECORATOR to listOf("generateDotEnvExample"),
        )
    }

    @Test
    fun `files without decorators are not validated`() {
        assertThat(validate("A=1\nB=\n# comment\n")).isEmpty()
        assertThat(validate("# @required\nA=1\n# @optional=false\nB=\n# @required=forEnv(prod)\nC=\n")).isEmpty()
    }

    @Test
    fun `type values and literal mismatches`() {
        val problems = validate(
            "# @type=port\nP=80\n# @type=port\nQ=70000\n# @type=enum(a, b)\nE=a\n# @type=enum()\nF=\n# @type=wat\nW=\n" +
                "# @type=string(startsWith=sk-, nope=1)\nS=\n# @type=string(matches=regex(\"[\"))\nR=\n# @type=number(min=1, max=x)\nN=\n" +
                "# @type=url\nU=\n# @type=ip\nI=anything\n# @type=boolean\nB=\"\"\n",
        )
        assertThat(problems.map { it.code to it.args }).containsExactly(
            Code.TYPE_MISMATCH to listOf("Q", "port"),
            Code.INVALID_TYPE_ARGUMENT to listOf("enum", "values"),
            Code.UNKNOWN_TYPE to listOf("wat"),
            Code.INVALID_TYPE_ARGUMENT to listOf("string", "nope"),
            Code.INVALID_TYPE_ARGUMENT to listOf("string", "matches"),
            Code.INVALID_TYPE_ARGUMENT to listOf("number", "max"),
        )
    }

    @Test
    fun `type checks`() {
        assertThat(accepts("string(startsWith=sk-)", "sk-123")).isTrue()
        assertThat(accepts("string(startsWith=sk-)", "pk-123")).isFalse()
        assertThat(accepts("string(endsWith=.com, minLength=5, maxLength=10)", "a.com")).isTrue()
        assertThat(accepts("string(endsWith=.com, minLength=6)", "a.com")).isFalse()
        assertThat(accepts("string(isLength=3)", "abc")).isTrue()
        assertThat(accepts("string(matches=regex(\"^[a-z]+\$\"))", "abc")).isTrue()
        assertThat(accepts("string(matches=regex(\"^[a-z]+\$\"))", "ABC")).isFalse()
        assertThat(accepts("string(matches=regex(\"^[a-z]+\$\", i))", "ABC")).isTrue()
        assertThat(accepts("string(matches=\"^x\")", "xy")).isTrue()
        assertThat(accepts("string(matches=/^x/i)", "Xy")).isTrue()
        assertThat(accepts("enum(debug, info, \"with space\")", "with space")).isTrue()
        assertThat(accepts("enum(debug, info)", "warn")).isFalse()
        assertThat(accepts("port", "8080")).isTrue()
        assertThat(accepts("port", "-1")).isFalse()
        assertThat(accepts("port", "http")).isFalse()
        assertThat(accepts("number", "1.5")).isTrue()
        assertThat(accepts("number", "NaN")).isFalse()
        assertThat(accepts("number(min=0, max=10, isInt=true)", "11")).isFalse()
        assertThat(accepts("number(isInt=true)", "1.5")).isFalse()
        assertThat(accepts("integer", "3")).isTrue()
        assertThat(accepts("integer", "3.1")).isFalse()
        assertThat(accepts("url", "https://example.com/x")).isTrue()
        assertThat(accepts("url", "postgres://u@db:5432/app")).isTrue()
        assertThat(accepts("url", "localhost:3000")).isFalse()
        assertThat(accepts("url", "not a url")).isFalse()
        assertThat(accepts("boolean", "TRUE")).isTrue()
        assertThat(accepts("boolean", "maybe")).isFalse()
        assertThat(accepts("email", "a@b.cz")).isTrue()
        assertThat(accepts("email", "a@b")).isFalse()
        assertThat(accepts("uuid", "123e4567-e89b-12d3-a456-426614174000")).isTrue()
        assertThat(accepts("uuid", "123")).isFalse()
        assertThat(accepts("timeZone", "whatever")).isTrue()
        assertThat((type("enum(a, b)") as EnvSpecType.Parsed.Ok).type.expected).isEqualTo("enum(a, b)")
        assertThat((type("string(startsWith=sk-)") as EnvSpecType.Parsed.Ok).type.expected).isEqualTo("string(startsWith=sk-)")
        assertThat(type("\$REF")).isEqualTo(EnvSpecType.Parsed.Unknown("\$REF"))
    }

    @Test
    fun `layers merge decorators, later explicit ones win, defaults per declaring file`() {
        val schema = EnvSpecParser.parse(
            "# @defaultRequired=infer @defaultSensitive=inferFromPrefix(PUBLIC_)\n# ---\n" +
                "API_URL=http://default\nOPTIONAL_EMPTY=\nPUBLIC_NAME=x\n# @type=port\nPORT=3000\n# @required=forEnv(prod)\nPROD_ONLY=\n",
        )
        val env = EnvSpecParser.parse("# @optional\nAPI_URL=\n# @public\nSECRET=x\nOTHER=1\n")
        val local = EnvSpecParser.parse("# @required @sensitive @internal\nOTHER=2\n")
        val layers = EnvSpecLayers.of(listOf(schema, env, local), environment = "dev")
        val keys = layers.keys
        assertThat(keys.keys).containsExactly("API_URL", "OPTIONAL_EMPTY", "PUBLIC_NAME", "PORT", "PROD_ONLY", "SECRET", "OTHER")
        assertThat(keys.getValue("API_URL").required).describedAs("@optional in .env wins over infer").isFalse()
        assertThat(keys.getValue("API_URL").sensitive).describedAs("not prefixed: sensitive").isTrue()
        assertThat(keys.getValue("OPTIONAL_EMPTY").required).isFalse()
        assertThat(keys.getValue("PUBLIC_NAME").sensitive).isFalse()
        assertThat(keys.getValue("PORT").required).isTrue()
        assertThat(keys.getValue("PORT").type!!.name).isEqualTo("port")
        assertThat(keys.getValue("PROD_ONLY").required).describedAs("forEnv(prod) in dev").isFalse()
        assertThat(EnvSpecLayers.of(listOf(schema), "prod").keys.getValue("PROD_ONLY").required).isTrue()
        // SECRET is first declared in .env, which has no root defaults: EnvBridge's own (not required, not sensitive)
        assertThat(keys.getValue("SECRET")).isEqualTo(EnvSpecLayers.Key("SECRET", required = false, sensitive = false, internal = false, type = null))
        assertThat(keys.getValue("OTHER")).isEqualTo(EnvSpecLayers.Key("OTHER", required = true, sensitive = true, internal = true, type = null))
        // varlock's reading of unmarked files
        assertThat(EnvSpecLayers.of(listOf(env, local), defaults = EnvSpecLayers.Defaults.VARLOCK).keys.getValue("SECRET").required).isTrue()

        val check = layers.check(mapOf("API_URL" to "", "PORT" to "http", "OTHER" to "secret-other", "PUBLIC_NAME" to "public-name", "SECRET" to "s3cr3t"))
        assertThat(check.missingRequired).isEmpty()
        assertThat(check.typeMismatches).containsExactly("PORT" to "port")
        assertThat(check.sensitiveValues).containsExactly("http", "secret-other")
        assertThat(check.internalKeys).containsExactly("OTHER")
        assertThat(layers.check(mapOf("OTHER" to "x")).missingRequired).describedAs("infer: values in the schema").containsExactly("PUBLIC_NAME", "PORT")
    }

    @Test
    fun `launch check`() {
        val file = EnvSpecParser.parse(
            "# @required\nA=\n# @required @sensitive\nB=\n# @type=port\nC=\n# @internal\nD=\n# @sensitive\nE=\nF=plain\n",
        )
        val check = EnvSpecLayers.of(listOf(file)).check(mapOf("A" to "", "B" to "bee-secret", "C" to "x", "D" to "d", "E" to "", "F" to "plain"))
        assertThat(check.missingRequired).containsExactly("A")
        assertThat(check.typeMismatches).containsExactly("C" to "port")
        assertThat(check.sensitiveValues).containsExactly("bee-secret")
        assertThat(check.internalKeys).containsExactly("D")
        assertThat(check.toString()).doesNotContain("bee-secret")
        assertThat(EnvSpecLayers.of(listOf(EnvSpecParser.parse("A=\n# @docs(x)\nB=\n"))).active).isFalse()
    }

    @Test
    fun `decorator registry`() {
        assertThat(EnvSpecDecorators.suggestion("requried")).isEqualTo("required")
        assertThat(EnvSpecDecorators.suggestion("Sensitive")).isEqualTo("sensitive")
        assertThat(EnvSpecDecorators.suggestion("zzzzzzzz")).isNull()
        assertThat(EnvSpecDecorators.isKnown("initInfisical")).isTrue()
        assertThat(EnvSpecDecorators.isKnown("initiative")).isFalse()
        assertThat(EnvSpecDecorators.ALL).contains("required", "defaultRequired")
        val call = EnvSpecParser.parse("# @type=enum(a, b)\nA=\n").items.single().decorator("type")!!.value as EnvSpecValue.Call
        assertThat(call.positional).hasSize(2)
    }
}
