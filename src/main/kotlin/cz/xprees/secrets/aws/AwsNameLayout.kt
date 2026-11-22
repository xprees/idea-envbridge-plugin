package cz.xprees.secrets.aws

import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretProviderException.Kind

/**
 * How a connection's AWS secret names map to EnvBridge projects and environments: a template with the placeholders
 * `{project}` and `{env}`, each exactly once, and literal text around and between them (`{project}/{env}`, `{env}/{project}`,
 * `acme/{env}/{project}`, `{project}-{env}`).
 *
 * Rules (see `docs/DESIGN.md`, "Name layout"):
 * - The literal parts are matched exactly; they may only use the characters AWS allows in names, and the two placeholders
 *   must be separated by a non-empty literal (`{project}{env}` can't be split).
 * - `{env}` is never empty and never contains `/`; `{project}` is never empty and may span several `/` segments.
 * - Where a name could be split more than one way, `{project}` takes as much as it can and `{env}` as little: in
 *   `{project}/{env}` the environment is the last segment, in `{env}/{project}` the first, in `{project}-{env}` the text after
 *   the last `-`.
 * - Names that don't match are not shown (counted, see [AwsClient]). Names ending with `/` or containing `//` never match.
 *
 * The default `{project}/{env}` keeps the original mapping exactly, including the project *(no prefix)* for names without `/`
 * ([AwsSecretNames]).
 */
class AwsNameLayout private constructor(
    val template: String,
    private val leading: String,
    private val middle: String,
    private val trailing: String,
    private val projectFirst: Boolean,
) {
    val isDefault: Boolean get() = template == DEFAULT

    /** The literal text every matching name starts with (`acme/` for `acme/{env}/{project}`), usable as a ListSecrets prefix filter. */
    val fixedPrefix: String get() = leading

    private val regex: Regex = run {
        val project = "(.+)"
        val env = "([^/]+?)"
        val (first, second) = if (projectFirst) project to env else env to project
        Regex("^" + Regex.escape(leading) + first + Regex.escape(middle) + second + Regex.escape(trailing) + "$")
    }

    /** (project, environment) of [name], or null when the name doesn't follow the layout. */
    fun split(name: String): Pair<String, String>? {
        if (isDefault) return AwsSecretNames.split(name)
        if (name.isBlank() || name.endsWith('/') || "//" in name) return null
        val match = regex.matchEntire(name) ?: return null
        val (a, b) = match.destructured
        val (project, environment) = if (projectFirst) a to b else b to a
        if (project.isEmpty() || environment.isEmpty() || '/' in environment) return null
        return project to environment
    }

    /** The secret name of [projectId] and [environment]; CONFIGURATION when they can't be put into this layout. */
    fun join(projectId: String, environment: String): String {
        if (isDefault) return AwsSecretNames.join(projectId, environment)
        if (projectId == AwsSecretNames.NO_PREFIX || projectId.isEmpty() || environment.isEmpty() || '/' in environment) {
            throw SecretProviderException(Kind.CONFIGURATION, "Project '$projectId' and environment '$environment' don't fit the name layout $template")
        }
        val (first, second) = if (projectFirst) projectId to environment else environment to projectId
        return leading + first + middle + second + trailing
    }

    /**
     * [join], checked to split back into the same project and environment, for names EnvBridge creates: under
     * `{project}-{env}`, environment `a-b` would be read back as part of the project.
     */
    fun joinForCreate(projectId: String, environment: String): String {
        val name = join(projectId, environment)
        if (split(name) != projectId to environment) {
            throw SecretProviderException(
                Kind.CONFIGURATION,
                "'$name' would not be read back as project '$projectId', environment '$environment' under the name layout $template; choose another name",
            )
        }
        return name
    }

    override fun toString(): String = template

    companion object {
        const val DEFAULT = "{project}/{env}"
        private const val PROJECT = "{project}"
        private const val ENV = "{env}"

        private val DEFAULT_LAYOUT = AwsNameLayout(DEFAULT, "", "/", "", projectFirst = true)

        /** The problem with [template] as a user-readable sentence, or null when it is a valid layout (blank: the default). */
        fun problem(template: String?): String? = runCatching { parse(template) }.exceptionOrNull()?.message

        /** The layout of [template] (blank: the default); IllegalArgumentException with a readable message when it is invalid. */
        fun parse(template: String?): AwsNameLayout {
            val t = template?.trim().orEmpty()
            if (t.isEmpty() || t == DEFAULT) return DEFAULT_LAYOUT
            require(count(t, PROJECT) == 1 && count(t, ENV) == 1) { "The name layout must contain {project} and {env}, each exactly once" }
            val project = t.indexOf(PROJECT)
            val env = t.indexOf(ENV)
            val projectFirst = project < env
            val firstEnd = if (projectFirst) project + PROJECT.length else env + ENV.length
            val secondStart = if (projectFirst) env else project
            val secondEnd = secondStart + if (projectFirst) ENV.length else PROJECT.length
            val leading = t.substring(0, minOf(project, env))
            val middle = t.substring(firstEnd, secondStart)
            val trailing = t.substring(secondEnd)
            for (literal in listOf(leading, middle, trailing)) {
                require('{' !in literal && '}' !in literal) { "Only {project} and {env} can be used in the name layout" }
                require(literal.all(AwsSecretNames::isNameChar)) {
                    "The name layout may only contain letters, digits and ${AwsSecretNames.NAME_PUNCTUATION} besides {project} and {env}"
                }
            }
            require(middle.isNotEmpty()) { "Put a separator such as / between {project} and {env} in the name layout" }
            require("//" !in t && !t.endsWith("/")) { "The name layout can't contain // or end with /" }
            return AwsNameLayout(t, leading, middle, trailing, projectFirst)
        }

        /** The layout of [connection] (option `nameLayout`); CONFIGURATION when it is invalid. */
        fun of(connection: SecretConnection): AwsNameLayout = try {
            parse(connection.option(AwsAuthMethods.OPTION_NAME_LAYOUT))
        } catch (e: IllegalArgumentException) {
            throw SecretProviderException(Kind.CONFIGURATION, "${e.message}; check the connection '${connection.name}'")
        }

        /**
         * The ListSecrets name filter for [layout] and the connection's [namePrefix]: the longer of the two when one starts with
         * the other, empty when there is neither, null when they exclude each other (no name can match both).
         */
        fun serverPrefix(layout: AwsNameLayout, namePrefix: String?): String? {
            val fixed = layout.fixedPrefix
            val prefix = namePrefix.orEmpty()
            return when {
                fixed.startsWith(prefix) -> fixed
                prefix.startsWith(fixed) -> prefix
                else -> null
            }
        }

        private fun count(text: String, part: String): Int = text.windowed(part.length).count { it == part }
    }
}
