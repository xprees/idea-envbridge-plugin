package cz.xprees.secrets.editor

import cz.xprees.secrets.core.model.SecretScope

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.resolve.EnvReferences
import cz.xprees.secrets.core.resolve.KeysIndex
import cz.xprees.secrets.core.settings.SecretsAppSettings
import cz.xprees.secrets.core.varlock.VarlockSecrets

/** Shared, cached scan of a file for variable names and secret references (once per file modification). */
object EditorReferences {
    fun schemes(): List<String> = SecretProvider.all().map { it.referenceScheme }

    /**
     * The variable names and references of [file]; in a `.env` file in the varlock mode also its varlock secret functions
     * (`infisical()`, `vaultSecret("secret/app#key")`, …) as the references they read ([VarlockSecrets.find]), so hints, hover
     * and inspections work on them too.
     */
    fun of(file: PsiFile): List<EnvReferences.Found> = CachedValuesManager.getCachedValue(file) {
        val text = file.viewProvider.contents
        val found = EnvReferences.find(text, schemes())
        val varlock = if (SecretsAppSettings.getInstance().state.varlockCompatibility && DotEnvLines.isDotEnv(file.name) && text.length <= EnvReferences.MAX_TEXT_LENGTH) {
            VarlockSecrets.find(text.toString()).mapNotNull { call ->
                call.reference.key?.let { EnvReferences.Found(it, call.start, call.end, EnvReferences.Kind.REFERENCE, call.reference) }
            }
        } else {
            emptyList()
        }
        CachedValueProvider.Result.create((found + varlock).sortedBy { it.start }, file)
    }

    /** What the editor knows about [found]: the key's info in the bound environment and the environments that define it. */
    data class Resolved(
        val found: EnvReferences.Found,
        val snapshot: KeysIndex.Snapshot,
        val info: KeysIndex.KeyInfo?,
        val coverage: List<String>,
        /** Whether the reference reads the indexed folder (no other environment, folder or project named). */
        val indexed: Boolean,
    )

    fun resolve(project: Project, found: EnvReferences.Found, snapshot: KeysIndex.Snapshot? = KeysIndex.getInstance(project).current()): Resolved? {
        snapshot ?: return null
        val reference = found.reference
        if (reference != null && !reference.scheme.equals(snapshot.scheme, ignoreCase = true)) return null
        // A folder of the bound environment (`?path=`): known from the folder index
        if (reference?.path != null && (reference.environment == null || reference.environment == snapshot.boundEnvironment) &&
            (reference.projectId == null || reference.projectId == snapshot.projectId) && reference.connectionId == null
        ) {
            val path = SecretScope.normalizePath(reference.path)
            // Outside the folder the provider indexes (Vault, 1Password: the bound one): unknown, not missing
            if (!snapshot.isIndexed(path)) return Resolved(found, snapshot, null, emptyList(), false)
            if (path != snapshot.path && snapshot.folders.isNotEmpty()) {
                val info = snapshot.folders[path]?.get(found.name)
                return Resolved(found, snapshot, info, if (info != null) listOf(snapshot.boundEnvironment) else emptyList(), true)
            }
        }
        val indexed = reference == null ||
            ((reference.environment == null || reference.environment in snapshot.environments) && reference.path == null &&
                (reference.projectId == null || reference.projectId == snapshot.projectId) && reference.connectionId == null)
        if (!indexed) return Resolved(found, snapshot, null, emptyList(), false)
        val environment = reference?.environment ?: snapshot.boundEnvironment
        return Resolved(found, snapshot, snapshot.environments[environment]?.get(found.name), snapshot.coverage(found.name), true)
    }
}
