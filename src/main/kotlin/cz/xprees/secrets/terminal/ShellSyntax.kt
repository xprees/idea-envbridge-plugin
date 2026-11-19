package cz.xprees.secrets.terminal

import java.nio.file.Path
import java.util.Base64

/**
 * How to put environment variables into an interactive shell that is already running, decided from the shell's command line:
 * PowerShell, cmd, POSIX shells (bash, zsh, sh, …, also Git Bash and WSL on Windows) and fish.
 *
 * Values never go through the terminal: they are written to a short-lived, owner-only file in the shell's own syntax, and the
 * shell is sent one command that loads that file and deletes it. So they show up neither on screen nor in the shell history.
 */
sealed class ShellSyntax(val displayName: String) {
    /** The file content that sets [variables]. */
    abstract fun file(variables: Map<String, String>): String

    /** The command that loads the file at [hostPath] (a path of the IDE's machine) and deletes it. */
    abstract fun loadCommand(hostPath: Path): String

    /** Extension of the file (cmd only runs `.cmd` files). */
    open val extension: String = ".txt"

    /** Variables this shell can't hold (cmd: values with line breaks), left out with a warning. */
    open fun unsupported(variables: Map<String, String>): Set<String> = emptySet()

    /** bash, zsh, sh, dash, ksh, ash; [pathStyle] maps a Windows path for Git Bash, Cygwin or WSL. */
    class Posix(private val pathStyle: PathStyle = PathStyle.NATIVE) : ShellSyntax("POSIX shell") {
        override fun file(variables: Map<String, String>) =
            variables.entries.joinToString("") { (k, v) -> "export $k=${single(v)}\n" }

        // The leading space keeps the line out of the history where the shell ignores such lines
        override fun loadCommand(hostPath: Path): String {
            val path = single(pathStyle.map(hostPath))
            return " . $path; rm -f $path"
        }

        private fun single(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }

    data object Fish : ShellSyntax("fish") {
        override fun file(variables: Map<String, String>) =
            variables.entries.joinToString("") { (k, v) -> "set -gx $k ${quote(v)}\n" }

        override fun loadCommand(hostPath: Path): String {
            val path = quote(hostPath.toString())
            return " source $path; rm -f $path"
        }

        private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

    /**
     * Windows PowerShell and PowerShell 7. The file holds `NAME=base64(value)` lines, read by a pipeline typed at the prompt,
     * so no script has to run (execution policies don't matter) and any value survives.
     */
    data object PowerShell : ShellSyntax("PowerShell") {
        override fun file(variables: Map<String, String>) = variables.entries.joinToString("") { (k, v) ->
            "$k=${Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))}\n"
        }

        override fun loadCommand(hostPath: Path): String {
            val path = "'" + hostPath.toString().replace("'", "''") + "'"
            return "Get-Content -LiteralPath $path | ForEach-Object { \$n, \$v = \$_ -split '=', 2; " +
                "Set-Item -LiteralPath \"env:\$n\" -Value ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(\$v))) }; " +
                "Remove-Item -LiteralPath $path"
        }
    }

    /** cmd.exe: a `.cmd` file of `set "NAME=value"` lines (`%` doubled), then `call` and `del`. */
    data object Cmd : ShellSyntax("Command Prompt") {
        override val extension = ".cmd"

        override fun unsupported(variables: Map<String, String>) =
            variables.filterValues { it.contains('\n') || it.contains('\r') }.keys

        override fun file(variables: Map<String, String>) = "@echo off\r\n" + variables.entries
            .filter { (_, v) -> !v.contains('\n') && !v.contains('\r') }
            .joinToString("") { (k, v) -> "set \"$k=${v.replace("%", "%%")}\"\r\n" }

        override fun loadCommand(hostPath: Path) = "call \"$hostPath\" & del \"$hostPath\""
    }

    /** How a POSIX shell on Windows sees a Windows path. */
    enum class PathStyle {
        NATIVE, MSYS, CYGWIN, WSL;

        fun map(path: Path): String {
            val text = path.toString()
            val drive = Regex("""^([A-Za-z]):[\\/](.*)$""").find(text) ?: return text
            val letter = drive.groupValues[1].lowercase()
            val rest = drive.groupValues[2].replace('\\', '/')
            return when (this) {
                NATIVE -> text
                MSYS -> "/$letter/$rest"
                CYGWIN -> "/cygdrive/$letter/$rest"
                WSL -> "/mnt/$letter/$rest"
            }
        }
    }

    companion object {
        /** The syntax for a shell started with [command] (e.g. `[pwsh.exe, -NoExit]`), or null for a shell it doesn't know. */
        fun detect(command: List<String>?, windows: Boolean): ShellSyntax? {
            val executable = command?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val name = executable.replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe")
            val lowerPath = executable.lowercase()
            return when (name) {
                "pwsh", "powershell", "pwsh-preview" -> PowerShell
                "cmd" -> Cmd
                "fish" -> Fish
                "wsl", "ubuntu", "debian", "kali" -> Posix(PathStyle.WSL)
                "bash", "zsh", "sh", "dash", "ksh", "mksh", "ash", "busybox" -> Posix(
                    when {
                        !windows -> PathStyle.NATIVE
                        // bash.exe in System32 is the WSL launcher
                        lowerPath.contains("system32") -> PathStyle.WSL
                        lowerPath.contains("cygwin") -> PathStyle.CYGWIN
                        else -> PathStyle.MSYS
                    },
                )

                else -> null
            }
        }
    }
}
