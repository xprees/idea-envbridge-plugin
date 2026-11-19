package cz.xprees.secrets.dotnet

/**
 * ASP.NET Core configuration keys and environment variables: `ConnectionStrings:Default` in `appsettings.json` is overridden
 * by the environment variable `ConnectionStrings__Default` (the `__` separator stands for `:`).
 */
object AppSettingsKeys {
    /** `appsettings.json`, `appsettings.Development.json`, … */
    fun isAppSettings(fileName: String): Boolean =
        fileName.equals("appsettings.json", ignoreCase = true) ||
            (fileName.startsWith("appsettings.", ignoreCase = true) && fileName.endsWith(".json", ignoreCase = true))

    fun toEnvironmentVariable(path: List<String>): String = path.joinToString("__")

    fun toConfigurationKey(variable: String): String = variable.replace("__", ":")

    /** The secret key matching [path] among [keys], trying `A__B` and `A:B` (case-insensitively, like .NET configuration). */
    fun match(path: List<String>, keys: Collection<String>): String? {
        val candidates = setOf(toEnvironmentVariable(path), path.joinToString(":"))
        return keys.firstOrNull { key -> candidates.any { it.equals(key, ignoreCase = true) } }
    }
}
