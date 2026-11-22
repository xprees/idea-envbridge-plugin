package cz.xprees.secrets.vault

import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind
import cz.xprees.secrets.core.reference.ReferenceSyntax
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a connection keeps its environments inside a mount's paths (option `environmentLayout`): `/`-separated segments, each
 * [ENV] (exactly once), [PROJECT] (at most once) or literal text. `{project}/{env}` maps `secret/myapp/dev/db` to project
 * `secret/myapp`, environment `dev`, folder `/db`. No layout (blank) keeps the original mapping: environment = namespace.
 * See `docs/DESIGN.md`, "Environment layout".
 */
class VaultEnvironmentLayout private constructor(val template: String, val segments: List<String>) {
    /** Whether projects have a name of their own (else a project is a mount). */
    val hasProject: Boolean get() = PROJECT in segments

    /** The layout project of [project] (null without [PROJECT]) in [mount]. */
    fun project(mount: String, project: String?): VaultLayoutProject =
        VaultLayoutProject(mount, segments.map { if (it == PROJECT) requireNotNull(project) else it })

    /**
     * The layout project, environment and folder of [path] (from the mount's root) in [mount] (`/myapp/dev/db` ->
     * `secret/myapp/{env}`, `dev`, `/db`), or null when the path doesn't follow the layout.
     */
    fun locate(mount: String, path: String): Triple<VaultLayoutProject, String, String>? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < segments.size) return null
        var project: String? = null
        var environment: String? = null
        for ((index, segment) in segments.withIndex()) {
            val part = parts[index]
            when (segment) {
                PROJECT -> project = part
                ENV -> environment = part
                else -> if (part != segment) return null
            }
        }
        if (environment == null || !VaultLayoutProject.isEnvironmentName(environment)) return null
        if (hasProject && (project == null || !VaultLayoutProject.isEnvironmentName(project))) return null
        return Triple(project(mount, project), environment, parts.drop(segments.size).joinToString("/", prefix = "/"))
    }

    override fun toString(): String = template

    companion object {
        const val PROJECT = "{project}"
        const val ENV = "{env}"

        /** The problem with [template] as a sentence, or null when it is valid (blank: no layout). */
        fun problem(template: String?): String? = runCatching { parse(template) }.exceptionOrNull()?.message

        /** The layout of [template], null when blank; IllegalArgumentException with a readable message when invalid. */
        fun parse(template: String?): VaultEnvironmentLayout? {
            val t = template?.trim()?.trim('/').orEmpty()
            if (t.isEmpty()) return null
            val segments = t.split('/')
            require(segments.none { it.isEmpty() }) { "The environment layout can't contain //" }
            require(segments.count { it == ENV } == 1) { "The environment layout must contain {env} exactly once, as a whole segment" }
            require(segments.count { it == PROJECT } <= 1) { "The environment layout may contain {project} at most once" }
            for (literal in segments.filter { it != ENV && it != PROJECT }) {
                require('{' !in literal && '}' !in literal) {
                    "Only {project} and {env} can be used in the environment layout, each as a whole segment (as in {project}/{env})"
                }
                require(literal.none { it.isWhitespace() || it == '#' || it == '?' }) { "The environment layout can't contain whitespace, # or ?" }
                require(literal != "." && literal != "..") { "The environment layout can't contain . or .." }
            }
            return VaultEnvironmentLayout(segments.joinToString("/"), segments)
        }

        /** The layout of [connection], null when it has none; CONFIGURATION when it is invalid. */
        fun of(connection: SecretConnection): VaultEnvironmentLayout? = try {
            parse(connection.option(VaultAuthMethods.OPTION_ENVIRONMENT_LAYOUT))
        } catch (e: IllegalArgumentException) {
            throw SecretProviderException(Kind.CONFIGURATION, "${e.message}; check the connection '${connection.name}'")
        }

        /** Whether [connection] has an environment layout (an invalid one counts: using it reports the problem). */
        fun isSet(connection: SecretConnection): Boolean = !connection.option(VaultAuthMethods.OPTION_ENVIRONMENT_LAYOUT).isNullOrBlank()
    }
}

/**
 * A project of an environment layout: a [mount] and the path of its environments inside it, [segments] with exactly one
 * [VaultEnvironmentLayout.ENV] (`myapp/{env}`). Its [id] (`secret/myapp/{env}`; a mount with `/` written `%2F`) can't be a
 * mount's, so the client tells layout scopes from the mount scopes full references read.
 */
data class VaultLayoutProject(val mount: String, val segments: List<String>) {
    val id: String get() = (listOf(ReferenceSyntax.encodeSegment(mount)) + segments).joinToString("/")

    /** The path (from the mount) of [environment]'s root: `/myapp/dev`. CONFIGURATION when [environment] can't be a path segment. */
    fun basePath(environment: String): String {
        if (!isEnvironmentName(environment)) {
            throw SecretProviderException(Kind.CONFIGURATION, "'$environment' can't be an environment of $mount: it must be one path segment, without /")
        }
        return segments.joinToString("/", prefix = "/") { if (it == VaultEnvironmentLayout.ENV) environment else it }
    }

    companion object {
        /** The layout project of [projectId], or null when it is a mount (no layout). */
        fun parse(projectId: String): VaultLayoutProject? {
            val segments = projectId.split('/')
            if (segments.size < 2 || segments.drop(1).count { it == VaultEnvironmentLayout.ENV } != 1) return null
            if (segments.any { it.isEmpty() }) return null
            return VaultLayoutProject(ReferenceSyntax.decodeSegment(segments.first()), segments.drop(1))
        }

        /** Whether [projectId] names a layout project. */
        fun isLayoutId(projectId: String?): Boolean = projectId != null && parse(projectId) != null

        /** Whether [name] can be an environment (one path segment). */
        fun isEnvironmentName(name: String): Boolean =
            name.isNotBlank() && name == name.trim() && '/' !in name && name != "." && name != ".." && name != VaultEnvironmentLayout.ENV
    }
}

/**
 * Environments created in the IDE that hold no secret yet (with a layout, a Vault path exists only once something is stored
 * there): kept in memory per connection and project until a listing finds them, sign-out, or the IDE restarts.
 */
object VaultNewEnvironments {
    private val created = ConcurrentHashMap<String, MutableSet<String>>()

    /** Remembers [environment] of [projectId]. */
    fun add(connection: SecretConnection, projectId: String, environment: String) {
        created.computeIfAbsent(key(connection, projectId)) { ConcurrentHashMap.newKeySet() } += environment
    }

    /** [listed] plus the environments created for [projectId] that aren't listed; forgets those that are. */
    fun merge(connection: SecretConnection, projectId: String, listed: List<ProviderEnvironment>): List<ProviderEnvironment> {
        val pending = created[key(connection, projectId)] ?: return listed
        val slugs = listed.map { it.slug }.toSet()
        pending.removeAll(slugs)
        return listed + pending.sorted().map { ProviderEnvironment(slug = it, name = it) }
    }

    /** Forgets every environment remembered for [connection]. */
    fun forget(connection: SecretConnection) {
        created.keys.removeIf { it.startsWith(prefix(connection)) }
    }

    private fun prefix(connection: SecretConnection) = "${connection.id}\u0000${connection.baseUrl}\u0000"

    private fun key(connection: SecretConnection, projectId: String) = prefix(connection) + projectId
}
