package cz.xprees.secrets.doppler

import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.reference.PathGrammar
import cz.xprees.secrets.core.reference.ReferenceGrammar
import cz.xprees.secrets.core.reference.SecretReference

/**
 * Doppler references: the default path grammar without folders, because a Doppler config is a flat list of secrets.
 *
 * ```
 * doppler://DB_PASSWORD                   DB_PASSWORD of the bound config
 * doppler://prd:DB_PASSWORD               DB_PASSWORD of config prd (of the bound project)
 * doppler://dev_personal:(star)           every secret of config dev_personal (scope import)
 * doppler://DB_PASSWORD?project=backend&connection=<name>
 * ```
 *
 * The "environment" of a reference is a Doppler **config** (`dev`, `stg`, `prd`, `dev_personal`, branch configs such as
 * `dev_feature`). `**` is accepted and means the same as `*`. (star) stands for `*`.
 */
object DopplerGrammar : ReferenceGrammar {
    override fun parse(scheme: String, body: String, original: String): SecretReference {
        val reference = PathGrammar.parse(scheme, body, original)
        require(reference.path == null) {
            "Doppler has no folders: write $scheme://[config:]KEY, as in $scheme://prd:${reference.key ?: "*"} (in '$original')"
        }
        return reference.copy(recursive = false)
    }

    override fun format(reference: SecretReference): String = PathGrammar.format(reference.copy(path = null, recursive = false))

    override fun build(scheme: String, scope: SecretScope, key: String?, defaults: SecretReference.Defaults?): SecretReference =
        PathGrammar.build(scheme, scope.copy(path = "/", recursive = false), key, defaults)
}
