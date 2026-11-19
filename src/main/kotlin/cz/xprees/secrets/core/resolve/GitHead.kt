package cz.xprees.secrets.core.resolve

import java.nio.file.Files
import java.nio.file.Path

/**
 * The current Git branch of a directory, read straight from `.git/HEAD` (also in worktrees and submodules, where `.git` is
 * a file pointing at the real git directory). No dependency on the Git plugin, so it works in every IDE.
 */
object GitHead {
    /** The branch checked out in the repository containing [dir], or null (no repository, detached HEAD). */
    fun currentBranch(dir: Path): String? {
        var current: Path? = dir.toAbsolutePath().normalize()
        while (current != null) {
            val dotGit = current.resolve(".git")
            val gitDir = when {
                Files.isDirectory(dotGit) -> dotGit
                Files.isRegularFile(dotGit) -> readGitDirFile(dotGit, current)
                else -> null
            }
            if (gitDir != null) return readHead(gitDir)
            current = current.parent
        }
        return null
    }

    private fun readGitDirFile(file: Path, base: Path): Path? {
        val line = runCatching { Files.readAllLines(file).firstOrNull() }.getOrNull() ?: return null
        val target = line.removePrefix("gitdir:").trim().takeIf { line.startsWith("gitdir:") && it.isNotEmpty() } ?: return null
        return base.resolve(target).normalize()
    }

    private fun readHead(gitDir: Path): String? {
        val head = runCatching { Files.readString(gitDir.resolve("HEAD")).trim() }.getOrNull() ?: return null
        return head.removePrefix("ref:").trim().removePrefix("refs/heads/").takeIf { head.startsWith("ref:") && it.isNotEmpty() }
    }
}
