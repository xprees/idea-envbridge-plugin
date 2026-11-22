package cz.xprees.secrets.run

import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.core.envspec.EnvSpecDecorator
import cz.xprees.secrets.core.envspec.EnvSpecFile
import cz.xprees.secrets.core.envspec.EnvSpecLayers
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecValue
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.DotEnv
import cz.xprees.secrets.core.resolve.LaunchSecretResolver
import cz.xprees.secrets.core.resolve.SecretBindingService
import cz.xprees.secrets.core.settings.ProjectSecretsSettings
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.core.varlock.DEFAULT_INSTANCE
import cz.xprees.secrets.core.varlock.VarlockCompiler
import cz.xprees.secrets.core.varlock.VarlockEvaluator
import cz.xprees.secrets.core.varlock.VarlockException
import cz.xprees.secrets.core.varlock.VarlockExpr
import cz.xprees.secrets.core.varlock.VarlockProblem
import cz.xprees.secrets.core.varlock.VarlockProblem.Code
import cz.xprees.secrets.core.varlock.VarlockProvider
import cz.xprees.secrets.core.varlock.VarlockSecrets
import cz.xprees.secrets.core.varlock.VarlockValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * `.env` files may hold references instead of secrets, e.g. a committed `.env` with `DB_PASSWORD=infisical://DB_PASSWORD`.
 *
 * - **Attached files** (a configuration's *Environment file(s)* option, or the *Environment file* field of Node.js and npm):
 *   when one holds references, the plugin reads the files itself, resolves the references, puts all their variables into the
 *   copy of the configuration and detaches the files from the copy, so the IDE doesn't load the unresolved values again.
 * - **The program's own `.env`** (Node.js tools such as Nuxt, Vite and Next.js read `.env` and `.env.local` from their
 *   directory by themselves, see [EnvPatcher.dotenvDirectory]): only its references are resolved and passed as process
 *   variables, which those tools keep over the file. Its other lines stay the program's business.
 * - **Layered files** (setting *Layered .env files*, off by default): `.env.schema` < `.env` < `.env.local` < `.env.[env]` <
 *   `.env.[env].local`, `[env]` being the launch's environment ([layerEnvironment]). They replace the program's own pair, and an
 *   attached file named `.env` brings its layered siblings along. EnvBridge then reads them itself and passes **all** their
 *   variables (an attached file is detached from the copy). `.env.schema` contributes only static values (defaults); its
 *   undefined items (`KEY=`) and function calls are not passed on.
 * - **env-spec decorators** (setting on by default, see [EnvSpecLayers]): the decorators of all these files (and of a
 *   `.env.schema` next to them, layered or not) are merged into [Prepared.spec] for the launch checks.
 * - **varlock compatibility mode** (setting `varlockCompatibility`, off by default): the files are always layered, the
 *   environment may come from `@currentEnv`, `@import(…)` adds files of the project, and item values are read as varlock
 *   values: function values and `${…}` interpolation go to [Prepared.varlock] and are resolved by [VarlockLaunch]; plain values
 *   are passed as they are. See [prepareVarlock].
 *
 * Values set on the configuration win over values from files.
 */
object EnvFileReferences {
    private const val MAX_BYTES = 256 * 1024L

    /** The files Node.js tools read from their directory, in the order they apply (later ones win). */
    val DOTENV_NAMES = listOf(".env", ".env.local")

    /** The lowest layer: defaults and decorators (varlock's schema file). */
    const val SCHEMA_NAME = ".env.schema"

    private val SAFE_ENVIRONMENT = Regex("""[A-Za-z0-9_][A-Za-z0-9_\-]*(?:\.[A-Za-z0-9_\-]+)*""")

    /** The layered file names, lowest precedence first; without an environment only the first three. */
    fun layerNames(environment: String?): List<String> {
        val env = environment?.takeIf { SAFE_ENVIRONMENT.matches(it) && it != "local" && it != "schema" }
        return listOfNotNull(SCHEMA_NAME, ".env", ".env.local", env?.let { ".env.$it" }, env?.let { ".env.$it.local" })
    }

    private data class Cached(val modified: Long, val size: Long, val variables: Map<String, String>, val spec: EnvSpecFile, val text: String)

    private val cache = ConcurrentHashMap<Path, Cached>()

    private val settings get() = SecretsAppSettings.getInstance().state

    /**
     * The environment whose `.env.[env]` files a launch reads: [override] ("Run with environment…"), `ENVBRIDGE_ENVIRONMENT` on
     * the configuration, else the environment the bound provider's binding selects (status bar, project file, default). Null
     * when none can be worked out or the name isn't a plain file name part.
     */
    fun layerEnvironment(profile: RunProfile, configured: Map<String, String>, override: String? = null): String? {
        val explicit = override?.takeIf { it.isNotBlank() } ?: SecretBindingService.environmentOverride(configured)
        val project = (profile as? RunConfiguration)?.project ?: return explicit
        val bindings = SecretBindingService.getInstance(project)
        val environment = runCatching { bindings.boundProvider()?.let { bindings.binding(it, explicit).environment } }.getOrNull()
            ?: explicit ?: ProjectSecretsSettings.getInstance(project).state.environment
        return environment?.takeIf { SAFE_ENVIRONMENT.matches(it) }
    }

    /**
     * The env files attached to [profile], resolved against the project directory; empty when it has none. With
     * [layeredFor] (the environment of layered files, or "" for layering without one), an attached `.env` brings its layered
     * siblings along.
     */
    fun files(profile: RunProfile, layeredFor: String? = null): List<Path> {
        if (profile !is RunConfiguration) return emptyList()
        val options = (profile as? EnvFilesOptions)?.envFilePaths.orEmpty()
        val own = EnvPatcher.EP_NAME.extensionList.firstOrNull { runCatching { it.read(profile) }.getOrNull() != null }
            ?.let { runCatching { it.attachedEnvFiles(profile) }.getOrNull() }.orEmpty()
        val attached = (options + own).filter { it.isNotBlank() }.mapNotNull { expand(profile, it) }.distinct()
        if (layeredFor == null) return attached
        return attached.flatMap { file ->
            if (file.fileName?.toString() != ".env") return@flatMap listOf(file)
            layerNames(layeredFor.ifEmpty { null }).map { file.resolveSibling(it) }.filter { it == file || Files.isRegularFile(it) }
        }.distinct()
    }

    /**
     * The `.env` files the program of [profile] reads by itself (existing ones that aren't also attached); with [layeredFor],
     * the layered files of that directory instead.
     */
    fun dotenvFiles(profile: RunProfile, layeredFor: String? = null): List<Path> {
        val directory = dotenvDirectory(profile) ?: return emptyList()
        val attached = files(profile, layeredFor).toSet()
        val names = if (layeredFor != null) layerNames(layeredFor.ifEmpty { null }) else DOTENV_NAMES
        return names.map { directory.resolve(it).normalize() }.filter { it !in attached && Files.isRegularFile(it) }
    }

    private fun dotenvDirectory(profile: RunProfile): Path? {
        if (profile !is RunConfiguration) return null
        val (patcher, _) = EnvPatcher.find(profile) ?: return null
        return runCatching { patcher.dotenvDirectory(profile) }.getOrNull()?.let { expand(profile, it) }
    }

    /** Variables of the files, in order (later files win), read with a small modification-time cache. */
    fun variables(files: List<Path>): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (file in files) result.putAll(read(file)?.variables.orEmpty())
        return result
    }

    /** Whether any env file of [profile] (attached or read by the program) contains a secret reference. Cheap enough for `canRun`. */
    fun hasReferences(profile: RunProfile, resolver: LaunchSecretResolver): Boolean {
        val layered = layeredFor(profile, EnvPatcher.find(profile)?.second.orEmpty(), null)
        val files = files(profile, layered) + dotenvFiles(profile, layered)
        return files.isNotEmpty() && resolver.hasReferences(variables(files))
    }

    /**
     * Whether the env files of [profile] use env-spec decorators EnvBridge acts on (and the setting is on): such launches go
     * through EnvBridge for the checks even without references. Cheap enough for `canRun` (parsed files are cached).
     */
    fun usesDecorators(profile: RunProfile): Boolean {
        if (!settings.envSpecDecorators) return false
        val layered = layeredFor(profile, EnvPatcher.find(profile)?.second.orEmpty(), null)
        return specFiles(profile, layered).any { file -> read(file)?.spec?.let(EnvSpecLayers::usesNative) == true }
    }

    /**
     * What a launch of [profile] starts from.
     *
     * @property envs [configured], under the references of the program's own `.env` files (all their variables when layered)
     *   and, when the attached files are used, all variables of the attached files
     * @property detach whether the attached files are used (and must be detached from the copy): they hold references,
     *   declare `@internal` keys, or layering added files to them
     * @property spec the merged decorators ([EnvSpecLayers.NONE] when off or unused)
     * @property fileValues every variable of every file read (references unresolved), for the checks
     * @property environment the environment used for the layered files and `forEnv(…)`, if any
     * @property varlock the varlock values to resolve after the references (varlock compatibility mode), or null
     */
    class Prepared(
        val envs: Map<String, String>,
        val detach: Boolean,
        val spec: EnvSpecLayers,
        val fileValues: Map<String, String>,
        val environment: String?,
        val varlock: VarlockPlan? = null,
    ) {
        /**
         * Checks the launch's final environment: the files' values overlaid with [resolved] (resolved references and values set
         * on the configuration).
         */
        fun check(resolved: Map<String, String>): EnvSpecLayers.Check {
            if (!spec.active) return EnvSpecLayers.NONE.check(emptyMap())
            val check = spec.check(LinkedHashMap(fileValues).apply { putAll(resolved) })
            // varlock's @init… credentials (clientSecret=$INFISICAL_CLIENT_SECRET) aren't needed: EnvBridge signs in by itself
            val credentials = varlock?.credentialKeys.orEmpty()
            return if (credentials.isEmpty()) check else check.copy(missingRequired = check.missingRequired.filter { it !in credentials })
        }

        override fun toString(): String = "Prepared(keys=${envs.keys}, detach=$detach, environment=$environment)"
    }

    /** Reads the env files of a launch of [profile]; [override] is the environment of "Run with environment…". Reads the disk. */
    fun prepare(profile: RunProfile, configured: Map<String, String>, resolver: LaunchSecretResolver, override: String? = null): Prepared {
        if (settings.varlockCompatibility) return prepareVarlock(profile, configured, override)
        val layeredOn = settings.layeredEnvFiles
        val selected = if (layeredOn) layerEnvironment(profile, configured, override) else null
        val layered = if (layeredOn) selected.orEmpty() else null
        val plainAttached = files(profile)
        val attachedFiles = files(profile, layered)
        val dotenvFiles = dotenvFiles(profile, layered)
        val attached = variables(attachedFiles)
        val dotenv = variables(dotenvFiles)
        val specs = if (settings.envSpecDecorators) specFiles(profile, layered).mapNotNull { read(it)?.spec } else emptyList()
        // The environment also answers forEnv(…) in decorators
        val environment = if (layeredOn) selected else if (specs.any(EnvSpecLayers::usesNative)) layerEnvironment(profile, configured, override) else null
        val spec = EnvSpecLayers.of(specs, environment)
        val internal = spec.keys.values.filter { it.internal }.map { it.key }
        val useFiles = resolver.hasReferences(attached) || attachedFiles != plainAttached || internal.any { it in attached }
        val result = LinkedHashMap<String, String>()
        dotenv.forEach { (name, value) -> if (layered != null || resolver.hasReferences(mapOf(name to value))) result[name] = value }
        if (useFiles) result.putAll(attached)
        val fileValues = LinkedHashMap(dotenv).apply { putAll(attached) }
        if (result.isEmpty()) return Prepared(configured, false, spec, fileValues, environment)
        result.putAll(configured)
        return Prepared(result, useFiles, spec, fileValues, environment)
    }

    /**
     * The variables a launch of [profile] starts from and whether the attached files are used (see [prepare]).
     */
    fun effective(profile: RunProfile, configured: Map<String, String>, resolver: LaunchSecretResolver): Pair<Map<String, String>, Boolean> =
        prepare(profile, configured, resolver).let { it.envs to it.detach }

    /** A copy of [copy] without attached env files (only for copies made by an [EnvPatcher]). */
    fun detach(copy: RunProfile) {
        if (copy is EnvFilesOptions) copy.envFilePaths = emptyList()
        EnvPatcher.find(copy)?.first?.let { runCatching { it.detachEnvFiles(copy) } }
    }

    /** The environment for layered files ("" without one), or null when layering is off (the varlock mode always layers). */
    private fun layeredFor(profile: RunProfile, configured: Map<String, String>, override: String?): String? =
        if (settings.layeredEnvFiles || settings.varlockCompatibility) layerEnvironment(profile, configured, override).orEmpty() else null

    /**
     * Whether the env files of [profile] use varlock values (varlock mode on): a function value, `${…}` interpolation, or
     * `@import` / `@currentEnv` / `@init…` in a header. Such launches go through EnvBridge. Cheap enough for `canRun`.
     */
    fun usesVarlock(profile: RunProfile): Boolean {
        if (!settings.varlockCompatibility) return false
        val layered = layeredFor(profile, EnvPatcher.find(profile)?.second.orEmpty(), null)
        return specFiles(profile, layered).any { file -> read(file)?.spec?.let(::hasVarlockValues) == true }
    }

    private fun hasVarlockValues(spec: EnvSpecFile): Boolean =
        spec.rootDecorators.any { it.name == "import" || it.name == "currentEnv" || VarlockProvider.ofInit(it.name) != null } ||
            spec.items.any { item ->
                when (val value = item.value) {
                    is EnvSpecValue.Call, is EnvSpecValue.Ref -> true
                    is EnvSpecValue.Literal -> value.quote != "'" && '$' in value.value
                    else -> false
                }
            }

    /** One env file of a varlock launch: its parsed form, with `pick=` / `omit=` of the `@import` that brought it applied. */
    private class Layer(val path: Path, val spec: EnvSpecFile)

    /**
     * The varlock mode's reading of a launch's env files:
     *
     * 1. the environment: *Run with environment…*, else the value of the item `@currentEnv` names (`@currentEnv=$APP_ENV`: the
     *    run configuration's `APP_ENV`, else its value in `.env.schema` / `.env` / `.env.local`), else EnvBridge's
     *    ([layerEnvironment]: `ENVBRIDGE_ENVIRONMENT`, the binding's environment);
     * 2. the layered files of that environment (`.env.schema` < `.env` < `.env.local` < `.env.[env]` < `.env.[env].local`, attached
     *    files above them), each preceded by the files its `@import(./path, pick=, omit=, allowMissing=, enabled=)` brings in
     *    (only files inside the project; others are refused);
     * 3. per key the definition of the file with the highest precedence (`KEY=` defines nothing, as in varlock): plain values
     *    go to [Prepared.envs] (EnvBridge references among them resolve as usual), varlock expressions to [Prepared.varlock].
     *
     * Decorators are merged with varlock's defaults for unmarked files ([EnvSpecLayers.Defaults.VARLOCK]).
     */
    private fun prepareVarlock(profile: RunProfile, configured: Map<String, String>, override: String?): Prepared {
        val problems = mutableListOf<VarlockLocatedProblem>()
        val projectDir = (profile as? RunConfiguration)?.project?.basePath?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }
        val baseLayers = layers(specFiles(profile, ""), null, projectDir, mutableListOf())
        val fromFile = currentEnvironment(baseLayers, configured, problems)
        val environment = (override?.takeIf { it.isNotBlank() } ?: fromFile ?: layerEnvironment(profile, configured, null))
            ?.takeIf { SAFE_ENVIRONMENT.matches(it) }
        val layers = layers(specFiles(profile, environment.orEmpty()), environment, projectDir, problems)

        val inits = layers.flatMap { layer -> layer.spec.rootDecorators.mapNotNull { d -> VarlockProvider.ofInit(d.name)?.let { Triple(it, d, layer) } } }
        val compiler = VarlockCompiler(
            inits.groupBy({ it.first }, { VarlockSecrets.staticArg(it.second, "id") ?: DEFAULT_INSTANCE }).mapValues { it.value.toSet() - DEFAULT_INSTANCE },
        )
        val instances = LinkedHashMap<Pair<VarlockProvider, String>, Map<String, VarlockExpr>>()
        val credentialKeys = LinkedHashSet<String>()
        val warnings = mutableListOf<String>()
        for ((provider, decorator, layer) in inits) {
            val id = VarlockSecrets.staticArg(decorator, "id") ?: DEFAULT_INSTANCE
            val args = LinkedHashMap<String, VarlockExpr>()
            for (arg in decorator.args.orEmpty()) {
                val name = arg.key ?: continue
                when (name) {
                    in provider.connectionArgs -> {
                        val compiled = compiler.compile(arg.value, null)
                        compiled.expr?.let { args[name] = it }
                        compiled.problems.forEach { problems += VarlockLocatedProblem(it, location(layer, decorator)) }
                    }
                    // Never read: EnvBridge signs in with its own stored credentials
                    in provider.credentialArgs -> {
                        credentialKeys += references(arg.value)
                        val inline = arg.value as? EnvSpecValue.Literal
                        if (name in VarlockProvider.SECRET_CREDENTIALS && inline != null && inline.value.isNotEmpty() && '$' !in inline.value) {
                            warnings += SecretsBundle.message("varlock.warning.inline.credential", decorator.name, name, layer.path.fileName.toString())
                        }
                    }
                }
            }
            instances[provider to id] = args
        }
        for (layer in layers) {
            layer.spec.rootDecorator("setValuesBulk")?.let { bulk ->
                val function = ((bulk.args?.firstOrNull()?.value ?: bulk.value) as? EnvSpecValue.Call)?.name ?: "setValuesBulk"
                problems += VarlockLocatedProblem(VarlockProblem(Code.BULK, null, listOf("$function()")), location(layer, bulk))
            }
        }

        val schemes = SecretProvider.all().map { it.referenceScheme }
        val envs = LinkedHashMap<String, String>()
        val fileValues = LinkedHashMap<String, String>()
        val definitions = LinkedHashMap<String, VarlockExpr>()
        val locations = LinkedHashMap<String, VarlockLocation>()
        for ((key, definition) in definitions(layers, compiler, schemes)) {
            if (key in configured) continue
            locations[key] = definition.location
            definition.problems.forEach { problems += VarlockLocatedProblem(it, definition.location) }
            when (val expr = definition.expr) {
                null, VarlockExpr.Undefined -> Unit
                is VarlockExpr.Text -> {
                    envs[key] = expr.value
                    fileValues[key] = expr.value
                }
                else -> definitions[key] = expr
            }
        }
        envs.putAll(configured)
        // A credential item another value uses is needed after all
        credentialKeys.removeAll(definitions.values.flatMap(::referencedNames).toSet())
        val plan = VarlockPlan(definitions, instances, problems.distinct(), locations, credentialKeys, warnings, environment)
        val specs = if (settings.envSpecDecorators) layers.map { it.spec } else emptyList()
        val spec = EnvSpecLayers.of(specs, environment, EnvSpecLayers.Defaults.VARLOCK, always = settings.envSpecDecorators)
        return Prepared(envs, files(profile, environment.orEmpty()).isNotEmpty(), spec, fileValues, environment, plan)
    }

    private class Definition(val expr: VarlockExpr?, val problems: List<VarlockProblem>, val location: VarlockLocation)

    /** Per key, the definition of the layer with the highest precedence (`KEY=` defines nothing). */
    private fun definitions(layers: List<Layer>, compiler: VarlockCompiler, schemes: List<String>): Map<String, Definition> {
        val result = LinkedHashMap<String, Definition>()
        for (layer in layers) {
            for (item in layer.spec.items) {
                // An EnvBridge binding comment (`KEY= # infisical://KEY`) stands for its reference, as without the varlock mode
                val binding = item.trailingComment?.let { DotEnv.bindingOf(it, schemes) }
                if (binding == null && item.value == null) continue
                val compiled = if (binding != null) VarlockCompiler.Result(VarlockExpr.Text(binding), emptyList()) else compiler.compile(item.value, item.key)
                result[item.key] = Definition(compiled.expr, compiled.problems, VarlockLocation(layer.path, item.line))
            }
        }
        return result
    }

    /** The value of the item `@currentEnv` names, from the configuration or the base files; null when it has none. */
    private fun currentEnvironment(layers: List<Layer>, configured: Map<String, String>, problems: MutableList<VarlockLocatedProblem>): String? {
        val (layer, decorator) = layers.flatMap { layer -> listOfNotNull(layer.spec.rootDecorator("currentEnv")?.let { layer to it }) }.lastOrNull() ?: return null
        val compiler = VarlockCompiler()
        val expr = compiler.compile(decorator.value, null).expr ?: return null
        // The configuration's value wins over the files' (it is the process environment varlock reads first)
        val definitions = definitions(layers, compiler, emptyList()).filterKeys { it !in configured }.mapNotNull { (key, d) -> d.expr?.let { key to it } }.toMap()
        val evaluator = VarlockEvaluator(definitions, { name -> (configured[name] ?: System.getenv(name))?.let { VarlockValue(it) } }, null) { _, _, _ ->
            throw VarlockException(VarlockProblem(Code.SECRET_IN_DECORATOR, null, listOf("@currentEnv")))
        }
        return try {
            evaluator.evaluate(expr, null).text?.takeIf { it.isNotBlank() }
        } catch (e: VarlockException) {
            problems += VarlockLocatedProblem(e.problem, location(layer, decorator))
            null
        }
    }

    /** [files] in order, each preceded by what its `@import(…)`s bring in (recursively, each file once). */
    private fun layers(files: List<Path>, environment: String?, projectDir: Path?, problems: MutableList<VarlockLocatedProblem>): List<Layer> {
        val result = mutableListOf<Layer>()
        val seen = HashSet<Path>()
        fun add(file: Path, filter: (String) -> Boolean, depth: Int) {
            val real = runCatching { file.toRealPath() }.getOrDefault(file.normalize())
            if (!seen.add(real) || depth > MAX_IMPORT_DEPTH) return
            val spec = read(file)?.spec ?: return
            for (decorator in spec.rootDecorators.filter { it.name == "import" }) {
                val args = decorator.args ?: continue
                fun named(name: String) = args.lastOrNull { it.key == name }?.value
                val enabled = when (val value = named("enabled")) {
                    null -> true
                    is EnvSpecValue.Literal -> value.value != "false"
                    is EnvSpecValue.Call -> value.name == "forEnv" && environment != null &&
                        value.positional.any { (it as? EnvSpecValue.Literal)?.value == environment }
                    else -> true
                }
                if (!enabled) continue
                val pathText = (args.firstOrNull { it.key == null }?.value as? EnvSpecValue.Literal)?.value ?: continue
                // Inside the project only, symbolic links followed (an absolute path or ../ out of it is refused)
                val target = real.resolveSibling(pathText).normalize()
                val targetReal = runCatching { target.toRealPath() }.getOrNull()
                if (projectDir == null || !(targetReal ?: target).startsWith(projectDir)) {
                    problems += VarlockLocatedProblem(VarlockProblem(Code.IMPORT_OUTSIDE, null, listOf(pathText)), VarlockLocation(file, lineOf(file, decorator)))
                    continue
                }
                if (targetReal == null || !Files.isRegularFile(targetReal)) {
                    val allowMissing = (named("allowMissing") as? EnvSpecValue.Literal)?.value == "true"
                    if (targetReal != null || !allowMissing) {
                        problems += VarlockLocatedProblem(VarlockProblem(Code.IMPORT_MISSING, null, listOf(pathText)), VarlockLocation(file, lineOf(file, decorator)))
                    }
                    continue
                }
                val pick = names(named("pick"))
                val omit = names(named("omit")).orEmpty()
                add(targetReal, { key -> filter(key) && (pick == null || key in pick) && key !in omit }, depth + 1)
            }
            result += Layer(file, spec.copy(items = spec.items.filter { filter(it.key) }))
        }
        files.forEach { add(it, { true }, 0) }
        return result
    }

    private const val MAX_IMPORT_DEPTH = 10

    /** `pick=[A, B]` or `pick=A` -> the names; null when not given. */
    private fun names(value: EnvSpecValue?): Set<String>? = when (value) {
        null -> null
        is EnvSpecValue.Array -> value.items.mapNotNull { (it as? EnvSpecValue.Literal)?.value }.toSet()
        is EnvSpecValue.Literal -> setOf(value.value)
        else -> emptySet()
    }

    /** The names a value refers to (`$A`, `ref(A)`, `concat($A, …)`). */
    private fun references(value: EnvSpecValue): Set<String> = when (value) {
        is EnvSpecValue.Ref -> setOf(value.name)
        is EnvSpecValue.Call -> if (value.name == "ref") value.positional.mapNotNull { (it as? EnvSpecValue.Literal)?.value }.toSet() else value.args.flatMap { references(it.value) }.toSet()
        is EnvSpecValue.Literal -> if (value.quote == "'") emptySet() else Regex("""\$\{?([A-Za-z_][A-Za-z0-9_]*)""").findAll(value.value).map { it.groupValues[1] }.toSet()
        is EnvSpecValue.Array -> value.items.flatMap(::references).toSet()
    }

    /** The items a compiled value refers to. */
    private fun referencedNames(expr: VarlockExpr): List<String> = when (expr) {
        is VarlockExpr.Ref -> listOf(expr.name)
        is VarlockExpr.Pure -> expr.args.flatMap(::referencedNames)
        is VarlockExpr.Secret -> (expr.call.positional + expr.call.named.values).flatMap(::referencedNames)
        is VarlockExpr.Text, VarlockExpr.Undefined -> emptyList()
    }

    private fun location(layer: Layer, decorator: EnvSpecDecorator) = VarlockLocation(layer.path, lineOf(layer.path, decorator))

    /** The zero-based line of [decorator] in [file]. */
    private fun lineOf(file: Path, decorator: EnvSpecDecorator): Int {
        val text = read(file)?.text ?: return 0
        return text.subSequence(0, decorator.range.first.coerceIn(0, text.length)).count { it == '\n' }
    }

    /** The files whose decorators count, lowest precedence first: schemas next to them, the program's files, attached files. */
    private fun specFiles(profile: RunProfile, layered: String?): List<Path> {
        val files = dotenvFiles(profile, layered) + files(profile, layered)
        val directories = (listOfNotNull(dotenvDirectory(profile)) + files.mapNotNull { it.parent }).distinct()
        val schemas = directories.map { it.resolve(SCHEMA_NAME).normalize() }.filter { it !in files && Files.isRegularFile(it) }
        return (schemas + files).distinct()
    }

    private fun expand(profile: RunConfiguration, raw: String): Path? {
        val base = profile.project.basePath
        val expanded = raw.trim()
            .replace("\$PROJECT_DIR\$", base.orEmpty())
            .replace("\$USER_HOME\$", System.getProperty("user.home"))
            .let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }
        return runCatching { Path.of(expanded).let { p -> if (p.isAbsolute || base == null) p else Path.of(base).resolve(p) }.normalize() }.getOrNull()
    }

    private fun read(file: Path): Cached? {
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size > MAX_BYTES) return null
        cache[file]?.takeIf { it.modified == modified && it.size == size }?.let { return it }
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val spec = runCatching { EnvSpecParser.parse(text) }.getOrDefault(EnvSpecFile.EMPTY)
        val variables = if (file.fileName?.toString() == SCHEMA_NAME) {
            schemaDefaults(spec)
        } else {
            runCatching { DotEnv.parse(text, SecretProvider.all().map { it.referenceScheme }) }.getOrDefault(LinkedHashMap())
        }
        return Cached(modified, size, variables, spec, text).also { cache[file] = it }
    }

    /** The static values of a schema: undefined items (`KEY=`), references and function calls are left out. */
    private fun schemaDefaults(spec: EnvSpecFile): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (item in spec.items) {
            val literal = item.value as? EnvSpecValue.Literal
            if (literal != null) result[item.key] = literal.value else result.remove(item.key)
        }
        return result
    }
}
