package cz.xprees.secrets.editor.varlock

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import cz.xprees.secrets.core.envspec.EnvSpecFile
import cz.xprees.secrets.core.envspec.EnvSpecParser
import cz.xprees.secrets.core.envspec.EnvSpecType
import cz.xprees.secrets.core.envspec.EnvSpecValue
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.editor.DotEnvLines

/**
 * When the varlock editor support is on for a file. It needs *varlock compatibility mode* (`varlockCompatibility`, off by
 * default) and a `.env`-style file name (`.env`, `.env.schema`, `.env.local`, `app.env`, …), and then only where varlock is
 * actually used: the file is a `.env.schema`, a `.env.schema` lies in the same directory (varlock layers the files of one
 * directory), or the file itself uses decorators or function-call values. Elsewhere `.env` files are left as they are.
 */
object VarlockMode {
    /** varlock's schema file, the lowest layer. */
    const val SCHEMA = ".env.schema"

    /** Whether the varlock compatibility mode is switched on. */
    fun enabled(): Boolean = SecretsAppSettings.getInstance().state.varlockCompatibility

    /** Whether the varlock editor support applies to [file] (its original file is used for completion copies). */
    fun isActive(file: PsiFile): Boolean {
        if (!enabled()) return false
        val original = file.originalFile
        if (!DotEnvLines.isDotEnv(original.name)) return false
        if (original.viewProvider.contents.length > EnvReferences.MAX_TEXT_LENGTH) return false
        if (original.name == SCHEMA) return true
        if (original.virtualFile?.parent?.findChild(SCHEMA) != null) return true
        val parsed = parsed(original)
        return parsed.usesDecorators || parsed.items.any { it.value is EnvSpecValue.Call }
    }

    /** [file] parsed, cached until it changes. */
    fun parsed(file: PsiFile): EnvSpecFile = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(EnvSpecParser.parse(file.viewProvider.contents.toString()), file)
    }
}

/**
 * A file and the files varlock layers with it: the `.env*` files of its directory (`.env.schema` first, then `.env`,
 * `.env.local` and the others by name) and the files the headers `@import`. What the editor needs from them: where each key is
 * defined, each key's `@type`, and which plugins the headers load. Cached until any file changes; files are only read, never
 * evaluated.
 */
class VarlockContext private constructor(
    /** The file itself. */
    val file: PsiFile,
    /** The file parsed. */
    val parsed: EnvSpecFile,
    /** The other layers (the file itself excluded), lowest precedence first. */
    val layers: List<Layer>,
) {
    /** Another file of the same configuration. */
    class Layer(val file: PsiFile, val parsed: EnvSpecFile)

    /** Where a key is defined. */
    class Definition(val file: PsiFile, val range: TextRange)

    /** Each key with its definitions, in layer order (the file's own definitions where it sits among the layers). */
    val keys: Map<String, List<Definition>> by lazy {
        val result = LinkedHashMap<String, MutableList<Definition>>()
        for ((psi, spec) in allFiles()) {
            for (item in spec.items) {
                result.getOrPut(item.key) { mutableListOf() } += Definition(psi, TextRange(item.keyRange.first, item.keyRange.last + 1))
            }
        }
        result
    }

    /** Whether a reference to [key] can be resolved: a key of a layer, or one of varlock's own (`VARLOCK_ENV`, …). */
    fun knows(key: String): Boolean = key in keys || key.startsWith("VARLOCK_")

    /** The `@type` of [key]: the file's own, else the one of the layer with the highest precedence that declares one. */
    fun typeOf(key: String): EnvSpecType? {
        fun of(spec: EnvSpecFile): EnvSpecType? =
            (spec.item(key)?.decorator("type")?.value?.let(EnvSpecType::parse) as? EnvSpecType.Parsed.Ok)?.type
        of(parsed)?.let { return it }
        return layers.asReversed().firstNotNullOfOrNull { of(it.parsed) }
    }

    /** The plugins the headers of the layers load (`@initOp(…)`, `@plugin(…1password…)`). */
    val plugins: Set<VarlockCatalog.Plugin> by lazy { pluginScan().first }

    /** Whether a header loads a plugin EnvBridge doesn't know, whose functions it then can't know either. */
    val unknownPlugins: Boolean by lazy { pluginScan().second }

    private fun pluginScan(): Pair<Set<VarlockCatalog.Plugin>, Boolean> {
        val found = mutableSetOf<VarlockCatalog.Plugin>()
        var unknown = false
        for ((_, spec) in allFiles()) {
            for (decorator in spec.rootDecorators) {
                val byInit = VarlockCatalog.Plugin.entries.firstOrNull { it.initDecorator == decorator.name }
                when {
                    byInit != null -> found += byInit
                    decorator.name == "plugin" -> {
                        val text = decorator.valueText.orEmpty().lowercase()
                        val byPackage = VarlockCatalog.Plugin.entries.filter { plugin -> plugin.packageWords.any { it in text } }
                        if (byPackage.isEmpty()) unknown = true
                        found += byPackage
                    }

                    decorator.name.startsWith("init") && decorator.name.length > 4 && decorator.name[4].isUpperCase() -> unknown = true
                }
            }
        }
        return found to unknown
    }

    /** Every file with its parse: the layers in order with the file itself in its place. */
    private fun allFiles(): List<Pair<PsiFile, EnvSpecFile>> {
        val own = file to parsed
        val others = layers.map { it.file to it.parsed }
        val ownIndex = order(file.name)
        val (before, after) = others.partition { order(it.first.name) <= ownIndex }
        return before + own + after
    }

    companion object {
        private const val MAX_LAYERS = 32

        /** The context of [file] (its original file for completion copies). */
        fun of(file: PsiFile): VarlockContext {
            val original = file.originalFile
            return CachedValuesManager.getCachedValue(original) {
                CachedValueProvider.Result.create(build(original), PsiModificationTracker.getInstance(original.project))
            }
        }

        private fun build(file: PsiFile): VarlockContext {
            val own = file.virtualFile
            val directory = own?.parent
            val files = LinkedHashSet<VirtualFile>()
            directory?.children.orEmpty()
                .filter { !it.isDirectory && it.name.startsWith(".env") && it != own }
                .sortedWith(compareBy({ order(it.name) }, { it.name }))
                .forEach { files += it }
            // Files the headers import: one level, relative to the importing file
            val parsed = VarlockMode.parsed(file)
            val manager = PsiManager.getInstance(file.project)
            val importing = listOfNotNull(own?.let { it to parsed }) + files.mapNotNull { vf -> manager.findFile(vf)?.let { vf to VarlockMode.parsed(it) } }
            for ((vf, spec) in importing) {
                for (path in importPaths(spec)) {
                    val target = resolveImport(vf, path) ?: continue
                    if (target.isDirectory) {
                        listOf(VarlockMode.SCHEMA, ".env").mapNotNull { target.findChild(it) }.forEach { files += it }
                    } else {
                        files += target
                    }
                }
            }
            files.remove(own)
            val layers = files.take(MAX_LAYERS).mapNotNull { vf ->
                val psi = manager.findFile(vf) ?: return@mapNotNull null
                if (psi.viewProvider.contents.length > EnvReferences.MAX_TEXT_LENGTH) return@mapNotNull null
                Layer(psi, VarlockMode.parsed(psi))
            }
            return VarlockContext(file, parsed, layers)
        }

        /** The literal paths of a file's `@import(…)` decorators. */
        fun importPaths(spec: EnvSpecFile): List<String> = spec.rootDecorators.filter { it.name == "import" }
            .mapNotNull { (it.args?.firstOrNull { arg -> arg.key == null }?.value as? EnvSpecValue.Literal)?.value?.takeIf(String::isNotBlank) }

        /**
         * The file or directory an `@import` path names, relative to [from]'s directory (or absolute), or null when it doesn't
         * exist. Paths that aren't file paths (`~`, a variable) are not looked up: null as well, see [isFilePath].
         */
        fun resolveImport(from: VirtualFile, path: String): VirtualFile? {
            if (!isFilePath(path)) return null
            if (path.startsWith("/")) return LocalFileSystem.getInstance().findFileByPath(path)
            return from.parent?.findFileByRelativePath(path.removePrefix("./"))
        }

        /** Whether an `@import` path is a plain relative or absolute file path EnvBridge can check. */
        fun isFilePath(path: String): Boolean =
            (path.startsWith("./") || path.startsWith("../") || path.startsWith("/") || path.startsWith(".env")) && '$' !in path

        /** The layer order of a file name: `.env.schema`, `.env`, `.env.local`, then the others, `.local` ones after their base. */
        private fun order(name: String): Int = when {
            name == VarlockMode.SCHEMA -> 0
            name == ".env" -> 1
            name == ".env.local" -> 2
            name.endsWith(".local") -> 4
            else -> 3
        }
    }
}
