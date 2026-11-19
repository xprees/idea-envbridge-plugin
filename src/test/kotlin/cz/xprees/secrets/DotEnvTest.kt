package cz.xprees.secrets

import cz.xprees.secrets.core.resolve.DotEnv
import cz.xprees.secrets.terminal.SecretsTerminalCustomizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.Test

class DotEnvTest {
    @Test
    fun `assignments, quotes, comments and export`() {
        val env = DotEnv.parse(
            """
            # comment
            PLAIN=value
            export EXPORTED = spaced
            SINGLE='lit#eral \n'
            DOUBLE="line\nnext \"quoted\""
            INLINE=value # trailing comment
            HASH=a#b
            REF=infisical://prod/DB_PASSWORD
            EMPTY=
            not an assignment
            1BAD=x
            """.trimIndent(),
        )
        assertThat(env).containsExactly(
            entry("PLAIN", "value"),
            entry("EXPORTED", "spaced"),
            entry("SINGLE", "lit#eral \\n"),
            entry("DOUBLE", "line\nnext \"quoted\""),
            entry("INLINE", "value"),
            entry("HASH", "a#b"),
            entry("REF", "infisical://prod/DB_PASSWORD"),
            entry("EMPTY", ""),
        )
    }

    @Test
    fun `double quoted values may span lines`() {
        val env = DotEnv.parse("KEY=\"-----BEGIN\nabc\n-----END\"\nAFTER=1\r\n")
        assertThat(env).containsExactly(entry("KEY", "-----BEGIN\nabc\n-----END"), entry("AFTER", "1"))
    }

    @Test
    fun `terminal references`() {
        val parsed = SecretsTerminalCustomizer.parse("infisical://*\n# comment\n\nDB=infisical://prod/DB_PASSWORD\n")
        assertThat(parsed.values).containsExactly("infisical://*", "infisical://prod/DB_PASSWORD")
        assertThat(parsed).containsEntry("DB", "infisical://prod/DB_PASSWORD")
    }
}
