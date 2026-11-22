package cz.xprees.secrets.core.envspec

/**
 * What the decorators of the `.env` files of one launch say about each key, merged over the files (lowest precedence first,
 * e.g. `.env.schema`, `.env`, `.env.local`, `.env.dev`, `.env.dev.local`), and the checks a launch runs with it.
 *
 * Merging: for each key, an explicit decorator in a later file wins over one in an earlier file (`@required` / `@optional`,
 * `@sensitive` / `@public`, `@internal`, `@type`). Without an explicit one, the root defaults (`@defaultRequired`,
 * `@defaultSensitive`) of the **first file that declares the key** apply, as varlock applies defaults per file; a file without
 * them uses [Defaults]. `@defaultRequired=infer` makes an item required when that file gives it a value. A `forEnv(a, b)` value
 * (`@required=forEnv(prod)`) is true when the launch's environment is one of the arguments; other function values are ignored.
 */
class EnvSpecLayers private constructor(
    /** Per key, in first-declared order. Empty when no file uses a decorator EnvBridge acts on. */
    val keys: Map<String, Key>,
) {
    /** Whether any file uses a decorator EnvBridge acts on ([EnvSpecDecorators.NATIVE]). */
    val active: Boolean get() = keys.isNotEmpty()

    /** The merged meaning of one key. */
    data class Key(val key: String, val required: Boolean, val sensitive: Boolean, val internal: Boolean, val type: EnvSpecType?)

    /** What files without root defaults mean: EnvBridge's own reading is "not required, not sensitive". */
    data class Defaults(val required: Boolean, val sensitive: Boolean) {
        companion object {
            /** EnvBridge's reading of a file without root defaults. */
            val NATIVE = Defaults(required = false, sensitive = false)

            /** varlock's reading of a file without root defaults (for the compatibility mode). */
            val VARLOCK = Defaults(required = true, sensitive = true)
        }
    }

    /** The result of checking a launch's final environment. Names only, except [sensitiveValues] (for console masking). */
    data class Check(
        /** Required keys whose value is missing or empty, in declaration order. */
        val missingRequired: List<String>,
        /** Keys whose value doesn't fit their `@type`, with what was expected. */
        val typeMismatches: List<Pair<String, String>>,
        /** The non-empty values of sensitive keys, to mask in consoles. Never log them. */
        val sensitiveValues: Set<String>,
        /** Keys marked `@internal`: resolved, but not handed to the process. */
        val internalKeys: Set<String>,
    ) {
        override fun toString(): String =
            "Check(missingRequired=$missingRequired, typeMismatches=$typeMismatches, sensitiveValues=${sensitiveValues.size}, internalKeys=$internalKeys)"
    }

    /** Checks the final [environment] of a launch (after references were resolved). */
    fun check(environment: Map<String, String>): Check {
        val missing = mutableListOf<String>()
        val mismatches = mutableListOf<Pair<String, String>>()
        val sensitive = LinkedHashSet<String>()
        val internal = LinkedHashSet<String>()
        for (spec in keys.values) {
            val value = environment[spec.key]
            if (spec.required && value.isNullOrEmpty()) missing += spec.key
            if (spec.type != null && !value.isNullOrEmpty() && !spec.type.accepts(value)) mismatches += spec.key to spec.type.expected
            if (spec.sensitive && !value.isNullOrEmpty()) sensitive += value
            if (spec.internal) internal += spec.key
        }
        return Check(missing, mismatches, sensitive, internal)
    }

    companion object {
        val NONE = EnvSpecLayers(emptyMap())

        /**
         * Merges [files] (lowest precedence first).
         *
         * @param environment the launch's environment, for `forEnv(…)`
         */
        fun of(files: List<EnvSpecFile>, environment: String? = null, defaults: Defaults = Defaults.NATIVE): EnvSpecLayers {
            if (files.none(::usesNative)) return NONE
            class Merged(val required: Boolean, val sensitive: Boolean) {
                var explicitRequired: Boolean? = null
                var explicitSensitive: Boolean? = null
                var internal = false
                var type: EnvSpecType? = null
            }
            val merged = LinkedHashMap<String, Merged>()
            for (file in files) {
                val requiredDefault = file.rootDecorator("defaultRequired")
                val sensitiveDefault = file.rootDecorator("defaultSensitive")
                for (item in file.items) {
                    val entry = merged.getOrPut(item.key) {
                        Merged(requiredByDefault(requiredDefault, item, defaults), sensitiveByDefault(sensitiveDefault, item.key, defaults))
                    }
                    for (decorator in item.decorators) {
                        when (decorator.name) {
                            "required" -> flag(decorator, environment)?.let { entry.explicitRequired = it }
                            "optional" -> flag(decorator, environment)?.let { entry.explicitRequired = !it }
                            "sensitive" -> flag(decorator, environment)?.let { entry.explicitSensitive = it }
                            "public" -> flag(decorator, environment)?.let { entry.explicitSensitive = !it }
                            "internal" -> flag(decorator, environment)?.let { entry.internal = it }
                            "type" -> (decorator.value?.let(EnvSpecType::parse) as? EnvSpecType.Parsed.Ok)?.let { entry.type = it.type }
                        }
                    }
                }
            }
            return EnvSpecLayers(
                merged.mapValues { (key, m) -> Key(key, m.explicitRequired ?: m.required, m.explicitSensitive ?: m.sensitive, m.internal, m.type) },
            )
        }

        /** Whether [file] uses a decorator EnvBridge acts on. */
        fun usesNative(file: EnvSpecFile): Boolean = file.allDecorators.any { it.name in EnvSpecDecorators.NATIVE }

        private fun requiredByDefault(decorator: EnvSpecDecorator?, item: EnvSpecItem, defaults: Defaults): Boolean {
            if (decorator == null) return defaults.required
            decorator.booleanValue?.let { return it }
            val infer = (decorator.value as? EnvSpecValue.Literal)?.let { it.quote == null && it.value == "infer" } == true
            if (!infer) return defaults.required
            val value = item.value
            return value != null && !(value is EnvSpecValue.Literal && value.value.isEmpty())
        }

        private fun sensitiveByDefault(decorator: EnvSpecDecorator?, key: String, defaults: Defaults): Boolean {
            if (decorator == null) return defaults.sensitive
            decorator.booleanValue?.let { return it }
            val call = decorator.value as? EnvSpecValue.Call
            val prefix = (call?.takeIf { it.name == "inferFromPrefix" }?.positional?.singleOrNull() as? EnvSpecValue.Literal)?.value
                ?: return defaults.sensitive
            // Keys with the (public) prefix aren't sensitive, every other key is
            return !key.startsWith(prefix)
        }

        /** A decorator's switch: `@x`, `@x=true|false`, `@x=forEnv(a, b)`; null when EnvBridge can't evaluate it. */
        private fun flag(decorator: EnvSpecDecorator, environment: String?): Boolean? {
            decorator.booleanValue?.let { return it }
            val call = decorator.value as? EnvSpecValue.Call ?: return null
            if (call.name != "forEnv") return null
            val environments = call.positional.mapNotNull { (it as? EnvSpecValue.Literal)?.value }
            return environment != null && environments.any { it.equals(environment, ignoreCase = true) }
        }
    }
}
