package cz.xprees.secrets

import cz.xprees.secrets.terminal.LoadSecretsIntoTerminalAction
import cz.xprees.secrets.terminal.ShellSyntax
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

class ShellSyntaxTest {
    private val tricky = linkedMapOf(
        "QUOTES" to "it's a \"test\"",
        "MULTILINE" to "line1\nline2",
        "SPECIAL" to "\$HOME `id` \\ %PATH% !x & | ; *",
        "EMPTY" to "",
    )

    @Test
    fun `the shell is decided from its command line`() {
        assertThat(ShellSyntax.detect(listOf("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoExit"), windows = true)).isEqualTo(ShellSyntax.PowerShell)
        assertThat(ShellSyntax.detect(listOf("powershell.exe"), windows = true)).isEqualTo(ShellSyntax.PowerShell)
        assertThat(ShellSyntax.detect(listOf("C:\\Windows\\System32\\cmd.exe"), windows = true)).isEqualTo(ShellSyntax.Cmd)
        assertThat(ShellSyntax.detect(listOf("/opt/homebrew/bin/fish", "-l"), windows = false)).isEqualTo(ShellSyntax.Fish)
        assertThat(ShellSyntax.detect(listOf("/bin/zsh", "--login", "-i"), windows = false)).isInstanceOf(ShellSyntax.Posix::class.java)
        assertThat(ShellSyntax.detect(listOf("/usr/bin/nu"), windows = false)).isNull()
        assertThat(ShellSyntax.detect(null, windows = false)).isNull()
    }

    @Test
    fun `posix shells on windows see windows paths their own way`() {
        val file = Path.of("C:\\Users\\me\\AppData\\Local\\Temp\\envbridge-1.txt")
        fun command(shell: String) = ShellSyntax.detect(listOf(shell), windows = true)!!.loadCommand(file)
        assertThat(command("C:\\Program Files\\Git\\bin\\bash.exe")).contains("'/c/Users/me/AppData/Local/Temp/envbridge-1.txt'")
        assertThat(command("wsl.exe")).contains("'/mnt/c/Users/me/AppData/Local/Temp/envbridge-1.txt'")
        assertThat(command("C:\\Windows\\System32\\bash.exe")).contains("/mnt/c/")
        assertThat(command("C:\\cygwin64\\bin\\bash.exe")).contains("/cygdrive/c/")
    }

    @Test
    fun `powershell gets base64 values and a pipeline that needs no script execution`() {
        val text = ShellSyntax.PowerShell.file(tricky)
        val decoded = text.lines().filter { it.isNotEmpty() }.associate { line ->
            val (name, value) = line.split('=', limit = 2)
            name to String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        }
        assertThat(decoded).isEqualTo(tricky)
        val command = ShellSyntax.PowerShell.loadCommand(Path.of("C:\\Temp\\o'neil.txt"))
        assertThat(command).startsWith("Get-Content -LiteralPath 'C:\\Temp\\o''neil.txt'").endsWith("Remove-Item -LiteralPath 'C:\\Temp\\o''neil.txt'")
        assertThat(command).doesNotContain(".ps1")
    }

    @Test
    fun `cmd doubles percent signs and leaves out values with line breaks`() {
        assertThat(ShellSyntax.Cmd.unsupported(tricky)).containsExactly("MULTILINE")
        val text = ShellSyntax.Cmd.file(tricky)
        assertThat(text).contains("set \"SPECIAL=\$HOME `id` \\ %%PATH%% !x & | ; *\"\r\n").doesNotContain("line1")
        assertThat(ShellSyntax.Cmd.loadCommand(Path.of("C:\\T\\e.cmd"))).isEqualTo("call \"C:\\T\\e.cmd\" & del \"C:\\T\\e.cmd\"")
    }

    @Test
    fun `values never appear in the command typed into the terminal`() {
        val file = Path.of("/tmp/envbridge-x.txt")
        for (syntax in listOf(ShellSyntax.Posix(), ShellSyntax.Fish, ShellSyntax.PowerShell, ShellSyntax.Cmd)) {
            val command = syntax.loadCommand(file)
            tricky.values.filter { it.isNotEmpty() }.forEach { assertThat(command).doesNotContain(it) }
        }
    }

    private fun available(shell: String) = runCatching {
        ProcessBuilder("sh", "-c", "command -v $shell").start().waitFor() == 0
    }.getOrDefault(false)

    /** Loads [tricky] into a real [shell] the way the action does and prints the variables back, NUL-separated. */
    private fun roundTrip(shell: String, syntax: ShellSyntax, args: List<String>, print: String) {
        assumeTrue("$shell is not installed", available(shell))
        val file = LoadSecretsIntoTerminalAction.writeOwnerOnly(syntax.file(tricky), syntax.extension)
        val process = ProcessBuilder(listOf(shell) + args + (syntax.loadCommand(file) + "; " + print)).redirectErrorStream(false).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val errors = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
        assertThat(output.split('\u0000').dropLast(1)).describedAs("$shell: $errors").containsExactlyElementsOf(tricky.values)
        assertThat(Files.exists(file)).describedAs("$shell deletes the file").isFalse()
    }

    private val posixPrint = "printf '%s\\0' \"\$QUOTES\" \"\$MULTILINE\" \"\$SPECIAL\" \"\$EMPTY\""

    @Test
    fun `bash loads every value exactly`() = roundTrip("bash", ShellSyntax.Posix(), listOf("-c"), posixPrint)

    @Test
    fun `sh and dash load every value exactly`() {
        roundTrip("sh", ShellSyntax.Posix(), listOf("-c"), posixPrint)
        roundTrip("dash", ShellSyntax.Posix(), listOf("-c"), posixPrint)
    }

    @Test
    fun `zsh loads every value exactly`() = roundTrip("zsh", ShellSyntax.Posix(), listOf("-c"), posixPrint)

    @Test
    fun `fish loads every value exactly`() = roundTrip("fish", ShellSyntax.Fish, listOf("-c"), posixPrint)

    @Test
    fun `powershell loads every value exactly`() = roundTrip(
        "pwsh", ShellSyntax.PowerShell, listOf("-NoProfile", "-NonInteractive", "-Command"),
        "[Console]::Out.Write(\$env:QUOTES + [char]0 + \$env:MULTILINE + [char]0 + \$env:SPECIAL + [char]0 + \$env:EMPTY + [char]0)",
    )
}
