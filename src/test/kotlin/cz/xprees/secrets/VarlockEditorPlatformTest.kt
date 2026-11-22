package cz.xprees.secrets

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.envspec.EnvSpecDecorators
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.EnvSpecInspection
import cz.xprees.secrets.editor.varlock.VarlockCatalog
import cz.xprees.secrets.editor.varlock.VarlockDocs
import cz.xprees.secrets.editor.varlock.VarlockDocumentationTarget
import cz.xprees.secrets.editor.varlock.VarlockDocumentationTargetProvider
import cz.xprees.secrets.editor.varlock.VarlockGotoDeclarationHandler
import cz.xprees.secrets.editor.varlock.VarlockHighlighting
import cz.xprees.secrets.editor.varlock.VarlockInspection
import cz.xprees.secrets.editor.varlock.VarlockMode
import org.assertj.core.api.Assertions.assertThat

/** The varlock editor support (varlock compatibility mode): highlighting, completion, checks, documentation, navigation, Enter. */
class VarlockEditorPlatformTest : BasePlatformTestCase() {
    private val app get() = SecretsAppSettings.getInstance().state

    override fun setUp() {
        super.setUp()
        app.varlockCompatibility = true
    }

    override fun tearDown() {
        try {
            app.varlockCompatibility = false
        } finally {
            super.tearDown()
        }
    }

    private val schema = """
        # @defaultRequired=infer @defaultSensitive=false
        # @currentEnv=${'$'}APP_ENV
        # @import(./.env.shared, allowMissing=true)
        # ---
        # The environment
        # @type=enum(dev, prod)
        APP_ENV=dev
        # @sensitive @type=string(startsWith=sk-)
        API_KEY=fallback(op(op://vault/api/key), ${'$'}APP_ENV)
        URL="http://${'$'}{APP_ENV}.example.com"
    """.trimIndent() + "\n"

    /** The texts highlighted with [key]. */
    private fun highlighted(infos: List<HighlightInfo>, key: TextAttributesKey): List<String> =
        infos.filter { it.forcedTextAttributesKey == key }.map { it.text }

    fun `test varlock syntax is highlighted on top of the file's language`() {
        myFixture.configureByText(".env.schema", schema)
        val infos = myFixture.doHighlighting()
        assertThat(highlighted(infos, VarlockHighlighting.DECORATOR))
            .containsExactlyInAnyOrder("@defaultRequired", "@defaultSensitive", "@currentEnv", "@import", "@type", "@sensitive", "@type")
        assertThat(highlighted(infos, VarlockHighlighting.FUNCTION)).containsExactlyInAnyOrder("fallback", "op")
        assertThat(highlighted(infos, VarlockHighlighting.REFERENCE)).containsExactlyInAnyOrder("\$APP_ENV", "\$APP_ENV", "\${APP_ENV}")
        assertThat(highlighted(infos, VarlockHighlighting.TYPE)).containsExactlyInAnyOrder("enum", "string")
        assertThat(highlighted(infos, VarlockHighlighting.ARGUMENT)).containsExactlyInAnyOrder("allowMissing", "startsWith")
        assertThat(highlighted(infos, VarlockHighlighting.KEYWORD)).containsExactlyInAnyOrder("infer", "false", "true")
        assertThat(highlighted(infos, VarlockHighlighting.IMPORT)).containsExactly("./.env.shared")
        // Without a DotEnv language the file is plain text: keys and comments are highlighted too
        assertThat(myFixture.file.language.id).isEqualTo("TEXT")
        assertThat(highlighted(infos, VarlockHighlighting.KEY)).containsExactly("APP_ENV", "API_KEY", "URL")
        assertThat(highlighted(infos, VarlockHighlighting.COMMENT)).contains("# ---", "# The environment", "#")
    }

    fun `test decorators are completed by position, with their parentheses or equals sign`() {
        myFixture.configureByText(".env.schema", "# @<caret>\n# ---\nA=1\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("currentEnv", "defaultRequired", "import", "plugin", "initOp", "initInfisical", "initHcpVault")
            .doesNotContain("required", "type", "redactLogs")

        myFixture.configureByText(".env.schema", "# ---\n# @<caret>\nA=1\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("required", "optional", "type", "example", "docs").doesNotContain("currentEnv", "import", "docsUrl")

        myFixture.configureByText(".env.schema", "A=1 # @<caret>\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("required").doesNotContain("import")

        myFixture.configureByText(".env.schema", "# ---\n# @typ<caret>\nA=1\n")
        myFixture.completeBasic()
        myFixture.checkResult("# ---\n# @type=<caret>\nA=1\n")

        myFixture.configureByText(".env.schema", "# @impo<caret>\n# ---\nA=1\n")
        myFixture.completeBasic()
        myFixture.checkResult("# @import(<caret>)\n# ---\nA=1\n")
    }

    fun `test types, type options and decorator values are completed`() {
        myFixture.configureByText(".env.schema", "# @type=<caret>\nA=1\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("string", "enum", "port", "url", "number", "boolean", "ip", "timeZone")

        myFixture.configureByText(".env.schema", "# @type=string(sta<caret>)\nA=1\n")
        myFixture.completeBasic()
        myFixture.checkResult("# @type=string(startsWith=<caret>)\nA=1\n")

        myFixture.configureByText(".env.schema", "# @type=number(min=1, <caret>)\nA=1\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("max=", "isInt=")

        myFixture.configureByText(".env.schema", "# @defaultRequired=<caret>\n# ---\nA=1\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).containsExactlyInAnyOrder("true", "false", "infer")

        myFixture.configureByText(".env.schema", "# @required=forE<caret>\nA=1\n")
        myFixture.completeBasic()
        myFixture.checkResult("# @required=forEnv(<caret>)\nA=1\n")
    }

    fun `test enum values, functions and keys of the layered files are completed`() {
        myFixture.addFileToProject(".env.schema", "# @type=enum(debug, info, warn)\nLEVEL=\nSCHEMA_KEY=1\n")
        myFixture.addFileToProject(".env.local", "LOCAL_KEY=1\n")
        myFixture.configureByText(".env", "LEVEL=<caret>\n")
        myFixture.completeBasic()
        val variants = myFixture.lookupElementStrings!!
        assertThat(variants.take(3)).containsExactlyInAnyOrder("debug", "info", "warn")
        assertThat(variants).contains("fallback", "ref", "op", "infisical", "vaultSecret", "exec")

        myFixture.configureByText(".env", "LEVEL=in<caret>\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("info", "infisical", "infisicalBulk")

        myFixture.configureByText(".env", "A=\$<caret>\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("LEVEL", "SCHEMA_KEY", "LOCAL_KEY", "A")

        myFixture.configureByText(".env", "A=ref(LOC<caret>)\n")
        myFixture.completeBasic()
        myFixture.checkResult("A=ref(LOCAL_KEY<caret>)\n")

        myFixture.configureByText(".env", "A=concat(\"x\", fallb<caret>)\n")
        myFixture.completeBasic()
        myFixture.checkResult("A=concat(\"x\", fallback(<caret>))\n")

        myFixture.configureByText(".env", "A=vaultSec<caret>\n")
        myFixture.completeBasic()
        myFixture.checkResult("A=vaultSecret(<caret>)\n")
    }

    private fun problems(): List<String> {
        myFixture.enableInspections(VarlockInspection::class.java, EnvSpecInspection::class.java)
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }
    }

    fun `test varlock rules are checked`() {
        myFixture.addFileToProject(".env.shared", "SHARED_KEY=1\n")
        myFixture.configureByText(
            ".env.schema",
            """
            # @plugin(@varlock/infisical-plugin)
            # @initInfisical(projectId=p1)
            # @currentEnv=${'$'}MISSING_ENV
            # @import(./.env.missing)
            # @import(./.env.optional, allowMissing=true)
            # @import(./.env.shared)
            # @redactLogs
            # ---
            # @type=enum(dev, prod)
            APP_ENV=dev
            # @docsUrl=https://example.com
            A=frobnicate(1)
            B=concta(a, b)
            C=op(op://v/i/f)
            D=infisical("X")
            E=ref(NOPE)
            F=${'$'}SHARED_KEY
            G="${'$'}{ALSO_NOPE}"
            # @type=string(matches=/^x/)
            H=xyz
            I=exec(echo hi)
            J=fallback(${'$'}APP_ENV, ${'$'}VARLOCK_ENV)
            """.trimIndent() + "\n",
        )
        val problems = problems()
        assertThat(problems).contains(
            "@currentEnv names MISSING_ENV, which no .env file of this directory defines",
            "The imported file ./.env.missing doesn't exist; add allowMissing=true if it is optional",
            "@redactLogs is deprecated in varlock; use @redact",
            "@docsUrl is deprecated in varlock; use @docs",
            "Unknown function frobnicate()",
            "Unknown function concta(); did you mean concat()?",
            "op() comes from the 1Password plugin, which no header loads: add @plugin(…) and @initOp(…) to the header of .env.schema",
            "NOPE is not defined in this file or the other .env files of its directory",
            "ALSO_NOPE is not defined in this file or the other .env files of its directory",
            "A bare /regex/ is deprecated in varlock; use regex(…)",
        )
        val varlock = problems.filter { "varlock" in it || "function" in it || "plugin" in it || "defined" in it || "import" in it }
        assertThat(varlock.joinToString("\n")).doesNotContain(".env.optional", "infisical()", "SHARED_KEY", "exec", "VARLOCK_ENV", "APP_ENV")
        // The general decorator inspection doesn't report these twice
        assertThat(problems.count { "redactLogs" in it }).isEqualTo(1)

        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Change to @redact" })
        assertThat(myFixture.editor.document.text).contains("# @redact\n").doesNotContain("redactLogs")
    }

    fun `test a header that loads an unknown plugin silences unknown functions`() {
        myFixture.configureByText(".env.schema", "# @plugin(@acme/varlock-doppler)\n# ---\nA=doppler(KEY)\n")
        assertThat(problems().filter { "function" in it }).isEmpty()
    }

    fun `test plain values are checked against the types of other layers`() {
        myFixture.addFileToProject(".env.schema", "# @type=enum(dev, prod)\nAPP_ENV=dev\n# @type=port\nPORT=\n")
        myFixture.configureByText(".env", "APP_ENV=staging\nPORT=8080\n")
        assertThat(problems()).containsExactly("The value of APP_ENV doesn't match @type=enum(dev, prod) declared in .env.schema")
    }

    private fun documentation(file: PsiFile, text: String, inside: Int = 2): String? = runReadAction {
        val targets = VarlockDocumentationTargetProvider().documentationTargets(file, file.text.indexOf(text) + inside)
        (targets.singleOrNull() as? VarlockDocumentationTarget)?.html
    }

    fun `test decorators and functions are documented on hover`() {
        val file = myFixture.configureByText(".env.schema", "# @currentEnv=\$APP_ENV\n# ---\n# @required\nAPP_ENV=fallback(ref(X), op(op://v/i/f))\n")
        assertThat(documentation(file, "@required")).contains("The item must have a non-empty value").contains("https://varlock.dev/reference/item-decorators/#required")
        assertThat(documentation(file, "@currentEnv")).contains("Names the item that holds the current environment").contains("root decorator")
        assertThat(documentation(file, "fallback")).contains("fallback(a, b, …, default)").contains("The first argument that has a value")
        assertThat(documentation(file, "ref(")).contains("the same as \$KEY and \${KEY}")
        assertThat(documentation(file, "op(")).contains("1Password plugin").contains("@initOp")
        assertThat(documentation(file, "APP_ENV=")).isNull()
        val target = runReadAction { VarlockDocumentationTargetProvider().documentationTargets(file, file.text.indexOf("@required") + 1).single() }
        assertThat(target.computeDocumentation()).isNotNull()
        assertThat(target.computePresentation().presentableText).isEqualTo("@required")
        // Every decorator and function has a text
        for (name in EnvSpecDecorators.ROOT + EnvSpecDecorators.ITEM + VarlockCatalog.PLUGIN_ROOT_DECORATORS) {
            assertThat(VarlockDocs.decorator(name)).describedAs(name).isNotNull().doesNotContain("varlock.doc")
        }
        for (function in VarlockCatalog.FUNCTIONS) {
            assertThat(VarlockDocs.function(function.name)).describedAs(function.name).isNotNull().doesNotContain("varlock.doc")
        }
        assertThat(VarlockDocs.decorator("redactLogs")).contains("@redactLogs is deprecated in varlock; use @redact")
    }

    fun `test references and imports lead to their definitions`() {
        myFixture.addFileToProject(".env.other", "OTHER=1\n")
        myFixture.addFileToProject(".env.local", "A=2\n")
        val file = myFixture.configureByText(".env.schema", "# @import(./.env.other)\n# ---\nA=1\nB=\$A\nC=ref(OTHER)\n")
        val text = file.text
        val handler = VarlockGotoDeclarationHandler()

        val toA = handler.getGotoDeclarationTargets(file.findElementAt(text.indexOf("\$A") + 1), text.indexOf("\$A") + 1, myFixture.editor)!!
        assertThat(toA.map { it.containingFile.name to it.textOffset }).containsExactly(".env.schema" to text.indexOf("A=1"), ".env.local" to 0)

        val toOther = handler.getGotoDeclarationTargets(file.findElementAt(text.indexOf("OTHER)")), text.indexOf("OTHER)"), myFixture.editor)!!
        assertThat(toOther.map { it.containingFile.name to it.textOffset }).containsExactly(".env.other" to 0)

        val toFile = handler.getGotoDeclarationTargets(file.findElementAt(text.indexOf(".env.other")), text.indexOf(".env.other"), myFixture.editor)!!
        assertThat((toFile.single() as PsiFile).name).isEqualTo(".env.other")

        assertThat(handler.getGotoDeclarationTargets(file.findElementAt(text.indexOf("B=")), text.indexOf("B="), myFixture.editor)).isNull()
    }

    fun `test Enter continues a comment`() {
        myFixture.configureByText(".env.schema", "# @required<caret>\nA=1\n")
        myFixture.type('\n')
        myFixture.checkResult("# @required\n# <caret>\nA=1\n")
        myFixture.configureByText(".env.schema", "  # A description<caret>\nA=1\n")
        myFixture.type('\n')
        myFixture.checkResult("  # A description\n  # <caret>\nA=1\n")
        // Not after a divider, an empty comment or a value
        myFixture.configureByText(".env.schema", "# ---<caret>\nA=1\n")
        myFixture.type('\n')
        myFixture.checkResult("# ---\n<caret>\nA=1\n")
        myFixture.configureByText(".env.schema", "A=1<caret>\n")
        myFixture.type('\n')
        myFixture.checkResult("A=1\n<caret>\n")
    }

    fun `test the mode applies to schemas, their directories and files using decorators`() {
        assertThat(VarlockMode.isActive(myFixture.addFileToProject("a/.env.schema", "A=1\n"))).isTrue()
        assertThat(VarlockMode.isActive(myFixture.addFileToProject("b/x.env", "# @required\nA=1\n"))).isTrue()
        assertThat(VarlockMode.isActive(myFixture.addFileToProject("c/y.env", "A=op(op://v/i/f)\n"))).isTrue()
        assertThat(VarlockMode.isActive(myFixture.addFileToProject("d/notes.txt", "# @required\nA=1\n"))).isFalse()
        val plain = myFixture.addFileToProject("sub/.env", "A=1\n# mail me @ home\n")
        assertThat(VarlockMode.isActive(plain)).isFalse()
        myFixture.addFileToProject("sub/.env.schema", "A=\n")
        assertThat(VarlockMode.isActive(plain)).isTrue()
    }

    fun `test nothing varlock-specific happens with the mode off`() {
        app.varlockCompatibility = false
        val file = myFixture.configureByText(".env.schema", schema + "B=frobnicate(\$NOPE)\n# @redactLogs\nC=1\n")
        assertThat(VarlockMode.isActive(file)).isFalse()
        val infos = myFixture.doHighlighting()
        val keys = setOf(
            VarlockHighlighting.DECORATOR, VarlockHighlighting.FUNCTION, VarlockHighlighting.REFERENCE, VarlockHighlighting.TYPE,
            VarlockHighlighting.KEY, VarlockHighlighting.COMMENT,
        )
        assertThat(infos.filter { it.forcedTextAttributesKey in keys }).isEmpty()
        assertThat(problems().filter { "varlock" in it || "function" in it || "defined" in it }).isEmpty()
        assertThat(runReadAction { VarlockDocumentationTargetProvider().documentationTargets(file, file.text.indexOf("@sensitive") + 1) }).isEmpty()
        assertThat(VarlockGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(0), file.text.indexOf("\$APP_ENV") + 1, myFixture.editor)).isNull()

        // Decorator names as before: every one, anywhere, nothing inserted after them
        myFixture.configureByText(".env.schema", "# @<caret>\n# ---\nA=1\n")
        myFixture.completeBasic()
        assertThat(myFixture.lookupElementStrings).contains("required", "currentEnv").doesNotContain("initOp")
        myFixture.configureByText(".env.schema", "# @type=po<caret>\nA=1\n")
        assertThat(myFixture.completeBasic()?.map { it.lookupString }.orEmpty()).doesNotContain("port")
        myFixture.configureByText(".env.schema", "# @required<caret>\nA=1\n")
        myFixture.type('\n')
        myFixture.checkResult("# @required\n<caret>\nA=1\n")
    }
}
