package cz.xprees.secrets.vault

import cz.xprees.secrets.SecretsBundle
import cz.xprees.secrets.SecretsIcons
import cz.xprees.secrets.core.model.ProviderEnvironment
import cz.xprees.secrets.core.model.ProviderProject
import cz.xprees.secrets.core.model.SecretConnection
import cz.xprees.secrets.core.model.SecretEntry
import cz.xprees.secrets.core.model.SecretFolder
import cz.xprees.secrets.core.model.SecretMetadata
import cz.xprees.secrets.core.provider.MetadataEditor
import cz.xprees.secrets.core.model.SecretScope
import cz.xprees.secrets.core.model.SecretValue
import cz.xprees.secrets.core.provider.AuthMethod
import cz.xprees.secrets.core.provider.AuthOption
import cz.xprees.secrets.core.provider.KeysIndexScope
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.core.provider.SecretProviderException
import cz.xprees.secrets.core.provider.SecretsClient
import cz.xprees.secrets.core.provider.MoveResult
import cz.xprees.secrets.core.provider.WriteOutcome
import cz.xprees.secrets.core.provider.SignInInteraction
import cz.xprees.secrets.core.provider.renameTarget
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import cz.xprees.secrets.core.reference.ReferenceGrammar
import cz.xprees.secrets.core.reference.SecretReference
import javax.swing.Icon

/**
 * HashiCorp Vault and OpenBao (same API): KV secrets (version 1 and 2), namespaces. Projects are KV mounts, environments are
 * namespaces, folders are secret paths and a secret's fields are its keys. Writes change fields of secrets (KV v2 with
 * check-and-set).
 */
class VaultProvider : SecretProvider {
    override val id: String = ID
    override val displayName: String = "HashiCorp Vault / OpenBao"
    override val icon: Icon get() = SecretsIcons.PROVIDER
    // A namespace / the single environment is account structure, not a selection users switch
    override val switchesEnvironments: Boolean get() = false

    /** With an environment layout, environments are paths users switch between ([VaultEnvironmentLayout]). */
    override fun switchesEnvironments(connection: SecretConnection): Boolean = VaultEnvironmentLayout.isSet(connection)

    /** Short references without `?env=`, on a connection with an environment layout; a full path never follows the selection. */
    override fun followsSelection(connection: SecretConnection, reference: SecretReference): Boolean =
        switchesEnvironments(connection) && reference.environment == null && VaultGrammar.isShort(reference)

    /**
     * With an environment layout, a full reference (mount and path) reads in the connection's namespace (or its `?namespace=`),
     * not in the selected environment, which is no namespace any more.
     */
    override fun referenceDefaults(connection: SecretConnection, reference: SecretReference, defaults: SecretReference.Defaults): SecretReference.Defaults =
        if (VaultEnvironmentLayout.isSet(connection) && !VaultGrammar.isShort(reference)) defaults.copy(environment = loginNamespace(connection)) else defaults

    /**
     * A full reference whose path follows the connection's environment layout (in the connection's namespace) reads that layout
     * project: `vault://secret/myapp/dev/db#f` is in `secret/myapp/{env}`.
     */
    override fun referencedProject(connection: SecretConnection, reference: SecretReference): String? {
        val projectId = reference.projectId ?: return null
        if (VaultGrammar.isShort(reference)) return projectId
        if (reference.environment != null && reference.environment != loginNamespace(connection)) return projectId
        val layout = runCatching { VaultEnvironmentLayout.of(connection) }.getOrNull() ?: return projectId
        return layout.locate(projectId, reference.path ?: "/")?.first?.id ?: projectId
    }

    /** A layout scope as full references name it: mount, the connection's namespace and the full path. */
    override fun equivalentScope(connection: SecretConnection, scope: SecretScope): SecretScope? {
        val layout = VaultLayoutProject.parse(scope.projectId) ?: return null
        if (!VaultLayoutProject.isEnvironmentName(scope.environment)) return null
        return SecretScope(layout.mount, loginNamespace(connection), SecretScope.normalizePath(layout.basePath(scope.environment) + scope.path), scope.recursive)
    }

    override val referenceScheme: String = "vault"
    override val referenceGrammar: ReferenceGrammar get() = VaultGrammar
    override val supportsWrites: Boolean get() = true

    // Every Vault is self-hosted or a dedicated HCP cluster: no shared instance to offer
    override val instancePresets: List<Pair<String, String>> = emptyList()

    override val authMethods: List<AuthMethod> = VaultAuthMethods.ALL

    override fun defaultEnvironment(connection: SecretConnection): String =
        if (VaultEnvironmentLayout.isSet(connection)) "dev" else loginNamespace(connection)

    override fun createClient(connection: SecretConnection): SecretsClient = VaultClient(connection)

    /** A secret path's `custom_metadata` (KV v2): read when the user hovers over its field or folder, never while listing. */
    override fun hasSecretDetails(connection: SecretConnection, entry: SecretEntry?): Boolean = true

    /**
     * Only the bound secret path and what is below it: walking a whole mount costs a request per path (up to
     * [VaultClient.MAX_RECURSIVE_PATHS]), and a mount's root holds no fields, so no namespace's root is read. With an
     * environment layout (a [VaultLayoutProject] bound), like Infisical, every environment's root too (its own path, one read
     * each), for "which environments define this key".
     */
    override fun keysIndexScope(bound: SecretScope, environments: List<ProviderEnvironment>): KeysIndexScope {
        if (!VaultLayoutProject.isLayoutId(bound.projectId)) return KeysIndexScope.boundFolder(bound)
        return KeysIndexScope(
            environments = (listOf(bound.environment) + environments.map { it.slug }).distinct(),
            folders = bound.copy(recursive = true),
        )
    }

    /**
     * The Vault UI's page of the scope (OpenBao's UI uses the same routes). Called on the EDT, so the mount's KV version is only
     * taken from what listing it found ([VaultKvVersions]); unknown counts as KV v2.
     */
    override fun dashboardUrl(connection: SecretConnection, scope: SecretScope?, organizationId: String?, key: String?): String {
        if (scope == null) return "${connection.baseUrl}/ui/"
        // A layout scope is opened at its full path
        val physical = runCatching { equivalentScope(connection, scope) }.getOrNull() ?: scope
        val namespace = physical.environment.takeIf { it != VaultApi.ROOT_NAMESPACE }?.let { "?namespace=" + VaultHttp.segment(it) }.orEmpty()
        val path = VaultHttp.path(physical.path)
        val mount = "${connection.baseUrl}/ui/vault/secrets/${VaultHttp.segment(physical.projectId)}"
        // KV v1: /list (the mount's root) and /show/<path>; KV v2: /kv/list and /kv/<path, encoded as one segment>/details
        if (VaultKvVersions.get(connection, physical.environment, physical.projectId) == 1) {
            return if (path.isEmpty()) "$mount/list$namespace" else "$mount/show/$path$namespace"
        }
        val target = if (path.isEmpty()) "list" else "${VaultHttp.segment(path)}/details"
        return "$mount/kv/$target$namespace"
    }

    companion object {
        const val ID = "vault"

        /** The namespace [connection] signs in to (and, with an environment layout, reads in). */
        fun loginNamespace(connection: SecretConnection): String = VaultGrammar.normalizeNamespace(connection.option(VaultAuthMethods.OPTION_NAMESPACE))
    }
}

object VaultAuthMethods {
    const val OIDC = "oidc"
    const val TOKEN = "token"
    const val APPROLE = "approle"
    const val USERPASS = "userpass"
    const val LDAP = "ldap"

    const val OPTION_NAMESPACE = "namespace"
    const val OPTION_NAMESPACES = "namespaces"
    const val OPTION_MOUNTS = "mounts"
    const val OPTION_ENVIRONMENT_LAYOUT = "environmentLayout"
    const val OPTION_MOUNT = "authMount"
    const val OPTION_ROLE = "role"
    const val OPTION_CALLBACK_PORT = "callbackPort"
    const val OPTION_ROLE_ID = "roleId"
    const val OPTION_USERNAME = "username"
    const val OPTION_TOKEN = "token"
    const val OPTION_SECRET_ID = "secretId"
    const val OPTION_PASSWORD = "password"

    const val DEFAULT_CALLBACK_PORT = 8250
    const val CALLBACK_PATH = "/oidc/callback"

    /** Options every method has: where to sign in and what to show. */
    private val COMMON = listOf(
        AuthOption(
            OPTION_NAMESPACE,
            SecretsBundle.message("auth.vault.option.namespace.label"),
            required = false,
            hint = SecretsBundle.message("auth.vault.option.namespace.hint"),
        ),
        AuthOption(
            OPTION_NAMESPACES,
            SecretsBundle.message("auth.vault.option.namespaces.label"),
            required = false,
            hint = SecretsBundle.message("auth.vault.option.namespaces.hint"),
        ),
        AuthOption(
            OPTION_MOUNTS,
            SecretsBundle.message("auth.vault.option.mounts.label"),
            required = false,
            hint = SecretsBundle.message("auth.vault.option.mounts.hint"),
        ),
        AuthOption(
            OPTION_ENVIRONMENT_LAYOUT,
            SecretsBundle.message("auth.vault.option.environmentLayout.label"),
            required = false,
            hint = SecretsBundle.message("auth.vault.option.environmentLayout.hint"),
            validate = { value -> VaultEnvironmentLayout.problem(value)?.let { SecretsBundle.message("auth.vault.option.environmentLayout.invalid", it) } },
        ),
    )

    private fun mount(default: String) =
        AuthOption(OPTION_MOUNT, SecretsBundle.message("auth.vault.option.authMount.label"), required = false, hint = default)

    val ALL = listOf(
        AuthMethod(
            id = OIDC,
            displayName = SecretsBundle.message("auth.vault.oidc.name"),
            description = SecretsBundle.message("auth.vault.oidc.description"),
            options = listOf(
                AuthOption(
                    OPTION_ROLE,
                    SecretsBundle.message("auth.vault.option.role.label"),
                    required = false,
                    hint = SecretsBundle.message("auth.vault.option.role.hint"),
                ),
                mount(OIDC),
                AuthOption(
                    OPTION_CALLBACK_PORT,
                    SecretsBundle.message("auth.vault.option.callbackPort.label"),
                    required = false,
                    hint = DEFAULT_CALLBACK_PORT.toString(),
                ),
            ) + COMMON,
        ),
        AuthMethod(
            id = TOKEN,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink(SecretsBundle.message("auth.link.vault.ui")) { it.trimEnd('/') + "/ui/" },
            ),
            displayName = SecretsBundle.message("auth.vault.token.name"),
            description = SecretsBundle.message("auth.vault.token.description"),
            options = COMMON,
            secretOptions = listOf(AuthOption(
                OPTION_TOKEN,
                SecretsBundle.message("auth.vault.option.token.label"),
                required = false,
                hint = SecretsBundle.message("auth.vault.option.token.hint"),
            )),
        ),
        AuthMethod(
            id = APPROLE,
            setupLinks = listOf(
                cz.xprees.secrets.core.provider.SetupLink.fixed(SecretsBundle.message("auth.link.approle"), "https://developer.hashicorp.com/vault/docs/auth/approle"),
            ),
            displayName = SecretsBundle.message("auth.vault.approle.name"),
            description = SecretsBundle.message("auth.vault.approle.description"),
            options = listOf(AuthOption(OPTION_ROLE_ID, SecretsBundle.message("auth.vault.option.roleId.label")), mount(APPROLE)) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_SECRET_ID, SecretsBundle.message("auth.vault.option.secretId.label"))),
        ),
        AuthMethod(
            id = USERPASS,
            displayName = SecretsBundle.message("auth.vault.userpass.name"),
            description = SecretsBundle.message("auth.vault.userpass.description"),
            options = listOf(
                AuthOption(OPTION_USERNAME, SecretsBundle.message("auth.vault.option.username.label")),
                mount(USERPASS),
            ) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_PASSWORD, SecretsBundle.message("auth.vault.option.password.label"))),
        ),
        AuthMethod(
            id = LDAP,
            displayName = SecretsBundle.message("auth.vault.ldap.name"),
            description = SecretsBundle.message("auth.vault.ldap.description"),
            options = listOf(
                AuthOption(OPTION_USERNAME, SecretsBundle.message("auth.vault.option.username.label")),
                mount(LDAP),
            ) + COMMON,
            secretOptions = listOf(AuthOption(OPTION_PASSWORD, SecretsBundle.message("auth.vault.option.password.label"))),
        ),
    )
}

/**
 * [SecretsClient] for one Vault / OpenBao connection, on KV version 1 and 2 mounts (the version is detected per mount, see
 * [kvVersion]). Writes change one field of a secret: the secret is read, the field changed and the whole secret written back,
 * so every other field keeps its value and type. On KV v2 the write is a new version with check-and-set, so a change made
 * meanwhile is refused instead of lost; KV v1 has no versions, so there the last write wins.
 */
class VaultClient(private val connection: SecretConnection) : SecretsClient {
    private val api = VaultApi(VaultHttp(connection.baseUrl))
    private val auth = VaultAuth(connection, api)

    override fun signIn(interaction: SignInInteraction): String = auth.signIn(interaction)

    override fun signOut() {
        auth.signOut()
        VaultKvVersions.forgetAll(connection)
        VaultNewEnvironments.forget(connection)
    }

    override fun hasCredentials(): Boolean = auth.hasCredentials()

    /**
     * The KV mounts, version 1 and 2 (configured, else listed from the server), each with the namespaces as environments; with an
     * environment layout, the layout's projects in them ([layoutProjects]).
     */
    override fun listProjects(): List<ProviderProject> {
        val mounts = mounts()
        VaultEnvironmentLayout.of(connection)?.let { return layoutProjects(it, mounts) }
        val environments = namespaces().map { ProviderEnvironment(slug = it, name = it) }
        return mounts.map { ProviderProject(id = it, name = it, slug = it, environments = environments) }
    }

    /** The KV mounts: configured, else listed from the server (remembering their versions). */
    private fun mounts(): List<String> {
        val configured = csv(connection.option(VaultAuthMethods.OPTION_MOUNTS)).map { it.trim('/') }.filter { it.isNotEmpty() }
        val mounts = configured.ifEmpty {
            val listed = auth.withToken { api.kvMounts(it, auth.loginNamespace) }.orEmpty()
            // The listing says each mount's version: the first read needn't ask again
            listed.forEach { VaultKvVersions.put(connection, auth.loginNamespace, it.path, it.version) }
            listed.map { it.path }
        }.ifEmpty {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "No KV secret engine found for '${connection.name}'. If the token can't list them, enter the mounts " +
                    "(for example: secret) in the connection's settings.",
            )
        }
        return mounts.distinct()
    }

    /**
     * The projects of [layout] in [mounts], in the connection's namespace: the template is walked, a placeholder being a LIST of
     * the path so far (folders, and at the template's last segment secrets too). A mount whose first LIST is refused is left out,
     * unless every mount is; a deeper refused LIST leaves out that branch. At most [MAX_RECURSIVE_PATHS] LISTs. Environments
     * created in the IDE but holding no secret yet are added ([VaultNewEnvironments]).
     */
    private fun layoutProjects(layout: VaultEnvironmentLayout, mounts: List<String>): List<ProviderProject> {
        val namespace = auth.loginNamespace
        var lists = 0
        var failure: SecretProviderException? = null
        val projects = mutableListOf<ProviderProject>()
        for (mount in mounts) {
            // (project or null, environment) found below the mount
            val found = mutableListOf<Pair<String?, String>>()
            fun walk(index: Int, path: String, project: String?, environment: String?, kv: Int, token: String) {
                if (index == layout.segments.size) {
                    found += project to environment!!
                    return
                }
                val segment = layout.segments[index]
                if (segment != VaultEnvironmentLayout.PROJECT && segment != VaultEnvironmentLayout.ENV) {
                    walk(index + 1, "$path/$segment", project, environment, kv, token)
                    return
                }
                if (++lists > MAX_RECURSIVE_PATHS) {
                    throw SecretProviderException(
                        SecretProviderException.Kind.CONFIGURATION,
                        "Listing the environment layout ${layout.template} of '${connection.name}' takes more than $MAX_RECURSIVE_PATHS " +
                            "requests; enter fewer KV mounts in the connection's settings",
                    )
                }
                val first = project == null && environment == null
                val listing = try {
                    api.list(token, namespace, mount, SecretScope.normalizePath(path), kv)
                } catch (e: SecretProviderException) {
                    // A project or environment the token may not list is left out (the first LIST tells whether it may list the mount)
                    if (first || e.kind != SecretProviderException.Kind.FORBIDDEN) throw e
                    return
                }
                val last = index == layout.segments.size - 1
                val names = (listing.folders + if (last) listing.secrets else emptyList()).distinct().filter(VaultLayoutProject::isEnvironmentName)
                for (name in names) {
                    if (segment == VaultEnvironmentLayout.PROJECT) walk(index + 1, "$path/$name", name, environment, kv, token)
                    else walk(index + 1, "$path/$name", project, name, kv, token)
                }
            }
            try {
                auth.withToken { token ->
                    found.clear()
                    walk(0, "", null, null, kvVersion(token, namespace, mount, "/"), token)
                }
            } catch (e: SecretProviderException) {
                VaultKvVersions.forget(connection, namespace, mount)
                if (e.kind != SecretProviderException.Kind.FORBIDDEN && e.kind != SecretProviderException.Kind.NOT_FOUND) throw e
                failure = failure ?: e
                continue
            }
            for ((project, environments) in found.groupBy({ it.first }, { it.second })) {
                val id = layout.project(mount, project).id
                val name = if (project == null) mount else "$mount/$project"
                val listed = environments.distinct().map { ProviderEnvironment(slug = it, name = it) }
                projects += ProviderProject(id = id, name = name, slug = id, environments = VaultNewEnvironments.merge(connection, id, listed))
            }
        }
        if (projects.isEmpty()) failure?.let { throw it }
        return projects.sortedBy { it.name }
    }

    /** Child paths of the scope's path: folders and secrets alike (a secret is shown as a folder of its fields). */
    override fun listFolders(scope: SecretScope): List<SecretFolder> {
        val listing = withKv(scope, scope.path) { token, kv, at -> api.list(token, at.namespace, at.mount, at.path(scope.path), kv) }
        return (listing.folders + listing.secrets).distinct().sorted().map { name ->
            SecretFolder(name, SecretScope.normalizePath("${scope.path}/$name"))
        }
    }

    /** Fields of the secret at the scope's path; with `recursive`, of every secret below it too. */
    override fun listSecrets(scope: SecretScope): List<SecretEntry> {
        val paths = if (scope.recursive) secretPaths(scope) else listOf(scope.path)
        return paths.flatMap { path ->
            withKv(scope, path) { token, kv, at -> api.read(token, at.namespace, at.mount, at.path(path), kv) }.orEmpty().map { (field, value) ->
                SecretEntry(key = field, value = SecretValue(value), path = path, environment = scope.environment)
            }
        }
    }

    /** The scope's path and the secret paths below it, breadth first, at most [MAX_RECURSIVE_PATHS]. */
    private fun secretPaths(scope: SecretScope): List<String> {
        val result = mutableListOf(scope.path)
        val folders = ArrayDeque(listOf(scope.path))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val listing = withKv(scope, folder) { token, kv, at -> api.list(token, at.namespace, at.mount, at.path(folder), kv) }
            for (name in listing.secrets) result += SecretScope.normalizePath("$folder/$name")
            for (name in listing.folders) folders += SecretScope.normalizePath("$folder/$name")
            if (result.size > MAX_RECURSIVE_PATHS) {
                throw SecretProviderException(
                    SecretProviderException.Kind.CONFIGURATION,
                    "More than $MAX_RECURSIVE_PATHS secrets below ${location(scope)}; import a narrower path",
                )
            }
        }
        return result.distinct()
    }

    // ---- KV version --------------------------------------------------------------------------------------------------

    /**
     * Where a scope's secrets are on the server: the [namespace], the [mount] and the path of the scope's root inside it (empty
     * for a mount scope; the environment's path for a layout scope, see [VaultLayoutProject]).
     */
    private class Location(val namespace: String, val mount: String, private val base: String) {
        /** The path inside the mount of [path] (relative to the scope's root). */
        fun path(path: String): String = SecretScope.normalizePath("$base/$path")
    }

    /** Where [scope] is: a layout scope in the connection's namespace, a mount scope in the namespace it names. */
    private fun locate(scope: SecretScope): Location =
        VaultLayoutProject.parse(scope.projectId)?.let { Location(auth.loginNamespace, it.mount, it.basePath(scope.environment)) }
            ?: Location(scope.environment, scope.projectId, "")

    /**
     * Runs [call] with a valid token, the KV version of the scope's mount and where the scope is. A failure forgets the version,
     * so the next call detects it again (a mount may have been upgraded to KV v2 meanwhile).
     */
    private fun <T> withKv(scope: SecretScope, path: String, call: (token: String, kv: Int, at: Location) -> T): T {
        val at = locate(scope)
        return auth.withToken { token ->
            val kv = kvVersion(token, at.namespace, at.mount, at.path(path))
            try {
                call(token, kv, at)
            } catch (e: SecretProviderException) {
                VaultKvVersions.forget(connection, at.namespace, at.mount)
                throw e
            }
        }
    }

    /**
     * The KV version of [mount] in [namespace]: remembered ([VaultKvVersions]), else asked the way the Vault CLI asks
     * ([VaultApi.kvVersion]), else probed at [path] ([VaultApi.probeKvVersion]). When nothing tells, KV v2, as before KV v1
     * was supported, and not remembered (see docs/DESIGN.md, "KV version 1").
     */
    private fun kvVersion(token: String, namespace: String, mount: String, path: String): Int {
        VaultKvVersions.get(connection, namespace, mount)?.let { return it }
        val detected = api.kvVersion(token, namespace, mount) ?: api.probeKvVersion(token, namespace, mount, path) ?: return 2
        VaultKvVersions.put(connection, namespace, mount, detected)
        return detected
    }

    // ---- Writes ------------------------------------------------------------------------------------------------------

    override fun createSecret(scope: SecretScope, key: String, value: SecretValue, comment: String): WriteOutcome {
        val path = secretPath(scope)
        withKv(scope, path) { token, kv, at ->
            val current = api.readVersioned(token, at.namespace, at.mount, at.path(path), kv)
            if (current.fields.has(key)) {
                throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(scope)} already has a field $key")
            }
            current.fields.addProperty(key, value.reveal())
            api.write(token, at.namespace, at.mount, at.path(path), current.fields, current.version, kv)
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Changes the field's value; with a [newKey], the field is renamed in the same write (keeping its place among the fields;
     * on KV v2 with check-and-set), refused before writing when the secret already has a field [newKey].
     */
    override fun updateSecret(scope: SecretScope, key: String, value: SecretValue, comment: String?, newKey: String?): WriteOutcome {
        val path = secretPath(scope)
        val rename = renameTarget(key, newKey)
        withKv(scope, path) { token, kv, at ->
            val current = api.readVersioned(token, at.namespace, at.mount, at.path(path), kv)
            if (!current.fields.has(key)) throw missing(scope, key)
            if (rename == null) {
                current.fields.addProperty(key, value.reveal())
                api.write(token, at.namespace, at.mount, at.path(path), current.fields, current.version, kv)
                return@withKv
            }
            if (current.fields.has(rename)) {
                throw SecretProviderException(
                    SecretProviderException.Kind.CONFIGURATION,
                    "${location(scope)} already has a field $rename; choose another name or delete that one first",
                )
            }
            // A value left as it was keeps its JSON type (a number or an object stays one); a changed one is written as text
            val old = current.fields.get(key)
            val unchanged = VaultApi.textOf(old) == value.reveal()
            val renamed = JsonObject()
            for ((name, element) in current.fields.entrySet()) {
                if (name == key) renamed.add(rename, if (unchanged) element.deepCopy() else JsonPrimitive(value.reveal())) else renamed.add(name, element.deepCopy())
            }
            api.write(token, at.namespace, at.mount, at.path(path), renamed, current.version, kv)
        }
        return WriteOutcome.APPLIED
    }

    override fun deleteSecret(scope: SecretScope, key: String): WriteOutcome {
        val path = secretPath(scope)
        withKv(scope, path) { token, kv, at ->
            val current = api.readVersioned(token, at.namespace, at.mount, at.path(path), kv)
            if (!current.fields.has(key)) throw missing(scope, key)
            current.fields.remove(key)
            removeFields(token, at, path, current, kv)
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Copies the fields to the destination secret (one write: on KV v2 a new version, values and types as stored), then removes
     * them from the source secret (another write). A field already at the destination stops the move before anything is
     * written unless [overwrite]; a failure to remove the source copy is reported, never a lost field. Source and destination
     * may be on mounts of different KV versions.
     */
    override fun moveSecrets(source: SecretScope, secrets: List<SecretEntry>, destination: SecretScope, overwrite: Boolean): MoveResult {
        val sourcePath = secretPath(source)
        val destinationPath = secretPath(destination)
        val sourceAt = locate(source)
        val destinationAt = locate(destination)
        if (sourceAt.namespace == destinationAt.namespace && sourceAt.mount == destinationAt.mount && sourceAt.path(sourcePath) == destinationAt.path(destinationPath)) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(source)} is already where the fields are; nothing was moved")
        }
        val keys = secrets.map { it.key }
        val notRemoved = linkedMapOf<String, String>()
        val copied = withKv(destination, destinationPath) { token, toKv, toAt ->
            val fromKv = kvVersion(token, sourceAt.namespace, sourceAt.mount, sourceAt.path(sourcePath))
            val from = api.readVersioned(token, sourceAt.namespace, sourceAt.mount, sourceAt.path(sourcePath), fromKv)
            val to = api.readVersioned(token, toAt.namespace, toAt.mount, toAt.path(destinationPath), toKv)
            // Check everything before writing anything, so a refused move changes nothing
            val missing = keys.filter { !from.fields.has(it) }
            if (missing.isNotEmpty()) {
                throw SecretProviderException(
                    SecretProviderException.Kind.NOT_FOUND,
                    "${location(source)} has no ${missing.joinToString { "\"$it\"" }} any more; nothing was moved. Refresh and try again",
                )
            }
            val conflicts = keys.filter { to.fields.has(it) }
            if (conflicts.isNotEmpty() && !overwrite) {
                throw SecretProviderException(
                    SecretProviderException.Kind.CONFIGURATION,
                    "${location(destination)} already has ${conflicts.joinToString { "\"$it\"" }}; nothing was moved. Tick Overwrite to replace their values",
                )
            }
            val copied = mutableListOf<String>()
            for (key in keys) {
                to.fields.add(key, from.fields.get(key).deepCopy())
                copied += key
            }
            if (copied.isNotEmpty()) api.write(token, toAt.namespace, toAt.mount, toAt.path(destinationPath), to.fields, to.version, toKv)
            copied
        }
        if (copied.isEmpty()) return MoveResult(moved = emptyList(), notRemoved = notRemoved)
        try {
            withKv(source, sourcePath) { token, kv, at ->
                // Read again: the copy is safe at the destination, the source may have changed since
                val current = api.readVersioned(token, at.namespace, at.mount, at.path(sourcePath), kv)
                copied.forEach { current.fields.remove(it) }
                removeFields(token, at, sourcePath, current, kv)
            }
        } catch (e: SecretProviderException) {
            copied.forEach { notRemoved[it] = "copied, but not removed from ${location(source)}: ${e.message}" }
            return MoveResult(moved = emptyList(), notRemoved = notRemoved)
        }
        return MoveResult(moved = copied, notRemoved = notRemoved)
    }

    // ---- custom_metadata (KV v2) -------------------------------------------------------------------------------------

    /**
     * The `custom_metadata` of the secret at the scope's path (the folder itself or the path of field [key] alike: metadata
     * belongs to the path): its `description` key as the description, the other keys as tags. One `GET <mount>/metadata/<path>`;
     * null on KV v1 (no metadata) and where there is no secret.
     */
    override fun secretDetails(scope: SecretScope, key: String?): SecretMetadata? {
        if (locate(scope).path(scope.path) == "/") return null
        return withKv(scope, scope.path) { token, kv, at ->
            if (kv == 1) null else api.customMetadata(token, at.namespace, at.mount, at.path(scope.path))?.let(::toMetadata)
        }
    }

    /** KV v2: a description and `key=value` tags, saved as the path's `custom_metadata` (shared by all its fields). KV v1: none. */
    override fun metadataEditor(scope: SecretScope, key: String?): MetadataEditor? {
        if (key == null) return null
        val path = secretPath(scope)
        return withKv(scope, path) { token, kv, at ->
            if (kv == 1) return@withKv null
            val current = api.customMetadata(token, at.namespace, at.mount, at.path(path)).orEmpty()
            MetadataEditor(current = toMetadata(current), freeForm = true, note = "custom_metadata of ${location(scope)}, shared by all its fields")
        }
    }

    /** Replaces the path's `custom_metadata` (`POST <mount>/metadata/<path>`); refused on KV v1, which has none. */
    override fun updateMetadata(scope: SecretScope, key: String, metadata: SecretMetadata): WriteOutcome {
        val path = secretPath(scope)
        withKv(scope, path) { token, kv, at ->
            if (kv == 1) throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${location(scope)} is on a KV version 1 engine, which has no metadata")
            api.writeCustomMetadata(token, at.namespace, at.mount, at.path(path), fromMetadata(metadata))
        }
        return WriteOutcome.APPLIED
    }

    /**
     * Without an environment layout refused: namespaces are an administrator's job. With one, nothing is written (a path exists
     * once a secret is stored there): the environment is remembered in memory ([VaultNewEnvironments]) until it holds a secret.
     */
    override fun createEnvironment(projectId: String, name: String, slug: String): ProviderEnvironment {
        if (!VaultLayoutProject.isLayoutId(projectId)) {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "Namespaces are created by a Vault administrator. To show an existing one, add it to the connection's More namespaces. " +
                    "To keep environments in paths (secret/myapp/dev), set the connection's environment layout.",
            )
        }
        val environment = slug.trim()
        if (!VaultLayoutProject.isEnvironmentName(environment)) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "'$slug' can't be an environment: it must be one path segment, without /")
        }
        val project = listProjects().firstOrNull { it.id == projectId }
        if (project != null && project.environments.any { it.slug == environment }) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "${project.name} already has an environment $environment")
        }
        VaultNewEnvironments.add(connection, projectId, environment)
        return ProviderEnvironment(slug = environment, name = environment)
    }

    /**
     * Writes [current] without the removed fields. KV v2: a secret left with none has its latest version deleted (restorable).
     * KV v1: a secret left with none is deleted (it holds nothing any more; KV v1 can't restore it).
     */
    private fun removeFields(token: String, at: Location, path: String, current: VaultApi.Versioned, kv: Int) {
        if (kv == 1) {
            if (current.fields.size() == 0) api.deleteLatest(token, at.namespace, at.mount, at.path(path), kv)
            else api.write(token, at.namespace, at.mount, at.path(path), current.fields, 0, kv)
            return
        }
        // Written with check-and-set either way, so a version someone wrote meanwhile is refused, not deleted; the empty version
        // is then deleted too (only it can be the latest now, apart from a write in the instant between the two calls)
        api.write(token, at.namespace, at.mount, at.path(path), current.fields, current.version, kv)
        if (current.fields.size() == 0) api.deleteLatest(token, at.namespace, at.mount, at.path(path), kv)
    }

    /** The secret a write goes to: the scope's path, which must name a secret (KV has none at the mount's root). */
    private fun secretPath(scope: SecretScope): String {
        if (scope.path.split('/').any { it == "." || it == ".." }) {
            throw SecretProviderException(SecretProviderException.Kind.CONFIGURATION, "Write the secret's full path, without . or ..")
        }
        // A layout environment's own path is a secret path; a mount's root isn't
        if (scope.path == "/" && !VaultLayoutProject.isLayoutId(scope.projectId)) {
            throw SecretProviderException(
                SecretProviderException.Kind.CONFIGURATION,
                "Choose a secret path below the mount (for example /app/db): fields live in secrets, and ${scope.projectId}/ itself isn't one",
            )
        }
        return scope.path
    }

    private fun location(scope: SecretScope): String {
        val at = runCatching { locate(scope) }.getOrNull() ?: return scope.projectId + scope.path
        return at.mount + at.path(scope.path) + if (at.namespace == VaultApi.ROOT_NAMESPACE) "" else " (namespace ${at.namespace})"
    }

    private fun missing(scope: SecretScope, key: String) =
        SecretProviderException(SecretProviderException.Kind.NOT_FOUND, "${location(scope)} has no field $key")

    private fun namespaces(): List<String> {
        val login = auth.loginNamespace
        return (listOf(login) + csv(connection.option(VaultAuthMethods.OPTION_NAMESPACES)).map(VaultGrammar::normalizeNamespace)).distinct()
    }

    companion object {
        const val MAX_RECURSIVE_PATHS = 500

        /** The `custom_metadata` key shown as the description; the other keys are tags. */
        const val DESCRIPTION_KEY = "description"

        /** `custom_metadata` as shown: [DESCRIPTION_KEY] as the description, the other keys as tags (sorted). */
        fun toMetadata(custom: Map<String, String>): SecretMetadata =
            SecretMetadata(description = custom[DESCRIPTION_KEY].orEmpty(), tags = custom.filterKeys { it != DESCRIPTION_KEY }.toSortedMap())

        /** The `custom_metadata` to write for [metadata]: its tags, and its description under [DESCRIPTION_KEY] when not blank. */
        fun fromMetadata(metadata: SecretMetadata): Map<String, String> = buildMap {
            putAll(metadata.tags.filterKeys { it.isNotBlank() && it != DESCRIPTION_KEY })
            if (metadata.description.isNotBlank()) put(DESCRIPTION_KEY, metadata.description)
        }

        private fun csv(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
