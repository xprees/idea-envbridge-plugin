# Design: EnvBridge

Status: agreed plan (grill session, 2026-11-19). Milestones M1–M4 below; this document is kept up to date as they land.

> This is an unofficial community project. It is not affiliated with, endorsed by or sponsored by Infisical Inc.
> "Infisical" is a trademark of Infisical Inc. and is used here only to describe what the plugin works with.

## Goals

- Use Infisical secrets while developing locally **without keeping them in `.env` files**: they are fetched when a run
  configuration starts and handed to the process in memory.
- Work **inside the UIs the IDE already has** (the environment variables of a run configuration, the *Modify options* menu, the
  editor) instead of adding parallel screens. One tool window to browse secrets is the only new surface.
- **Security first**: sign in through the system browser, tokens only in the IDE's credential store (PasswordSafe), secret values
  never written to disk, logs, run configuration files or the IDE's caches.
- Any Infisical instance: US Cloud, EU Cloud, dedicated and self-hosted (base URL per connection).
- **Modular**: Infisical is the first implementation of a provider interface, so other secret managers can be added later.
- IntelliJ Platform **2026.1+**, every IDE (IDEA, WebStorm, PyCharm, GoLand, Rider, DataGrip, …).

Non-goals for v1: deleting secrets, syncing secrets to files. Creating and editing secrets and creating environments are the only writes, always on an explicit user action (*New Secret…*, *Edit Secret…*, *Extract Value to Secret Manager…*, *New Environment…*), through `SecretsClient.createSecret` / `updateSecret` / `createEnvironment`, all guarded by `SecretsService` so the *Read-only* setting holds everywhere (later also moving and deleting secrets and, for AWS Secrets Manager, *Rotate Now…* through `rotateSecret`, guarded the same way).

## Identity and licensing

- Plugin ID `cz.xprees.secrets` (neutral, so it can grow beyond Infisical), packages `cz.xprees.secrets.core` and
  `cz.xprees.secrets.infisical`, display name **EnvBridge** (renamed from "Secrets for Infisical (Unofficial)"; the
  description opens with the unofficial-integration note). Internal identifiers (credential store service name, settings
  storage) keep the old name so existing settings and sign-ins survive the rename.
- The plugin description, README and NOTICE say it is unofficial and not affiliated with Infisical Inc.
- No Infisical logos or other brand assets; the plugin uses its own key icon.
- Apache-2.0. No code is copied from the Infisical CLI (MIT outside `ee/`): its documented behaviour (browser login callback,
  `.infisical.yaml`) is reimplemented. If a snippet is ever ported, its MIT notice goes into `NOTICE`.

## Architecture

One Gradle module (like the Lottie and Comark plugins). Integrations with other plugins are optional `<depends>` with their own
`config-file`, so the plugin loads in every IDE.

```
cz.xprees.secrets
  core/        provider-agnostic model and services
    model      SecretConnection, ProviderProject, ProviderEnvironment, SecretFolder, SecretEntry, SecretScope, SecretValue
    provider   SecretProvider (extension point cz.xprees.secrets.provider), SecretsClient, AuthMethod
    auth       CredentialVault (PasswordSafe), TokenSet
    reference  SecretReference (`<scheme>://…` parser), reference resolution
    resolve    SecretResolver (launch-time, always fresh), SecretCache (memory-only TTL for UI/editor)
    settings   connections (application level), project binding (project level)
  infisical/   InfisicalProvider, InfisicalClient (REST), auth flows, project config (.infisical.yaml / .infisical.json)
  vault/       VaultProvider (HashiCorp Vault / OpenBao, KV v1 and v2)
  onepassword/ OnePasswordProvider (op CLI, Connect)
  doppler/     DopplerProvider, DopplerClient, DopplerApi / DopplerHttp (REST v3), DopplerAuth (CLI browser flow, tokens),
               DopplerGrammar (doppler://), DopplerProjectFile (doppler.yaml)
  aws/         AwsSecretsManagerProvider, AwsClient, AwsApi / AwsHttp (JSON 1.1), AwsSigV4 (request signing, no SDK),
               AwsAuth (access keys, environment), AwsProfiles (~/.aws files), AwsCli (export-credentials),
               AwsGrammar and AwsSecretNames (aws-sm://), AwsNameLayout (name <-> project/environment per connection),
               AwsMemory (listing and values in memory), AwsSso (IAM Identity Center sign-in)
  run/         launch-time injection: SecretsProgramRunner (clone-and-delegate), EnvPatcher extension point, per-tech patchers
  ui/          tool window, settings pages, login dialogs, notifications
  editor/      (M3) completion, inlays, hover, inspections, console masking, status bar
```

### Provider interface

```kotlin
interface SecretProvider {               // extension point cz.xprees.secrets.provider
    val id: String                       // "infisical"
    val displayName: String
    val referenceScheme: String          // "infisical" -> infisical://…
    val authMethods: List<AuthMethod>
    fun createClient(connection: SecretConnection): SecretsClient
}

interface SecretsClient {                 // blocking, called off the EDT
    fun listProjects(): List<ProviderProject>             // with their environments
    fun listFolders(scope: SecretScope): List<SecretFolder>
    fun listSecrets(scope: SecretScope): List<SecretEntry>
}
```

Optional parts of the SPI, all with defaults so providers that don't need them change nothing (added for AWS Secrets Manager,
generic on purpose):

- `SecretsClient.listSecrets(scope, fresh)`: whether the caller needs current values (launches) or accepts values as fresh as the
  shared cache's (browsing, the keys index). `SecretsService.secrets()` passes its `fresh` flag through.
- `SecretsClient.prefetch(scopes)`: a hint that a launch is about to read these scopes, for providers that read several secrets
  per request; `LaunchSecretResolver` calls it (through `SecretsService.prefetch`) before resolving. Never fails.
- `SecretsClient.rotateSecret(scope)` and `SecretsService.rotateSecret`: start the provider's rotation (guarded like a write).
- `ProviderEnvironment.metadata: SecretMetadata?` (`description`, `tags`, `rotation: RotationInfo(enabled, lastRotated,
  nextRotation, managed, name)`, `reminder: SecretReminder(repeatDays, note)`): non-secret facts the tool window shows as a
  tooltip; *Rotate Now…* is offered where `rotation.enabled`.
- `SecretEntry.metadata` and `SecretFolder.metadata` (the same `SecretMetadata`): what a **listing** already says about one secret
  or folder at no extra cost (Infisical tags, rotation and reminder; 1Password item tags). Shown as a badge after the name and in
  the tooltip. See [Secret metadata](#secret-metadata-tags-rotation-reminders-custom-metadata).
- `SecretProvider.hasSecretDetails(connection, entry)` and `SecretsClient.secretDetails(scope, key)`: details a listing leaves
  out because they cost a request per secret (Vault `custom_metadata`, an Infisical rotation's schedule), read **on demand only**
  (hovering in the tool window, the Edit dialog), never while listing or indexing.
- `SecretsClient.metadataEditor(scope, key)` → `MetadataEditor(current, tagChoices, freeForm, note)`, `updateMetadata(scope, key,
  metadata)` and `createSecret(scope, key, value, comment, metadata)`: what the Edit / New Secret dialogs may change (tags picked
  from a list, or a description and `key=value` tags typed in) and the write. `createSecret` with metadata defaults to create +
  `updateMetadata`; Infisical overrides it to send both in one request. `SecretsService` wraps them (read-only and cache rules
  as for every write; `metadataEditor` returns null while *Read-only* is on).
- `SecretsClient.rotateSecret(scope, key)`: rotate what manages one secret (Infisical's secret rotation); defaults to
  `rotateSecret(scope)`, so AWS is unchanged.
- `SecretProvider.forget(connectionId)`: called whenever `SecretsService.invalidate` runs, so provider-side memory (AWS's values
  read ahead) is dropped with the shared cache. `SecretProvider.listingNote(connection)`: a line the tool window shows under a
  connection's projects (AWS: how many names didn't fit the name layout).
- `AuthOption.validate`: a check the connection dialog runs on a non-blank option value (AWS: the name layout, SSO settings).

A provider also contributes how it is configured per project (Infisical: `.infisical.yaml`) and, optionally, what the
background keys index reads (`keysIndexScope`, see [Caching](#caching)). Everything else (run configurations, references, tool
window, caching, credentials) is shared.

## Authentication

Every connection has a **base URL** (presets: `https://app.infisical.com`, `https://eu.infisical.com`, or custom). Custom URLs must
be `https://`, except loopback addresses. Tokens are kept in PasswordSafe under
`Secrets for Infisical — <connection id>/<kind>` (former name, kept for compatibility), never in XML settings.

| Method | When | Flow |
|---|---|---|
| **Browser sign-in** (default) | Any user, no admin setup | The plugin listens on `127.0.0.1:<random port>` and opens `<base>/login?callback_port=<port>` in the system browser, as the Infisical CLI does. The web app posts the session to the loopback server. |
| **OAuth application** | Orgs that want least-privilege tokens | OAuth 2.0 authorization code with PKCE (S256) and `state`. An org admin registers an OAuth application with redirect URI `http://127.0.0.1:<port>/callback` (port configurable, default 51327) and shares the client ID and secret. The secret goes to PasswordSafe. Tokens are scoped to `secrets:read` and refresh tokens rotate. |
| **Machine identity** | Headless or locked-down setups | Universal Auth (client ID and client secret), access token renewed before it expires. |

Hardening of the browser sign-in callback, which the CLI contract leaves open:

- one server per attempt, bound to loopback only, closed after the first valid request or after 5 minutes;
- accepts only `POST` with `Content-Type: application/json`. That forces a CORS preflight, which is answered only for the
  configured instance's origin, and the `Origin` header must equal it;
- the received token is checked against the configured instance before anything is stored;
- the refresh token is kept, sessions are renewed and failures lead to a new sign-in (no silent fallbacks).

Sign-out deletes the credentials, clears the caches and, for browser sessions, revokes the session on the server where
possible.

## Project binding

A project is bound to *connection + project + environment + folder*. Sources, highest first:

1. The IDE binding (*Settings › Tools › EnvBridge › Project*), stored in the workspace file (IDs only).
2. `.infisical.yaml` / `.infisical.json`, looked up like the CLI does (the project directory and its parents, `.yaml` before
   `.json`): `project-id`, `environment`, `secret-path`, `overrides.git-branch`, `general.domain`.

A `domain` from a project file is **never trusted silently**: a committed file could send your token to another server. It only
selects among the connections you created. If it names an instance with no matching connection, resolution stops and tells you to
add a connection for that instance yourself, if you trust it.

### Environment resolution for a launch (CLI-compatible)

1. An explicit environment in the reference (`infisical://prod:…`) or the run configuration's Infisical section
2. `ENVBRIDGE_ENVIRONMENT` (or the older `INFISICAL_ENVIRONMENT`) set on the run configuration
3. The environment picked in the IDE (status bar / binding)
4. `.infisical.yaml` git-branch override (last segment of the branch name)
5. `.infisical.yaml` default environment
6. `dev`

## Injecting secrets into run configurations

Opt-in per run configuration, inside the run configuration's own **Environment variables** (no new dialog):

- **Per-variable references**: `DB_PASSWORD=infisical://DB_PASSWORD`. The full syntax is
  `infisical://[<env>:][<folder>/…/]<KEY>[?project=<id>&connection=<id>]`. Folders are absolute from the environment's root (no
  folder: the root), so the reference alone says which secret it is; the environment comes from the binding unless written before
  the colon. `?path=/backend` and `?env=prod` are accepted too. The binding's folder is only a default for new secrets and the
  tool window.
- **Whole scope**: a variable whose value ends with `/*` (`/**` for subfolders too) imports every secret of that scope. Its key is
  only a label and is not passed on, e.g. `INFISICAL=infisical://*` or `INFISICAL=infisical://staging:backend/**`.
- **Precedence**: plain values set on the run configuration > per-variable references > whole-scope imports.

Only the references are stored, in the run configuration's normal settings and `.run/*.xml` files, so they can be shared.

At launch `SecretsProgramRunner` (registered first) sees a configuration that has references. It resolves them in the background
with a progress indicator, always fetching fresh values, clones the run profile in memory, patches the clone's environment and
hands it to the runner the IDE would have used, for any executor (Run, Debug, Coverage, Profiler). The original configuration is
never modified, so secrets cannot end up in `workspace.xml`. **Fail closed**: if a reference cannot be resolved, the launch is
stopped with a notification offering *Sign in*, *Retry* and *Run without secrets once*.

Each technology plugs in with a small `EnvPatcher` (extension point `cz.xprees.secrets.envPatcher`) that reads and writes a
configuration's variables:

| Milestone | Targets |
|---|---|
| M1 | Platform `CommonProgramRunConfigurationParameters` (Application, JUnit, TestNG, Spring Boot, Kotlin, shell scripts, …), Gradle and other external-system run configurations, Maven |
| M2 | Node.js / npm / Bun, Python (incl. FastAPI, Django, pytest), Go, Docker image / Dockerfile / Compose (the Docker run configuration's environment variables: the container's environment, or the `docker compose` process environment for `${VAR}` interpolation; no temporary env file was needed), `.env` files holding references, Terminal (opt-in per project, through the terminal's `ShellExecOptionsCustomizer`, which replaces the deprecated `LocalTerminalCustomizer` and is still marked experimental) |
| M4 (done) | Rider `.NET Project` and `.NET Executable` configurations (ASP.NET Core included), patched through the platform's public XML form of environment variables, so no Rider API is needed; launch-profile configurations read `launchSettings.json` and can't be patched. `appsettings*.json` awareness (`Section__Key` ⇄ `Section:Key`): overridden settings marked, plaintext secrets reported. Database data sources: an "Infisical" authentication provider (`connectionInterceptor`) whose user and password are references resolved at connect time |

## Caching

- Values are kept **in memory only**. Browsing and editor features use a short-lived cache (default 5 minutes, configurable),
  which is cleared on sign-out, when the project closes and on demand.
- Every launch fetches fresh values.
- A keys-only index (`KeysIndex`, no values) lives longer, for completion, hover, inlays and inspections.

### The keys index: what it reads and when

**When.** The index snapshot is reloaded when it is older than its *refresh interval*, `max(10 minutes, cache TTL)`, and when
the binding or the connections change. Its reads go through the secrets cache but accept cached listings up to the refresh
interval old (`SecretsService.secrets(…, maxAgeMs)`), not just the cache TTL. So:

- the periodic reload re-reads (its own listings are older than the interval by then): at most one full index read per interval;
- a reload right after a binding or settings change, or soon after the tool window or a hover read the same folder, reuses
  what was read and costs no requests (before, with the 5-minute TTL and a 10-minute interval, every reload missed the cache);
- a cache TTL longer than 10 minutes slows the index down with it: it never refreshes more often than the cache would;
- *Refresh* in the tool window clears the cache and then reloads the index, so what the user asked for is read fresh. Writes
  from the IDE clear the connection's cache and reload the index too.

Keys shown by the editor can therefore be up to two intervals old (a listing up to one interval old in a snapshot up to one
interval old); launches never use the index.

**What.** `SecretProvider.keysIndexScope(bound, environments)` returns a `KeysIndexScope`: the environments whose root folder is
read (the keys a reference without a folder reads, and "which environments define this key" in hover, completion and the
missing-secret inspection) and one folder of the bound environment read with its subfolders (what completion and hover know
about folders). The snapshot also keeps every environment's name (`environmentNames`) for the status bar switcher, *Run with
environment…* and *Compare environments*, whether its keys are indexed or not, and the folder it indexed (`indexedFolder`): a
reference to a folder outside it is *unknown* to the editor (no hint, no "missing" warning), not missing.

| Provider | Environments' roots read | Folder read | Per refresh (requests) |
|---|---|---|---|
| Infisical, AWS (default) | Every environment of the project | Bound environment from `/`, recursive | 1 per environment + 1 recursive listing |
| Doppler | Root configs and the bound config; branch and personal configs (`ProviderEnvironment.branchOf`) are left out | None (a config is flat; the root read has every key) | 1 per root config (+1 when bound to a branch config) |
| Vault / OpenBao | None (a mount's root holds no fields) | The bound secret path, recursive (`KeysIndexScope.boundFolder`) | A secret path: 1 LIST + 1 GET (+ the mounts listing); bound to `/`: the whole mount as before, up to `MAX_RECURSIVE_PATHS` |
| 1Password | None (a vault's root holds no fields) | The bound item (`KeysIndexScope.boundFolder`) | One `op item get` / request (Connect: +1 list when the item is named by title); bound to the vault: 1 list + 1 per item **changed** since the last read |

**Trade-off (Vault, 1Password):** completion and hover know only the keys of the bound secret path / item (and, for Vault, the
paths below it). Keys of other secrets or items aren't suggested and get no hint, and references to them aren't reported as
missing; they resolve at launch exactly as before, because launches read every reference fresh and never consult the index.
Binding the vault's / mount's root (folder `/`) indexes everything, as before, at the old cost (1Password then re-reads only
changed items). The Vault and 1Password per-namespace / per-vault root reads were dropped because they read nothing.

**Doppler:** hover's "defined in", the struck-out "only in other environments" completions and the missing-secret inspection
consider root configs (`dev`, `stg`, `prd`) and the bound config, not other branch or personal configs; switching and *Run with
environment…* still offer every config.

**1Password item memory** (`OnePasswordItemMemory`): a whole-vault read (`op://vault/**`, or the index bound to a vault's root)
lists the vault's items (`op item list --vault … --format json`, Connect `GET /v1/vaults/{id}/items`) and reads with `op item
get` / `GET …/items/{id}` only the items whose `version` or update time (`updated_at` from the CLI, `updatedAt` from Connect)
differs from when this connection last read them; the others come from memory. Kept per connection, vault and item, memory only
(never persisted; values included, like the secrets cache), forgotten on sign-out, for an item this IDE writes (in case a
listing were slow to show the new version) and for items no longer listed. A listing without either field reads every item, as
before. This also applies to launches with `op://vault/**`: the listing is always read fresh and the version changes with every
edit, so the values are the current ones.

## Tool window "Secrets" (right side)

- Tree: connections › projects › environments › folders › secrets (imported and personal-override secrets are marked), with speed
  search. Keys and comments are visible, values masked.
- *Reveal* hides the value again after a few seconds. *Copy value* clears the clipboard after 30 s if it still holds the secret.
  *Copy reference* gives `infisical://…`, *Copy as KEY=…*, and *Open in dashboard*.
- *Run configuration preview* (dry run): pick a run configuration and see the final variables it would receive and where each
  comes from (explicit, reference, scope), values masked.

## Secret metadata: tags, rotation, reminders, custom metadata

Generic model (see the SPI list): `SecretMetadata` on environments (AWS: one AWS secret is one environment), on secrets
(`SecretEntry.metadata`) and on folders (`SecretFolder.metadata`). **Where tags live:** a tag belongs to whatever the provider
attaches it to, so the same type is used at each level rather than a new field per provider: Infisical tags are per secret
(`SecretEntry`), a 1Password tag is per item, which is a folder in EnvBridge (`SecretFolder`, and repeated on the item's fields so
a field shows them too), an AWS tag is per AWS secret, i.e. per environment (`ProviderEnvironment`, unchanged), and Vault's
`custom_metadata` is per secret path, which is a folder whose fields are the keys (read on demand for the folder or any of its
fields). Values are never part of it; tags and descriptions aren't logged.

**Cost rule**: listings only carry what the listing response already contains. Anything that needs a request per secret is a
*detail* (`secretDetails`), read once when the user hovers over the node (the tooltip shows "Loading details…" until then; the
result is kept with the node until the folder is read again) or when the Edit dialog opens (`metadataEditor`).

| Provider | Shown (from the listing) | On demand | Edited (Edit / New Secret) | Rotate Now |
|---|---|---|---|---|
| Infisical | `tags[].slug`, `isRotatedSecret` (badge *rotated*), `secretReminderRepeatDays` / `secretReminderNote` | the managing rotation's name, auto-rotation on/off, last and next rotation | tags picked from the project's tags (`tagIds`), on create and edit | the secret rotation managing the secret (Secret Rotations v2) |
| 1Password | item `tags` on items and their fields | — | — (read-only) | — |
| Vault / OpenBao KV v2 | — | `custom_metadata` (`description` key = description, other keys = tags) | description and `key=value` lines → `custom_metadata` | — |
| Vault / OpenBao KV v1 | — | — (KV v1 has no metadata) | — | — |
| AWS Secrets Manager | description, tags, rotation per environment (unchanged) | — | description as the comment (unchanged) | `RotateSecret` (unchanged) |
| Doppler | — (no tags, no public rotation API) | — | — | — |

### Infisical

| Purpose | Call (verified in the API reference at infisical.com/docs and in the backend source) |
|---|---|
| Tags, rotation flag, reminder of each secret | `GET /api/v4/secrets` (already used): `secrets[].tags[] {id, slug, name, color}`, `isRotatedSecret`, `rotationId`, `secretReminderRepeatDays`, `secretReminderNote` |
| One secret's description, without its value | `GET /api/v4/secrets?…&viewSecretValue=false&includeImports=false&recursive=false`, picking the shared secret by key |
| The project's tags | `GET /api/v1/projects/{projectId}/tags` → `tags[] {id, slug, …}`; on 404 (older instances) the deprecated `GET /api/v1/workspace/{projectId}/tags` → `workspaceTags[]`. Both exist in the pinned v0.167.13 |
| Set tags | `PATCH /api/v4/secrets/{name}` with `tagIds` (and nothing else when only tags change); `POST /api/v4/secrets/{name}` with `tagIds` for a new secret. The backend **replaces** the secret's tags with `tagIds` (it deletes the secret's tag links, then inserts the given ones; `secret-v2-bridge-fns.ts`), so an empty list removes all |
| The project's rotations | `GET /api/v2/secret-rotations?projectId=` → `secretRotations[] {id, name, type, isAutoRotationEnabled, lastRotatedAt, nextRotationAt, …}` (every rotation type) |
| Rotate now | `POST /api/v2/secret-rotations/{type}/{rotationId}/rotate-secrets` (the route is per rotation type: `postgres-credentials`, `aws-iam-user-secret`, …; the type comes from the listing) |

- Tags are shown and stored by **slug** (`SecretMetadata.tags` = slug → ""); writes map slugs to IDs with the project's tag list
  and refuse an unknown slug before writing. Moving or copying (`/api/v4/secrets/duplicate` with `attributesToCopy.tags`) keeps
  tags, as before.
- **Rotated secrets**: Infisical refuses changing a rotated secret's value or name (`Cannot update rotated secret name or value`)
  but accepts its comment and tags. EnvBridge's Edit dialog therefore keeps value and key read-only for such a secret, with an
  explanation, and `InfisicalClient.updateSecret` refuses a changed value or a rename before sending anything; comment and
  tags still work. *Rotate Now…* on such a secret rotates its secret rotation (every secret it maps changes), with the same
  confirmation, production guard and *Read-only* check as AWS.
- **Permissions**: listing tags needs the project role's Tags *Read* permission; listing rotations Secret Rotation *Read*; rotating
  Secret Rotation *Rotate Secrets* (`ProjectPermissionSecretRotationActions.RotateSecrets`). Each 403 becomes a FORBIDDEN error
  naming the permission. 404 on the rotations list (an instance without Secret Rotations v2) says so. A failed tag listing in a
  dialog only hides the tags (the value can still be saved).
- The v4 response's `secretMetadata[]` (key/value pairs) isn't shown or edited (not part of this change).

### Vault / OpenBao (KV v2)

`GET <mount>/metadata/<path>` → `data.custom_metadata` (string map, or null) and `POST <mount>/metadata/<path>` with only
`{"custom_metadata": {…}}` (other metadata settings such as `max_versions` are left as they are; the map is replaced as a whole).
Read only for a hover or the Edit dialog, never while listing, so a recursive listing still costs one LIST per folder and one
GET per secret. The key `description` is shown as the description, the others as tags; metadata belongs to the secret path, so
the dialog says it applies to every field of it. Policies need `read` / `update` on `<mount>/metadata/<path>`; a 403 names the
missing capability, and the value edit (on `<mount>/data/<path>`) is separate: it is saved first and still works when the
metadata write is refused (the notification reports the metadata failure). When only the metadata changed, no new version of
the secret is written. KV v1 mounts have no metadata: nothing is read, the dialog shows no metadata fields, and
`updateMetadata` is refused.

### 1Password

Item `tags` come with `op item list --format json` (`Item.tags?: string[]` in 1Password's `op-js` type definitions, the type its
`item list` returns) and with Connect's item listing (`tags: string[]` in Connect's OpenAPI spec, `docs/openapi/spec.yaml`), so
showing them costs nothing. Tags are read-only. The connection option **Only items tagged** (`onlyTagged`, comma-separated)
narrows the vault's item listing, and with it the tool window, whole-vault imports (`op://vault/**`) and the keys index, to
items with one of these tags (a tag also matches its nested tags: `env` matches `env/dev`). It is applied by EnvBridge to the
full listing rather than with `op item list --tags` (which `op-js` shows exists, with any-of semantics according to user
reports): item path segments are titles only when unique in the vault, and working them out from a filtered list could
address an item by a title an untagged item shares. Connect's `filter` query parameter is documented for `title eq` only, so
Connect is filtered the same way. A reference that names an item (`op://vault/Item/field`) is resolved whatever its tags.

### Tests

`SecretMetadataPlatformTest`: Infisical listings with tags, rotation flag and reminder (other listings unchanged), tag choices and
`tagIds` writes (replace, clear, unknown slug refused before writing), a new secret's tags in its create request, the older
tags route, 403 on tags, *Read-only*; rotation details read without values, *Rotate Now* by rotation type, 403 on listing and on
rotating, a rotated secret's value and name refused while comment and tags go through (`MockInfisical`). Vault: no metadata
request while listing (also recursive), details on demand, editing, KV v1 without metadata, 403 on reading and writing
metadata with the value edit still working (`MockVault`). 1Password: tags on items and fields, *Only items tagged* on folders
and the recursive listing (nested tags), an item named explicitly still read, through Connect and `FakeOp`. Tooltip lines,
badges and the `key=value` parser. Integration: `VaultIntegrationTest` / `OpenBaoIntegrationTest` read seeded
`custom_metadata`, write, replace and clear it (an empty map clears it on both servers), get 403 where the policy allows only
reading metadata (the value write still works) and none at all; `InfisicalIntegrationTest` lists the seeded tags, creates a
secret with a tag, changes and clears tags, and copies a secret with its tags (v0.167.13). Secret rotations need a database
or cloud connection to rotate, so they are tested against the mock only.

## Editor integration (M3, done)

Language-agnostic by design: variable names are found by text patterns, so completion (`completion.contributor language="any"`),
hover and gutter (`externalAnnotator language=""`) and the missing-secret inspection (no language) work in every file type. Inlay
hints have to be registered per language, so they are, in the optional configs of the plugins that define the languages (Java,
Kotlin, JavaScript/TypeScript, Python, Go, YAML, Properties, XML, Shell, `.env`, PHP, Ruby, Rust, plain text). A keys-only index
(`KeysIndex`, see [the keys index](#the-keys-index-what-it-reads-and-when)) feeds them; it never holds values.


- Completion of secret keys in `System.getenv("…")`, `process.env.…`, `Bun.env.…`, `os.environ[…]` / `os.getenv`, `os.Getenv`,
  `Environment.GetEnvironmentVariable`, `${VAR}` in Compose / YAML / properties / `.env.example`, and in the environment variables
  table of run configurations. *Go to declaration* selects the secret in the tool window.
- Masked inlay hints (`Infisical · dev · ••••`) and hover with comment, environments that define the key, *Reveal* and *Copy*.
- Inspection: referenced variable missing from the bound environment (quick fixes: switch environment, open dashboard).
- Console masking: injected values are replaced by `••••` in the Run and Debug consoles.
- Status bar widget: bound project and environment, with a quick switcher. Environments matching the *prod guard* pattern
  (default `prod*`) are shown in red and ask for confirmation before a launch.
- *Run with environment…*: run a configuration once against another environment without editing it.
- Environment coverage and comparison: which environments define a key, and a keys-only diff of two environments.
- Personal overrides: Infisical personal overrides are honoured (`includePersonalOverrides`).

## Second provider: HashiCorp Vault and OpenBao

Chosen next (over 1Password, Doppler and the cloud managers) for reach: Vault is the enterprise default and OpenBao, its
open-source fork under the Linux Foundation, shares its API, so one provider covers both. No good JetBrains integration
exists for either. 1Password is the likely provider after it.

**Scope, first version:** reading and injecting (sign-in, tool window, references in run configurations, terminal, `.env`,
editor hints), then writing fields (see *Writes* below). Engines: **KV v2**, **KV v1** (since the version after the first, see
[KV version 1](#kv-version-1)) and **namespaces** (Vault Enterprise / HCP Vault, OpenBao). Dynamic secrets are a later candidate.

### Model mapping

| EnvBridge | Vault / OpenBao |
|---|---|
| Project | A KV mount, version 1 or 2 (`secret`, `kv`, …) |
| Environment | A namespace (`root` when the instance has none, or the connection's namespace) |
| Folder | A path below the mount (`app/`, `app/db`) |
| Secret (key = value) | A field of a secret (`password` in `secret/app/db`) |

A secret path holds several fields, so a path is shown as a folder whose entries are its fields.

### References

`vault://<mount>/<path…>#<field>`, as Vault users write paths (`vault kv get secret/app/db`):

```
vault://secret/app/db#password        field password of secret/app/db
vault://secret/app/db#*               every field of that secret (scope import)
vault://secret/app/db#password?namespace=team-a&connection=<name>
```

The mount is always written, so a reference alone says which secret it is; the namespace comes from the binding unless
given. Each provider now brings its own reference grammar (`SecretProvider.referenceGrammar`); `infisical://` keeps its
`env:folder/KEY` form.

### API (KV v1 and v2, namespaces via `X-Vault-Namespace`, token via `X-Vault-Token`)

| Purpose | Call |
|---|---|
| Mounts the token can use | `GET /v1/sys/internal/ui/mounts` (type `kv` / `generic`, with their versions); no compatibility promise, so mounts can also be listed in the connection |
| A mount's KV version | `GET /v1/sys/internal/ui/mounts/<mount>` → `data.options.version` (see [KV version 1](#kv-version-1)) |
| Folders and secrets of a path | KV v2 `LIST /v1/<mount>/metadata/<path>`, KV v1 `LIST /v1/<mount>/<path>` (`GET …?list=true`); keys ending in `/` are folders |
| Fields of a secret | KV v2 `GET /v1/<mount>/data/<path>` → `data.data`, KV v1 `GET /v1/<mount>/<path>` → `data`; non-string values are passed as JSON |
| Check / renew the token | `GET /v1/auth/token/lookup-self`, `POST /v1/auth/token/renew-self` |

### Sign-in (browser first; tokens and secrets only in the IDE's password storage)

| Method | Flow |
|---|---|
| **OIDC (browser)** | `POST /v1/auth/<mount>/oidc/auth_url` with the role and `redirect_uri` `http://localhost:8250/oidc/callback` (the Vault CLI's, so roles already allowing the CLI work), open the system browser, receive `code` and `state` on the hardened loopback listener, check `state`, then `GET /v1/auth/<mount>/oidc/callback?code&state` → `auth.client_token` |
| Token | A pasted token (or the Vault CLI's `~/.vault-token`), checked with `lookup-self` |
| AppRole | `POST /v1/auth/<mount>/login` with `role_id` and `secret_id` (the secret in password storage) |
| Userpass / LDAP | `POST /v1/auth/<mount>/login/<username>` with the password |

Auth mount paths default to the method names (`oidc`, `approle`, `userpass`, `ldap`) and can be changed. Renewable tokens
are renewed before they expire; otherwise the user signs in again (the launch fails closed, as with Infisical).

### Implementation notes (first version)

- Mounts are listed in the sign-in namespace and offered for every namespace of the connection (its namespace plus *More
  namespaces*); a mount missing in another namespace reads as empty there.
- The OIDC listener binds `127.0.0.1` (never all interfaces) while the redirect URI says `localhost`, as the Vault CLI's does;
  browsers fall back from `::1` to `127.0.0.1`. The callback must carry the `state` from `auth_url`, and a `client_nonce` ties the
  callback to this IDE's request.
- A token connection's token (or the CLI's `~/.vault-token`) is the user's own: sign-out forgets it but never revokes it. Tokens
  from OIDC, AppRole, userpass and LDAP are revoked on sign-out.
- **Writes**: a write changes one field of a KV v2 secret. The client reads the secret (`GET data/<path>`, fields as JSON and
  `metadata.version`), changes the field and writes the whole secret as a new version with `options.cas` = the version read
  (0 for a new secret), so a concurrent change fails with "check-and-set parameter did not match" instead of being lost, and
  other fields keep their JSON types. Removing the last field deletes the latest version (`DELETE data/<path>`, a soft delete
  Vault can undo); a deleted latest version still reports its version, which the next write names. Moves copy all fields into
  the destination in one version, then re-read the source and remove them in another; a failed removal is reported per key
  (`notRemoved`) with both copies kept. KV v2 has no per-field comments, so comments are ignored. Writes at the mount's root
  (`/`) are refused: fields live in secrets. `createEnvironment` is refused (namespaces are an administrator's job).
  Integration tests cover writes on real Vault and OpenBao, including check-and-set and a move between namespaces.

### KV version 1

KV v1 mounts work like KV v2 ones for everything: listing, reading, references and launches, and every write the client
implements (`createSecret`, `updateSecret` including the rename to `newKey`, `deleteSecret`, `moveSecrets`; the SPI has no
`copySecrets`, copying in the tool window is a create per secret). Only the API differs:

| | KV v2 | KV v1 |
|---|---|---|
| Read | `GET <mount>/data/<path>` → `data.data`, `data.metadata.version` | `GET <mount>/<path>` → `data` |
| List | `LIST <mount>/metadata/<path>` | `LIST <mount>/<path>` |
| Write | `POST <mount>/data/<path>` `{"options":{"cas":<version>},"data":{…}}` | `POST <mount>/<path>` with the fields as the body (replaces the whole secret) |
| Remove the last field | write, then `DELETE <mount>/data/<path>` (soft delete of the latest version, restorable) | `DELETE <mount>/<path>` (gone for good; nothing is written first) |

**Detecting the version** follows the Vault CLI (`vault kv`'s preflight request, `kvPreflightVersionRequest`): `GET
sys/internal/ui/mounts/<mount>`; type `kv` (older servers: `generic`) with `data.options.version` `"2"` is KV v2, a missing or
null `options`, a missing or empty version, or `"1"` is KV v1. Another engine type is refused (CONFIGURATION) rather than read
as KV. Listing the mounts (`sys/internal/ui/mounts`) gives every mount's version too, so mounts found that way need no
preflight. The result is remembered per connection (id and address), namespace and mount (`VaultKvVersions`, memory only, 10
minutes, cleared on sign-out); any failed operation on the mount forgets it, so the next one asks again. That covers a mount
upgraded meanwhile (`vault kv enable-versioning`): a KV v1-style request to a KV v2 engine is answered with 404 and the engine's
warning "Invalid path for a versioned K/V secrets engine", which EnvBridge reports ("is a KV version 2 engine now; refresh")
instead of reading it as "no such secret", so a stale version can't make a secret look empty or send a write to a wrong path.

**When the preflight doesn't answer** (an old server without the endpoint, a proxy that blocks `sys/`, or a token Vault refuses
there; Vault also answers 403 for a mount that doesn't exist), the version is **probed at the operation's own path, the KV v1
way**: `GET <mount>/<path>` (or `LIST <mount>/` for the mount's root and for a first segment a KV v2 engine reserves: `data`,
`metadata`, `config`, `delete`, `undelete`, `destroy`, `subkeys`, `detailed-metadata`, whose KV v1-style read KV v2 would answer
itself). An answer means KV v1; a 404 with KV v2's "invalid path" warning means KV v2; a plain 404 (`{"errors":[]}`, no such
secret) means KV v1, since a KV v2 engine never answers a non-prefixed path that way; a 404 with an error (`no handler for
route`: no such mount) and anything else (403) say nothing. When neither tells, the operation uses **KV v2 and nothing is
remembered** (the behaviour before KV v1 support, so no KV v2 setup changes). Why this order rather than "try the KV v2 path and
fall back to KV v1 on 404": a KV v2 404 is ambiguous (a KV v1 mount answers `GET <mount>/data/<path>` with a plain 404 too, as
does a KV v2 secret that doesn't exist yet), and a KV v2 write to a KV v1 mount isn't refused at all: it would create a secret
literally named `data/<path>` holding `options` and `data`. Probing the KV v1 path is unambiguous in the cases that matter
(policies written for a KV v1 mount allow `<mount>/<path>`; policies for KV v2 allow `<mount>/data/…`, so the probe is refused
and KV v2 is used), and a wrong guess of KV v1 on a KV v2 mount only produces the engine's 404 warning, never a write. The
preflight endpoint authorizes any token that has some capability below the mount, so in practice the probe is only needed for
servers or proxies that hide the endpoint.

**No concurrency control on KV v1.** KV v1 has no versions, no metadata endpoint and no check-and-set: every write is a
read-modify-write of the whole secret (read the fields as stored JSON, change one, `POST` all of them). A change someone else
makes to the same secret between EnvBridge's read and its write (milliseconds) is overwritten, and **can't be detected**;
likewise a field added by someone else in the instant before the last field's removal is deleted with the secret. The rest of
the safety rules are unchanged: other fields keep their values and JSON types; a rename is one write of the whole secret with
the field renamed in place, refused before anything is written when the new name is taken; a move writes the destination
first, then re-reads the source and removes the fields (a failed removal leaves both copies, reported in `notRemoved`);
conflicts at the destination refuse the whole move before anything is written unless *Overwrite*. Moves work across KV
versions (each side uses its mount's API).

**Dashboard link**: `dashboardUrl` runs on the EDT and only uses the remembered version: KV v1 opens the Vault UI's
`/ui/vault/secrets/<mount>/list` and `/show/<path>` routes, KV v2 (or unknown) `/kv/list` and `/kv/<path>/details` as before.

### Tests

A mock Vault server (like `MockInfisical`): KV v2 and KV v1 mounts (`mounts`: version per mount, also "no options"), the mount
preflight (can be refused), KV v2's 404 warning for non-prefixed paths, LIST, reads and writes of both versions, namespaces, each
auth method including the OIDC round trip, expired tokens and permission errors; the grammar round-trips; launches resolving
`vault://` references. KV v1 tests: listing and reading by preflight, by the mount listing (no preflight) and by the probe (KV v1,
KV v2, a new secret, a reserved name); creating, changing, renaming and deleting fields (the whole secret as the body, no
`cas`, the secret deleted with its last field); conflicts refused before writing; moves within KV v1, across namespaces and in
both directions between KV v1 and KV v2 (destination written first); a source that refuses the removal; a mount upgraded to
KV v2 meanwhile; the dashboard link per version; launches. `KeysIndexScopePlatformTest` counts the requests of a keys index
refresh: bound to `secret/app/db` 3 (mounts, LIST, GET) against 9 for the old whole-mount read of the mock, and hover / the
missing-secret inspection still knowing the bound secret's fields while another path is unknown. Integration tests (`scripts/integration/setup.sh` mounts a KV v1
engine `legacy` on Vault and OpenBao, also in OpenBao's `team-a` namespace) cover listing, reading, the preflight and the probe
against the real servers, create / update / rename / delete, moves between KV v1 secrets, KV v1 ↔ KV v2 and across namespaces,
and launches.

### Known limits (keys index)

- The keys index reads only the bound secret path and the paths below it (see [the keys index](#the-keys-index-what-it-reads-and-when)):
  completion and hover don't know other secrets of the mount. Bound to the mount's root (`/`) it walks the whole mount, one LIST
  per folder and one GET per secret (at most `MAX_RECURSIVE_PATHS` = 500; more leaves the folder index empty), once per refresh
  interval.

## Third provider: 1Password

Chosen after Vault for reach among individual developers and small teams: many already keep their API keys in 1Password and
write `op://` references for `op run` / `op inject`. EnvBridge reads those same references, so a `.env` or run configuration
written for the CLI works in the IDE unchanged, without `op run` wrapping every launch.

**Scope:** reading and injecting (tool window, references in run configurations, terminal, `.env`, editor hints) in the
first version; creating, changing, moving and deleting fields since then (see [Writes](#writes-1password)).
`supportsWrites` is true, so the generic write actions (tool window *New / Edit / Move / Delete*, `.env` *Extract value to …*)
work for 1Password connections, behind the same *Read-only* setting as every other provider.

### Model mapping

| EnvBridge | 1Password |
|---|---|
| Project | A vault (ID = vault ID, name and slug = vault name) |
| Environment | None in 1Password: every vault has one, `default` (`defaultEnvironment()`) |
| Folder | An item: `/<item title>` (the item's ID when its title is shared with another item, contains `/` or is blank). A section is addressable as `/<item>/<section>` (label, else ID) but not shown as a folder |
| Secret (key = value) | A field with a value (key = its label, else its ID). Empty fields are left out |

`listFolders("/")` lists the vault's items; `listSecrets("/<item>")` returns every field of the item, top-level fields first (a
section without a label, such as the CLI's `add more`, counts as top level), then by section; each entry's `path` names its
section. `listSecrets("/<item>/<section>")` returns one section's fields. `listSecrets("/", recursive)` reads every item of the
vault, at most 100 (each is a CLI call or request; more is a CONFIGURATION error naming item imports instead). Sections are not
shown as folders because a reference may leave the section out (below): listing their fields under the item keeps one place
per field in the tool window, and *Copy reference* gives the short form.

### References

1Password's [secret reference syntax](https://developer.1password.com/docs/cli/secret-reference-syntax/), scheme `op`:

```
op://<vault>/<item>/<field>               field of the item (any section; top-level fields first)
op://<vault>/<item>/<section>/<field>     field of a section
op://<vault>/<item>/*                     every field of the item (EnvBridge extension, scope import)
op://<vault>/<item>/<section>/*           every field of a section
op://<vault>/**                           every field of every item (EnvBridge extension, capped)
op://<vault>/<item>/<field>?connection=<name>
```

`OnePasswordGrammar` maps the vault to `projectId`, `/<item>[/<section>]` to `path` and the field to `key`; `environment` is
always left open (the binding's, `default`). Decisions:

- **Names** are written raw, spaces included, as 1Password writes them (so a reference copied from the tool window works with
  `op run` too). Characters outside 1Password's allowed set (letters, digits, `-`, `_`, `.`, space) are percent-encoded on
  output and decoded on input; `%2F` is refused (1Password requires the ID for a name with `/`). Raw leading or trailing
  blanks are refused as typos (`%20` writes them). Stars count only when written raw: `%2A` is a field called `*`.
- **The vault is always written** (`build()` puts the vault ID there; the binding can't shorten an `op://` reference).
- **`?attribute=`**: only `value` (the default) is accepted. `otp`, `type` and `ssh-format` would need a value that isn't a
  field's value; they are refused with a message rather than resolving to something else. Unknown parameters are refused.
- **Section omitted**: resolution takes the first field with that label in the item, top-level first. As far as known
  (unverified, see limits) `op read` also finds a field without its section when the label is unique.
- **Case**: field labels match case-sensitively (core compares keys exactly); vault and item names are passed to `op`
  (which matches them itself) or, for Connect, matched exactly first and then ignoring case when that is unique.
- **Bound project**: a reference names the vault by name (`op://Dev/…`) while the binding stores its ID. *Only use the bound
  project* therefore accepts a reference that names the bound project by its slug or name (`LaunchSecretResolver.namesProject`,
  a generic core change; only when the IDs differ is the project list consulted).

### Access methods

| Method | How | Credentials |
|---|---|---|
| **1Password app (through the CLI)** (default) | `op` as a child process: `op vault list`, `op item list --vault <v>`, `op item get <item> --vault <v> --reveal`, `op whoami`, all `--format json`, optional `--account <shorthand / sign-in address / ID>`. The 1Password app authorizes the CLI (biometrics / system password) | None in the IDE. `SLOT_SESSION` only remembers that the user signed in (so background features don't run `op` for a user who never did) |
| **Service account (through the CLI)** | The same commands | The token is a secret option (PasswordSafe), given to `op` only as `OP_SERVICE_ACCOUNT_TOKEN` in its environment, never on argv |
| **1Password Connect server** | `GET /v1/vaults`, `GET /v1/vaults/{vaultId}/items`, `GET /v1/vaults/{vaultId}/items/{itemId}` (writes: `POST …/items`, `PUT …/items/{itemId}`, see [Writes](#writes-1password)) with `Authorization: Bearer`. Names are resolved to IDs by listing (one list per client, i.e. per operation); a 26-character ID is fetched directly | The access token is a secret option (PasswordSafe) |

The CLI methods don't use the connection's base URL. A small generic SPI addition, `AuthMethod.usesBaseUrl` (default true),
lets the connection dialog hide the address for them and lists not show it; a valid URL is still saved (the previous one, else
the provider's first preset, `http://localhost:8080`, the usual local Connect address) so switching the method later works.

Finding `op`: the connection's path if set (an error if it doesn't exist; no fallback to another binary), else `PATH`, then the
usual install locations (`/opt/homebrew/bin`, `/usr/local/bin`, `/usr/bin`, `/snap/bin`, `~/.local/bin`; on Windows WinGet's
links and `Program Files\1Password CLI`), because an IDE started from the Dock or Start menu may lack the shell's `PATH`. Not
found: CONFIGURATION with an install hint.

Process handling: `ProcessBuilder`, stdin closed at once (`op` must never wait for a password), stdout and stderr drained on
their own threads with size caps, polling for cancellation (progress indicator, sign-in interaction), timeouts of 120 s for the
app (the user may have to approve) and 60 s for service accounts. `--reveal` (op 2.24+) makes sure concealed values are in the
JSON; an `op` that doesn't know the flag is called again without it.

Errors from `op` (exit code non-zero) are classified from stderr, shown without the `[ERROR] <date> <time>` prefix, on one
line, at most 300 characters: not signed in / authorization dismissed / app not reachable / invalid token → AUTH_REQUIRED;
"isn't a vault / item" → NOT_FOUND; "more than one … matches" → CONFIGURATION ("name it by its ID"); permission → FORBIDDEN;
network → NETWORK. Connect: 401 → AUTH_REQUIRED, 403 → FORBIDDEN, 404 → NOT_FOUND, its `message` shown shortened.

Sign-out: the app method forgets the "signed in" marker (the app's own authorization of the CLI expires by itself); service
account and Connect forget the stored token.

### Writes (1Password)

Every write is a **read-modify-write of one item**: read the item's whole JSON (CLI `op item get … --reveal`, Connect `GET`),
change it (`OnePasswordEdits`: add / change / remove fields, set notes), save it whole. Everything not touched (URLs, tags,
other fields, the read `version`) is sent back as it was read. After a save, the item 1Password returns is checked by field
IDs (never values): a field that should exist but doesn't, or should be gone but isn't, is a SERVER error ("didn't apply the
change"), so a CLI that ignores or merges the piped JSON can't pass for success.

| Operation | `/<item>` or `/<item>/<section>` | `/` (vault root) |
|---|---|---|
| `createSecret(key, value, comment)` | Adds a **concealed** field labelled `key` to the item's top level, or to the section (by label, else ID; a missing section is NOT_FOUND, sections aren't created). Refused (CONFIGURATION) when the item has a field whose key is `key` **anywhere** (any section): a reference without section must stay unambiguous. A non-blank `comment` is **appended** to the item's notes as `key: comment` (an existing item's notes are never replaced by a create) | Creates a **new item** titled `key`, category **Password**, value in its `password` field (purpose `PASSWORD`), `comment` as its notes. Refused (CONFIGURATION) when the vault has an item with that title (ignoring case): to add a field, choose `/<item>` |
| `updateSecret(key, value, comment?)` | Sets the field's value (found as listings and references find it: in the section if given, else top-level first). A non-null `comment` **replaces the item's notes**: 1Password fields have no comment, so listings give every field the item's notes as `SecretEntry.comment`, and *Edit Secret* shows and edits them | CONFIGURATION (a root holds items, not secrets) |
| `deleteSecret(key)` | Removes the field. A **built-in** field (one with a `purpose`: a login's username / password, the notes) is **emptied** instead: items keep their built-in fields, and empty fields aren't listed, so it disappears from the tool window | CONFIGURATION: **EnvBridge never deletes items.** The tool window shows no secrets at a vault's root (items are folders), so no UI action asks for it; deleting / archiving an item is left to 1Password |
| `moveSecrets(…, destination, overwrite)` | Different items (also across vaults): every field is copied into the destination item in **one save** (with `overwrite`, a field with the same key there gets the value; without it, any such field refuses the whole move before anything is written), then removed from the source item in **a second save** (read again first, for its current version). If the copy's save fails, nothing changed and the error is thrown; if the removal fails, every key is in `MoveResult.notRemoved` with the reason (it exists in both items). Same item, other section: the fields just change section, one save | As destination: CONFIGURATION (choose an item). As source: CONFIGURATION |
| `createEnvironment` | CONFIGURATION (1Password has no environments) | |

Decisions:

- **Root items are Password items, not API Credentials.** A Password item's layout is exactly one concealed field plus notes,
  which is what a key / value / comment is. An API Credential item comes with `username`, `type`, `filename`, `hostname`,
  `valid from`, `expires` that would stay empty (and which a sparse template may or may not get filled in by the CLI / Connect),
  so its layout fits worse. The new item is addressed as `op://<vault>/<key>/password`; `SecretProvider.createdSecretLocation`
  (a small generic SPI addition, default: the scope's folder and the key) tells the *New Secret* dialog and *Extract value to …*
  that the secret is `/<key>` / `password`, so they select it and bind the `.env` line to a reference that resolves.
- **New fields** get a random 26-character 1Password-style ID (lowercase letters and digits) not used in the item, type
  `CONCEALED`; moved fields keep their type but not their `purpose` (the destination may have its own built-in field).
- **No item deletion at all**, so the Connect `DELETE /v1/vaults/{v}/items/{id}` endpoint and `op item delete` are not used.
  (`op item delete` moves an item to *Recently Deleted* unless `--archive` is given; whether Connect's DELETE is permanent is
  not documented clearly enough to rely on.) An item whose last custom field was deleted or moved stays, empty.
- **The New Secret dialog's folder** completes `/` plus `/<item>` for every item (`allFolders` over `listFolders`); sections
  aren't offered (they are typed as `/<item>/<section>`). Its default is the selected node's folder: an item, a secret's
  `/<item>/<section>`, or the vault's root (then a new item). Its key pattern (`[A-Za-z_][A-Za-z0-9_.-]*`) is also a valid item
  title and field label.

**Connect:** `POST /v1/vaults/{v}/items` (the template plus `vault.id`) and `PUT /v1/vaults/{v}/items/{id}` with the whole item,
including the `version` it was read with. **409** (and 412) is CONFIGURATION "the item was changed meanwhile (…); refresh and try
again". As far as known, Connect rejects a PUT with an outdated `version`; if a server doesn't, the last write wins within the
read-modify-write window (milliseconds). `PATCH` (RFC 6902 on `fields`) would send less, but PUT of the item as read keeps
everything else unchanged and is one code path with the CLI. The HTTP client is now pinned to **HTTP/1.1**, like the other
providers' (over plain `http://`, the usual local Connect address, Java's default HTTP/2 sends an h2c upgrade that breaks
request bodies on some servers).

**CLI (app and service account): a value is never on `op`'s command line** (other local processes can read argv). That rules
out `op item edit <item> 'field=value'` assignment statements entirely; there is no fallback to them. Instead:

| Operation | Command | stdin |
|---|---|---|
| Create a root item | `op item create --vault <vault> --format json [--account …]` | The item template JSON (`title`, `category: PASSWORD`, `fields`) |
| Change an item | `op item edit <item ID> --vault <vault ID> --format json [--account …]` | The whole item JSON as read with `op item get`, changed |

`OnePasswordCli.run` writes the JSON to stdin from its own thread (so a process that never reads it can't block past the
timeout) and closes it; for reads stdin is still closed at once. Neither stdin nor stdout is logged. Before an edit, an item
whose concealed values came back masked (`[use 'op item get … --reveal' …]`, an `op` that didn't reveal) is refused, so the
placeholder can never overwrite the real values.

`op` features relied on, and confidence (from 1Password's documentation and release notes as remembered, **not verified
against a live account or a real `op` binary** while writing this; the tests use a fake `op`):

- `op item create` reads an item JSON template from **piped stdin** (documented, e.g. `op item get … --format json | op item
  create --vault …` to copy an item; the template shape is `op item template get Password`'s): **high**.
- `op item edit <item>` reads the **whole item JSON from piped stdin** and makes the item match it (fields left out are
  removed), as with `--template` (added in op 2.x, used with the output of `op item get --format json`): **medium**. If an `op`
  ignores the piped JSON or merges instead, the field-ID check after the save reports "didn't apply the change"; nothing is
  then claimed as done. A user with such an `op` can update it or use Connect.
- `op item edit` / `create` print the resulting item with `--format json`: **medium-high** (if they print nothing, the check
  is skipped).
- The CLI has no optimistic concurrency that EnvBridge can use: an item changed between `op item get` and `op item edit` (a
  fraction of a second) is overwritten.

### Dashboard link

*Open in Dashboard* for the CLI methods (desktop app, service account); Connect connections have none (a Connect server
belongs to no signed-in account, and its address is not 1Password's web app).

- **Account**: `op whoami --format json` (no secret on argv; a service account's token only in `op`'s environment as for every
  call) → `url` (the sign-in address, `https://my.1password.com`) and `account_uuid`. Run at sign-in (after `vault list`, which is
  what makes the app authorize the CLI) and, when nothing is remembered yet, after `vault list` in `listProjects` (background;
  a failure only means no link). `url` must be `https://` with a host; otherwise, or without `account_uuid`, there is no link.
- **Item IDs**: the tool window's item folders may be titles; `listFolders` remembers each folder segment's item ID per vault.
  A segment that is itself an ID needs nothing.
- Both live in memory only (`OnePasswordDashboard`), per connection id, method and account option, and are forgotten on
  sign-out. An account UUID and item IDs are not secrets (1Password puts them in its own links).
- **`dashboardUrl` is called on the EDT** (the tool window's *Open in Dashboard* action), so it never runs `op`: it builds the
  address from what is remembered, or returns null before the account is known (in practice the vault list that shows the
  connection's vaults has filled it).
- **Address**: an item, a section or a field (any path below a vault whose project ID is a vault ID) opens through the private
  link `https://start.1password.com/open/i?a=<account UUID>&v=<vault ID>&i=<item ID>&h=<sign-in host>`, the format the 1Password
  apps' *Copy Private Link* produces, which opens the item in the 1Password app or the web app for the right account. A vault,
  the connection, or an item whose ID isn't known opens the account's web app (`https://<sign-in host>/`, which shows the
  vaults after sign-in): the web app's own routes for a single vault are not documented, so no deeper link is guessed.

### Security notes

- No secret on a command line: argv carries only vault / item names and IDs, flags and the account. Values written go to
  `op` on stdin, as part of the item's JSON. The service account token goes
  through the child's environment, which other users' processes can't read.
- Inherited `OP_SERVICE_ACCOUNT_TOKEN`, `OP_CONNECT_HOST`, `OP_CONNECT_TOKEN` and `OP_SESSION_*` are removed so `op` uses the
  connection's credentials only (`OP_ACCOUNT` is also removed for service accounts).
- Neither `op`'s output nor Connect's bodies are logged; `OpField.toString()` leaves the value out; values become `SecretValue`s.
- The path to `op` is a setting the user chose; EnvBridge does not verify the binary's signature (1Password documents how to).
- Connect follows the other HTTP clients: IDE proxy and certificates, no redirects, `https://` except loopback.

### Tests

- `OnePasswordGrammarTest`: parsing, formatting and round trips (sections, `*`, `**`, encoded names, a field called `*`),
  building references, malformed references with their messages, other schemes unaffected.
- Dashboard link: the private link after sign-in and listing (item by title once listed, by ID at once, a field in a section,
  a vault by name falling back to the account), nothing before sign-in or after sign-out, a service account's `whoami` run once
  while listing (token never on argv), no link without `account_uuid` or with a non-`https://` address, none for Connect.
- `FakeOp`: a POSIX shell script written to a temp dir that answers `vault list`, `item list`, `item get`, `whoami` from
  fixture JSON, logs argv and whether `OP_SERVICE_ACCOUNT_TOKEN` was set, can require a token, act signed out, and report an
  ambiguous title like `op` does. `MockOnePasswordConnect`: a JDK HttpServer with the three Connect endpoints, bearer check,
  403 and 404.
- `KeysIndexScopePlatformTest`: a keys index refresh bound to an item reads that item only (one `op item get`, against `item
  list` + four `item get` for the vault before); bound to a vault, an unchanged vault costs one listing, an item another client
  changed (`FakeOp.touch`, `MockOnePasswordConnect.touch` raise its version) is read again with its new value and the others
  aren't; sign-out forgets the items; an item written through the IDE is read again although the fake's listing keeps its version.
- `OnePasswordPlatformTest`: each method lists vaults, items and fields; sections by label and ID; duplicate titles; the
  whole-vault import and its cap; the token only in the environment; errors (op missing, not signed in, wrong token, missing
  item, ambiguous title, Connect 401 / 403); launches resolving `op://` references against a bound vault (vault by name and by
  ID, section given or not, item import, fail closed, another vault refused while restricted).
- Writes: `FakeOp` also answers `item create` / `item edit`, which read the item JSON from stdin, store it (edits are seen
  under every name through symlinks) and **fail when stdin is empty or an argument is an assignment statement**; it can refuse
  to edit one item (permission error) and ignore edits. `MockOnePasswordConnect` keeps items in memory with versions: `POST`,
  `PUT` (409 unless the body carries the current `version`), `DELETE`; it can refuse PUTs of an item and change one "meanwhile".
  `OnePasswordPlatformTest` covers, on the CLI and on Connect: creating a field (top level, section, comment into notes),
  duplicate label refused, root-level Password item (template, notes, existing title refused), updating value and notes,
  deleting a field and emptying a built-in one, root deletion refused, moving between items and sections, overwrite refused
  then allowed, a move whose source can't be saved (`notRemoved`, copy kept), a Connect conflict (409 → "changed meanwhile",
  nothing changed), an `op` that ignores the piped item, masked values refused, every written value absent from argv, and
  *Read-only* blocking each write before anything is sent.

### Known limits and uncertainties

- **JSON shapes are from documentation and memory, not verified against a live account** while writing this: CLI items
  (`id`, `title`, `vault`, `category`, `sections[] {id, label}`, `fields[] {id, type, purpose, label, value, section {id,
  label}, reference}`), CLI vaults (`id`, `name`), `whoami` (`url`, `email`, `user_type`, `account_uuid`), Connect vaults (`id`, `name`) and
  items (`sections[]`, fields with `section {id}` only). The parser is lenient (missing keys are skipped, a non-string `value`
  is passed as JSON, `null` / empty output is an empty list).
- Whether `op read` resolves `op://vault/item/field` for a field inside a section is assumed, not verified; EnvBridge does.
- The `op` error texts used for classification are known examples; an unknown text is SERVER with the text shown.
- `--reveal` behaviour of older CLI versions is assumed (retry without it on "unknown flag").
- Background features (completion's keys index, every 10 minutes or the cache TTL if longer) run `op` for a signed-in app
  connection: one `op item get` for the bound item, or, bound to a vault's root, `op item list` plus one `op item get` per
  changed item. When the app's authorization of the CLI has expired, the app may ask to approve at that moment. Signing out stops it.
- The keys index reads only the bound item: completion and hover don't know other items' fields (they still resolve at launch).
- That `op item list --format json` reports `version` and `updated_at` per item is taken from 1Password's `op-js` type
  definitions (`Item`, `version?: number`, `updated_at: string`, the type it gives `item list`), and Connect's `version` /
  `updatedAt` from `connect-sdk-go` (`onepassword.Item`, which its `GetItems` decodes the list into); not verified against a live
  account or server. The fixtures (`FakeOp`, `MockOnePasswordConnect`) list both. Without either field every item is read.
- `?attribute=otp`, `?attribute=type`, `?ssh-format=` and field lookup by field ID (except where ID and label are equal, as for
  built-in `username` / `password`) are not supported.
- A whole-vault import makes one `op` call per item that changed since this connection last read it (≈0.5 s each; the first
  time, every item); it is capped at 100 items.
- The dashboard link's private-link format (below) is from 1Password's apps as remembered, not verified against a live
  account while writing this.
- Writes through the CLI rely on `op item edit` accepting piped item JSON (confidence above); there is no argv fallback.
- Writes don't check that the item is unchanged since the tool window showed it, only since the operation read it (Connect:
  `version`; CLI: none). Items are never deleted; sections aren't created; moving into a vault's root is refused.
- A whole item is sent back on every write, values of all its fields included (to the same `op` / Connect server it was read
  from, which already holds them).

## Fourth provider: Doppler

Chosen after 1Password because Doppler is popular with application teams that want hosted secrets per environment without
running a server, and has no JetBrains integration. Its model (projects, environments, configs of flat key/value secrets) is
close to Infisical's, so reading **and writing** fit the existing SPI with no core changes.

**Scope, first version:** reading and injecting, plus every write the tool window offers (create, edit with note, move between
configs, delete, new branch config). Doppler is SaaS only and can't be self-hosted, so there are **no integration tests against a
real server**: everything is tested against a mock of the API (below).

### Model mapping

| EnvBridge | Doppler |
|---|---|
| Project | A Doppler project (ID = slug = the project's name as the API takes it, `backend`) |
| Environment | A Doppler **config**: `dev`, `dev_personal`, `stg`, `prd`, branch configs such as `dev_feature`. Configs hold the secrets; Doppler's environments only group them. Shown as the config's name, branch configs as `dev_feature (branch of dev)` |
| Folder | None: a config is a flat list. `listFolders` is empty; any path other than `/` is NOT_FOUND ("Doppler has no folders") |
| Secret (key = value) | A secret of the config, with Doppler's **computed** value (Doppler references `${KEY}`, `${env.config.KEY}` resolved by the server); the comment is the secret's note |
| Organization (dashboard links) | The workplace (from `/v3/me`) |

`defaultEnvironment()` is `dev` (Doppler creates `dev`, `stg` and `prd` in every new project). Doppler adds `DOPPLER_PROJECT`,
`DOPPLER_CONFIG` and `DOPPLER_ENVIRONMENT` to every config; `listSecrets` leaves them out, so scope imports don't inject them
(they describe the binding, which the IDE already knows; `doppler run` sets them, EnvBridge deliberately doesn't). A reference to
one of them is therefore NOT_FOUND. Secrets whose value is restricted (`computed` null or `computedVisibility: restricted`) are
listed with `valueHidden`. Dynamic secrets are not requested (`include_dynamic_secrets=false`).

### References

The default path grammar with scheme `doppler`, without folders (`DopplerGrammar`, a thin wrapper around `PathGrammar`):

```
doppler://DB_PASSWORD                   DB_PASSWORD of the bound config
doppler://prd:DB_PASSWORD               DB_PASSWORD of config prd of the bound project
doppler://dev_personal:*                every secret of config dev_personal (scope import)
doppler://DB_PASSWORD?project=web&connection=<name>
```

A folder segment or `?path=` is refused at parse time ("Doppler has no folders: write doppler://[config:]KEY"); `**` is accepted
and means `*`. Config names (lowercase, digits, `_`) always fit the `config:` prefix form.

### API (v3, `https://api.doppler.com`, `Authorization: Bearer <token>`)

| Purpose | Call |
|---|---|
| Account, workplace, token type | `GET /v3/me` → `name`, `type`, `workplace {slug, name}`, `user {email}` |
| Projects | `GET /v3/projects?page&per_page=100` → `projects[] {slug, name}` (paged, ≤ 20 pages) |
| Configs of a project | `GET /v3/configs?project&page&per_page=100` → `configs[] {name, environment, root, locked}` |
| Secrets of a config | `GET /v3/configs/config/secrets?project&config&include_dynamic_secrets=false` → `secrets {KEY: {raw, computed, note, computedVisibility}}` |
| Create / change secrets | `POST /v3/configs/config/secrets?project&config` `{secrets: {KEY: VALUE}}` (project and config also in the body) |
| Delete a secret | The same call with the value null, `{secrets: {KEY: null}}`, as `doppler secrets delete` sends |
| Secret note | `POST /v3/projects/project/note?project` `{project, secret, note}` (a note belongs to the project, shared by its configs) |
| New branch config | `POST /v3/configs?project` `{environment, name}` |
| Browser sign-in | `GET /v3/auth/cli/generate/2?hostname&version&os&arch` (no token) → `{code, polling_code, auth_url}`; `POST /v3/auth/cli/authorize` `{code: polling_code}` every 2 s while it answers **409**, until `{token, name, dashboard_url}` (a 200 with `error` = failed) |
| Sign-out | `POST /v3/auth/cli/revoke` `{token}` (`/v3/auth/revoke` is for service account identity tokens) |

`DopplerHttp` uses the JDK HttpClient like `VaultHttp`: IDE proxy and certificates, no redirects, HTTP/1.1, 10 s / 30 s timeouts;
the token only ever goes in the `Authorization` header, the polling code only in a request body, and no body is logged. Errors:
401 → AUTH_REQUIRED, 403 → FORBIDDEN, 404 → NOT_FOUND, 429 → SERVER ("Doppler rate limited the request; retry in N s" from
`Retry-After`), other 4xx → CONFIGURATION, 5xx → SERVER, I/O → NETWORK. Doppler's `messages` are shown, joined and capped at 300
characters; a message about a locked config becomes CONFIGURATION "the config is locked in Doppler: …" whatever the status.

### Sign-in

| Method | Flow | Credentials |
|---|---|---|
| **Browser sign-in** (default) | The Doppler CLI's `doppler login` flow: `generate_2` with the computer's name (`EnvBridge (<host>)`, the token's name in the user's Doppler token list), open `auth_url` in the system browser, show "check that Doppler shows the code `<code>`, then approve" as the progress text, poll `authorize` every 2 s (or the server's `poll_interval`), stop on cancel, give up after 5 minutes. Pending = 409 / 202 / a success without a token / a 400 saying "pending"; any other 4xx = declined or expired (AUTH_REQUIRED) | The CLI token in `SLOT_SESSION` (PasswordSafe), revoked on sign-out (local copy forgotten first, so a failed revoke never keeps it) |
| **Token** | A pasted personal (`dp.pt.`), service account (`dp.sa.`) or service token (`dp.st.`), checked with `/v3/me` on *Sign In* | A secret option (PasswordSafe). Sign-out forgets it, never revokes it (the user's own) |

The session slot also caches what `/v3/me` said (workplace, token type, user), keyed to the token, so listings ask once. A
browser session Doppler refuses (401) is forgotten, so the UI offers to sign in again; there is nothing to refresh. The
`auth_url` is opened only if it is `https` (or loopback, for tests).

**Service tokens** are bound to one config and may not list projects. When `/v3/me` reports a service token (or, if it reports no
type, the token starts with `dp.st.`), or listing projects is FORBIDDEN, `listProjects` offers just the token's project with its
one config: from `/v3/me` if it names them, else from that config's own `DOPPLER_PROJECT` / `DOPPLER_CONFIG` (a secrets read
without `project` / `config`, which Doppler answers for the token's config). Projects whose configs a token may not list are
shown without configs.

### Writes

All writes go through `SecretsService` (so *Read-only* and the prod guard apply) and are explicit user actions.

- **Names** must match `[A-Z_][A-Z0-9_]*` (Doppler's rule: uppercase letters, digits, underscores); checked before any request,
  with a suggested name (`db-url` → `DB_URL`).
- **Create** reads the config first and refuses an existing key ("edit it instead"); **update** refuses a missing one (NOT_FOUND).
  Both send one `POST /v3/configs/config/secrets`. Doppler's `change_requests` form isn't used (it needs the same permission and
  adds nothing here). A key that the config only inherits counts as existing (the listing doesn't tell them apart).
- **Notes**: a non-empty comment on create, or a changed comment on update, sets the secret's note. The value is written first;
  if the note then fails, the error says the value was saved. Notes are per project, so editing one in `dev` changes it for every
  config.
- **Delete**: `DELETE …/secret`, one request per key; a missing key is NOT_FOUND.
- **Move** between configs (and projects): checks every key exists and is readable in the source and, without *overwrite*, is
  absent from the destination (else nothing happens); copies the **raw** values (so `${…}` references keep referring) in one
  request; carries notes over when the project changes; then deletes each key from the source. A key whose deletion fails stays in
  both configs and is reported in `MoveResult.notRemoved`: a failure never loses a secret.
- **New environment** creates a **branch config**. Doppler names a branch config after its environment (`dev_feature` in `dev`);
  the dialog's slug may use hyphens (`dev-feature`), which become underscores. The longest existing environment that prefixes the
  name is used; otherwise CONFIGURATION with the allowed prefixes. The dialog's display name isn't used (a config has only a name).
  New Doppler environments (as opposed to configs) are created in the dashboard.
- **Locked configs**, config inheritance and access rules are Doppler's to enforce; their refusals come back as CONFIGURATION or
  FORBIDDEN with Doppler's message. A read-only service token gets FORBIDDEN on writes.

### Project file

`readProjectFile` reads the Doppler CLI's repository file `doppler.yaml` (also `doppler.yml`), looked up in the project directory
and its parents, so the binding picks up the project and config that `doppler setup` would. `setup:` may be one `{project,
config}` or a list of them with a `path` (relative to the file); the entry whose path is the project directory wins, then one for
the file's own directory, then the first. SnakeYAML's `SafeConstructor`, files over 256 KB ignored. The user's
`~/.doppler/.doppler.yaml` (which holds the CLI's tokens and scoped settings) is never read.

### Dashboard

`https://dashboard.doppler.com/workplace/<workplace>/projects/<project>/configs/<config>`, the workplace from `/v3/me`; without it
the dashboard's start page. For another API address, `api.<domain>` becomes `dashboard.<domain>`.

### Tests

- `DopplerGrammarTest`: keys, `config:` prefixes, `*` / `**`, `?project=` / `?connection=` round trips, folders refused,
  building short references; `doppler.yaml` in both forms, nested directories, malformed files.
- `MockDoppler`: a JDK HttpServer with `/v3/me`, projects, configs (one project forbidden), secrets with computed `${KEY}`
  values and the `DOPPLER_*` metadata, writes, delete, notes, branch configs, the browser sign-in (409 until approved after N
  polls, or declined), revocation, service tokens bound to one config, locked configs, failing deletions and a 429 with
  `Retry-After`.
- `KeysIndexScopePlatformTest`: a keys index refresh reads `dev`, `stg` and `prd` (3 requests, against 5 before: also
  `dev_personal` and `dev` a second time), coverage of keys over the root configs, every config still offered for switching, a
  bound personal config indexed; the cache rule (a reload within the interval reuses listings even with the cache TTL at 0, a
  cleared cache reads fresh, a longer TTL lengthens the interval).
- `DopplerPlatformTest`: registration; listings with each token type (service token offered alone, without listing projects);
  the browser sign-in round trip (code in the progress text, polling code only in bodies, token only in the header, never in
  exceptions or `toString`, revoked on sign-out), declined, cancelled and timed-out sign-ins, a revoked session; error mapping
  (401, 403, 404, 429); create / update / note / delete; name validation; a locked config; *Read-only*; move with a partial
  failure, conflicts and a cross-project note; branch configs; launches through `LaunchSecretResolver` with a binding (bound
  config, `config:` prefix, `?project=`, scope import without metadata, fail closed, *Only use the bound project*); `doppler.yaml`.

### Known limits and unverified assumptions

docs.doppler.com could not be reached from the build environment. The endpoints above were **checked against the Doppler CLI's
source** (`DopplerHQ/cli`, `pkg/http/api.go`, `pkg/http/http.go`, `pkg/models/api.go`, `pkg/cmd/login.go`, `pkg/cmd/secrets.go`):
the browser sign-in (`GET …/generate/2`, `authorize` with the polling code, 409 = pending, `POST /v3/auth/cli/revoke`), the
`/v3/me` fields (`workplace {name, slug}`, `type`, `name`, `token_preview`, `slug`), projects (`id, name, description`), configs
(`name, environment, root, locked, inheritable`), secrets (`secrets {KEY: {raw, computed, rawVisibility, computedVisibility,
note}}`), deleting with a null value, notes and error bodies (`messages`). The mock follows that wire format. Still unverified:

- Whether `/v3/me` names a service token's project and config (the CLI's model has no such fields; the config's metadata secrets
  are used).
- The note endpoint's shape (`project` sent both as query parameter and in the body).
- That a service token may read secrets without `project` / `config` parameters (the CLI relies on this).
- That `workplace.slug` is what the dashboard's `/workplace/<…>` path takes.
- Branch config names with digits and underscores only (hyphens become underscores); Doppler may allow more.
- No retry on 429: the error says when to retry. Pagination stops at a short page (≤ 2 000 projects or configs).
- `ENVBRIDGE_ENVIRONMENT` (or `INFISICAL_ENVIRONMENT`) on a run configuration picks the config (the core's override variable); `DOPPLER_CONFIG` /
  `DOPPLER_PROJECT` / `DOPPLER_TOKEN` in the IDE's or run configuration's environment are not read.
- The keys index reads root configs and the bound config only (`root` from the configs listing; when Doppler omits it, a name
  without `_` counts as root). Keys that exist only in another branch or personal config aren't shown as "defined elsewhere".

## Fifth provider: AWS Secrets Manager

Chosen after Doppler because it is where most teams on AWS already keep application secrets, and JetBrains has no
integration that injects them into run configurations. It is the first provider whose API needs **request signing** (AWS
Signature Version 4) rather than a bearer token.

**Scope, first version:** reading and injecting, plus every write the tool window offers (create, edit, rename, move, copy,
delete a key, new environment). Integration tests run against **LocalStack** in Docker; no real AWS account was used.
Added since: per-connection name layouts, batched and version-checked reads (fewer billed requests), descriptions, tags,
rotation and *Rotate Now*, and an in-IDE IAM Identity Center sign-in (sections below).

**No AWS SDK.** The SDK for Java v2 would add several megabytes and dozens of transitive artifacts for five API calls. The
plugin signs requests itself (`AwsSigV4`, about 150 lines, unit-tested against the AWS SigV4 test suite) and speaks the JSON 1.1
protocol with the JDK HttpClient like the other providers.

### Model mapping

The usual way to keep application configuration in Secrets Manager is **one secret per application and environment holding a
JSON object of key/value pairs** (the console's "key/value" secret type), named `<app>/<env>`: `myapp/dev` →
`{"DB_PASSWORD": "…", "API_KEY": "…"}`. EnvBridge maps that convention directly:

| EnvBridge | AWS Secrets Manager |
|---|---|
| Connection | An AWS account (credentials) in one region (the endpoint `https://secretsmanager.<region>.amazonaws.com`) |
| Project | The **name prefix**: everything before the last `/` of a secret's name (`myapp` for `myapp/dev`, `team/myapp` for `team/myapp/dev`, `/prod` for `/prod/web`). Names without `/` belong to the project *(no prefix)* (ID `~`, a character AWS names can't contain) |
| Environment | The **last segment** of the name (`dev`, `stg`, `prod`). The secret *is* the environment: `myapp/dev` |
| Folder | None: `listFolders` is empty; any path other than `/` is NOT_FOUND |
| Secret (key = value) | A **top-level field of the secret's JSON object**. String values as they are; numbers, booleans, objects and arrays as their JSON text (as for Vault). A secret whose `SecretString` is not a JSON object (a **plain string**) is listed as a single key named after the name's last segment (`STRIPE_KEY` for `myapp/STRIPE_KEY`) |

Alternatives considered:

- **Project = the secret, environment = none** (like 1Password's single environment): simplest, but loses the environment
  switcher, the prod guard by environment name and `aws-sm://#KEY` references that follow the selected environment, which is
  what makes `<app>/<env>` naming useful in the IDE.
- **Project = first segment, environment = second, folders = the rest** (`myapp/dev/db` → folder `/db`): fits `app/env/component`
  names but not the equally common deeper prefixes (`team/myapp/dev`) and leaves single- and two-segment names awkward. "Last
  segment is the environment" works for any depth of prefix, and the name is always `project + "/" + environment`, so a
  reference written with the full name maps back without asking AWS.
- **One AWS secret per variable** (`myapp/dev/DB_PASSWORD`, plain strings): supported as a side effect (project `myapp/dev`,
  each variable an "environment" with one key), but not the primary model: every variable would be a separate API call and a
  separate secret billed monthly.
- **Environment-first names** (`prod/myapp`) and other schemes: handled by the per-connection *Name layout* (below).

### Name layout

The table above describes the default layout `{project}/{env}`. A connection's *Name layout* option (`nameLayout`) is a template
with the placeholders `{project}` and `{env}` for teams that name secrets differently (`AwsNameLayout`):

- Each placeholder exactly once; between them a non-empty literal separator (`{project}{env}` can't be split); literal text
  only of characters AWS allows in names (letters, digits, `/_+=.@-`), no `//`, not ending with `/`. Separators other than `/`
  are allowed (`{project}-{env}`, `{env}.{project}`). Leading and trailing literals are allowed (`acme/{env}/{project}`,
  `{project}/{env}/config`). The connection dialog validates the template (`AuthOption.validate`).
- Matching: the literals exactly; `{env}` is non-empty and never contains `/` (as in the default, and as `?env=` requires);
  `{project}` is non-empty and may span several `/` segments. Where a name could be split more than one way, `{project}` takes as
  much as it can and `{env}` as little: in `{project}/{env}` the environment is the last segment (today's rule), in
  `{env}/{project}` the first, in `{project}-{env}` the text after the last `-` (`my-app-dev` → `my-app` / `dev`). Implemented as
  an anchored regex with a greedy project and a lazy, slash-free environment.
- The default template keeps the original mapping exactly (`AwsSecretNames.split/join`), including *(no prefix)* for names without
  `/` and the `/`-prefixed names, so existing connections and references are unchanged. Other layouts have no *(no prefix)*
  project.
- Names that don't match are **not shown**. `listProjects` counts them, and the tool window shows "N secrets are not shown: their
  names don't follow the name layout …" under the connection (`SecretProvider.listingNote`).
- New secrets (new environments, keys moved or copied to a missing environment) are named by the template, and refused when the
  name wouldn't be read back as the same project and environment (`{project}-{env}` with environment `a-b`).
- ListSecrets' `name` filter is the template's fixed leading literal (`acme/`), combined with the *Secret name prefix* option:
  the longer one when one starts with the other; when they exclude each other, listing is CONFIGURATION.
- **References are layout-independent**: `aws-sm://myapp/dev#KEY` names project `myapp` and environment `dev` (split at the last
  `/` as before) and the client maps them to the secret's name through the layout (`dev/myapp` under `{env}/{project}`). The
  same reference therefore works for connections with different layouts (tested with both).

Names AWS allows but the mapping can't express are left out of listings and refused in references: a name ending with `/` or
containing `//`. Secrets scheduled for deletion are not listed (ListSecrets' default). **Binary secrets** (`SecretBinary`) are
not supported: reading one is CONFIGURATION "holds a binary value, which EnvBridge doesn't read"; nothing writes them.

### References

Scheme `aws-sm` (`AwsGrammar`). The secret is named the way AWS users name it (`aws secretsmanager get-secret-value --secret-id
myapp/dev`), the key after `#`:

```
aws-sm://myapp/dev#DB_PASSWORD               key DB_PASSWORD of secret myapp/dev
aws-sm://team/myapp/prod#DB_PASSWORD         prefix team/myapp, environment prod
aws-sm://myapp/dev#*                         every key of myapp/dev (scope import; ** means the same)
aws-sm://#DB_PASSWORD                        key DB_PASSWORD of the bound secret (bound prefix + selected environment)
aws-sm://#DB_PASSWORD?env=prod               bound prefix, environment prod (?project=<prefix> names another prefix)
aws-sm://myapp/dev#DB_PASSWORD?connection=Work
```

- The part before `#` is *project/environment* (split at the last `/`), which is the secret's name under the default layout; with
  another name layout it is mapped to the name through the layout (see *Name layout*).
- The full name pins prefix and environment; `aws-sm://#KEY` takes both from the binding, so it follows the environment picked
  in the status bar, like `doppler://KEY`. `build()` writes the short form only when the scope is the bound project *and*
  environment (as `PathGrammar` does), else the full name.
- `?env=` / `?project=` exist for references that name only one of the two, which the core produces when it rewrites
  references after a move (an omitted project stays omitted). A name can't be combined with them (it already says both);
  `?env=` can't contain `/`. A name without `/` can't be told apart from a one-segment short form, which is why the partial
  forms are parameters rather than path segments.
- A name is written raw when it uses only the characters AWS allows in names (letters, digits, `/_+=.@-`); anything else
  (managed secrets' `!`, a `#` or `?` in a key) is percent-encoded. Keys are encoded like other providers' segments; `%2A` is a
  key called `*`.
- Refused with a message: no `#` ("add #KEY, or #* for every key"), an empty key, whitespace, `*` inside the name, a name
  ending with `/` or containing `//`, unknown parameters. The query may follow the name or the key (as in `vault://`).
- **No `?region=`**: a connection is one region; a secret in another region is reached through a connection for that region
  (`?connection=`). Cross-region references would need the client to sign for another endpoint with the same credentials,
  which the SPI's scope doesn't carry.

### API (JSON 1.1: `POST /`, `Content-Type: application/x-amz-json-1.1`, `X-Amz-Target: secretsmanager.<Action>`)

| Purpose | Action |
|---|---|
| Projects and environments | `ListSecrets` `{MaxResults: 100, NextToken, Filters: [{Key: "name", Values: [prefix]}]}` (the name prefix option and the layout's fixed start), paged, ≤ 100 pages (10 000 secrets; more is CONFIGURATION "set a name prefix"). Each entry's `Description`, `Tags`, `RotationEnabled`, `LastRotatedDate`, `NextRotationDate`, `LastChangedDate` and `SecretVersionsToStages` are kept (in memory) for comments, the tool window and change detection: no `DescribeSecret` per secret |
| Several environments' keys at once | `BatchGetSecretValue` `{SecretIdList: [≤ 20 names], NextToken}` → `SecretValues[]` (`Name`, `VersionId`, `SecretString`) and `Errors[]` (`SecretId`, `ErrorCode`, `Message`), see *Fewer requests* |
| Description of a secret | `UpdateSecret` `{SecretId, Description, ClientRequestToken}` without `SecretString`, so no new version (the token, which the SDKs always send, names a version only together with a value; LocalStack requires it); `DescribeSecret` to see whether a secret has one |
| Rotate now | `RotateSecret` `{SecretId, ClientRequestToken}` (the secret's stored rotation settings, `RotateImmediately` defaulting to true) |
| Keys of an environment | `GetSecretValue` `{SecretId: name}` → `SecretString` / `SecretBinary`, `VersionId` |
| Create an environment, or the first key of a missing secret | `CreateSecret` `{Name, SecretString, ClientRequestToken}` (fails with `ResourceExistsException` if it exists: an atomic create) |
| Change keys | `PutSecretValue` + `UpdateSecretVersionStage`, see *Writes* |
| Sign-in check | `ListSecrets` `{MaxResults: 1}` |

Errors (`__type`, possibly prefixed `…#`, or `x-amzn-ErrorType`; message from `message` / `Message`, capped at 300 characters):
`UnrecognizedClientException`, `InvalidSignatureException`, `SignatureDoesNotMatch`, `ExpiredTokenException`,
`InvalidClientTokenId`, `IncompleteSignature`, `MissingAuthenticationToken` → AUTH_REQUIRED (with "check the system clock" for
an expired signature); `AccessDeniedException` and any 403 → FORBIDDEN; `DecryptionFailure` → FORBIDDEN ("can't decrypt with
its KMS key"); `ResourceNotFoundException` → NOT_FOUND; `ThrottlingException` / `TooManyRequestsException` / 429 → SERVER
("AWS throttled the request; retry in a moment"); other 4xx → CONFIGURATION; 5xx → SERVER; I/O → NETWORK (`NetworkErrors`).
AWS messages name secrets and ARNs, never values.

`AwsHttp` follows `DopplerHttp`: JDK HttpClient, IDE proxy and certificates, no redirects, HTTP/1.1, 10 s / 30 s timeouts,
user agent `EnvBridge-IntelliJ`. Credentials are used only to compute the `Authorization` header (the secret access key never
leaves the IDE; the session token goes in `X-Amz-Security-Token`). Nothing is put in URLs; bodies are not logged.

### Fewer requests

Secrets Manager bills API requests (per 10 000), so the client avoids repeated reads where that doesn't weaken freshness:

- **Launches** (`fresh`): `LaunchSecretResolver` hands every scope of the launch to `prefetch`; the AWS client reads them with
  `BatchGetSecretValue`, 20 names per request, and keeps the values in memory marked *read ahead*. Each read-ahead value serves
  **one** read within 15 seconds (the launch's own), so a launch reads N secrets in ⌈N/20⌉ requests and never gets a value read for
  another launch. Without a prefetch, fresh reads are single `GetSecretValue` calls, as before.
- **Browsing and the keys index** (not `fresh`): values are kept in memory (`AwsMemory`) with the `VersionId` they are. A read is
  answered from memory when the last listing (no older than the cache time, *Settings › Tools › EnvBridge*) shows the same
  `AWSCURRENT` version (from `SecretVersionsToStages`), or, for listings without version IDs, the same `LastChangedDate` as when the
  value was read. A secret that has to be read is read together with up to 19 other environments of the same project whose
  values are missing or out of date, in one `BatchGetSecretValue`. So the keys index (which lists projects, then reads each
  environment) costs one `ListSecrets` page per 100 secrets plus one batch per 20 *changed* environments, instead of one
  `GetSecretValue` per environment every refresh. `KeysIndex` itself is unchanged; this is all inside the AWS client.
- **Never persisted**: values live in memory only, at most 500 per connection, at most an hour old, and are dropped whenever
  `SecretsService.invalidate` runs (`SecretProvider.forget`: after every write, sign-in, sign-out, Refresh) and on sign-out. The
  listing (names, metadata, versions; no values) stays until the next listing or a global invalidate.
- **Fallback**: `BatchGetSecretValue` needs `secretsmanager:BatchGetSecretValue` **and** `GetSecretValue` on each secret (AWS's
  documented requirement). When the whole request is refused (AccessDenied, any other 4xx such as an endpoint that doesn't know
  the action, or HTTP 501), the client reads with `GetSecretValue` and doesn't try the batch for that connection for an hour
  (in memory). Throttling or 5xx fall back for that read only. Per-secret `Errors` are mapped like a single read's error
  (`ResourceNotFoundException` → NOT_FOUND, `AccessDeniedException` → FORBIDDEN, `DecryptionFailure` → FORBIDDEN, …) and affect
  only that secret.
- AWS's pricing page wasn't reachable from the build environment; EnvBridge assumes requests are billed per API call, as AWS
  documents for Secrets Manager. CloudTrail logs one `GetSecretValue` event per secret of a batch (AWS API reference).

IAM permissions: reading `ListSecrets`, `GetSecretValue`, `BatchGetSecretValue` (optional but recommended); writing
`PutSecretValue`, `UpdateSecretVersionStage`, `CreateSecret`; descriptions `UpdateSecret`, `DescribeSecret`; *Rotate Now*
`RotateSecret` (plus `lambda:InvokeFunction` on the rotation function, as AWS documents).

### Signing (`AwsSigV4`)

Signature Version 4 as specified in the AWS General Reference: canonical request (method, canonical URI, sorted query, sorted
lower-case headers with trimmed values, signed header list, hex SHA-256 of the body) → string to sign (`AWS4-HMAC-SHA256`,
`X-Amz-Date`, credential scope `<date>/<region>/secretsmanager/aws4_request`, hash of the canonical request) → signing key
(HMAC-SHA256 chain `AWS4<secret>` → date → region → service → `aws4_request`) → `Authorization: AWS4-HMAC-SHA256
Credential=…, SignedHeaders=…, Signature=…`. Signed headers: `content-type`, `host` (with the port when not the default),
`x-amz-date`, `x-amz-target` and, for temporary credentials, `x-amz-security-token`. The clock is injectable for tests.

Tests: the cases `get-vanilla`, `get-vanilla-query-order-key-case`, `get-vanilla-with-session-token`, `post-vanilla`,
`post-vanilla-query`, `post-header-key-sort` and `post-x-www-form-urlencoded` of the AWS SigV4 test suite (the copy in
`awslabs/aws-c-auth`, `tests/aws-signing-test-suite/v4`: canonical request, string to sign and signature each compared), plus
the mock server re-computing every request's signature with the same secret.

### Region and endpoint

The connection's address is the regional endpoint; the presets are the common regions (`us-east-1` … `sa-east-1`). The region is
the connection's *Region* option if set, else read from the host (`secretsmanager[-fips].<region>.amazonaws.com[.cn]`), else
CONFIGURATION "set the region". A custom address (LocalStack `http://localhost:4566`, a VPC endpoint
`https://vpce-….secretsmanager.<region>.vpce.amazonaws.com`) is allowed with the usual rule: `https://`, plain `http://` only
for loopback.

### Sign-in

| Method | Credentials | Where they live |
|---|---|---|
| **Access key** (default) | Access key ID, secret access key, optional session token | All three are secret options (PasswordSafe). The access key ID isn't secret by AWS's definition, but it identifies the account's user, so it isn't put in settings either. Sign-out forgets them (never deactivates the key: it is the user's own) |
| **AWS profile** | A profile of `~/.aws/credentials` / `~/.aws/config` (`AWS_SHARED_CREDENTIALS_FILE` / `AWS_CONFIG_FILE` honoured), name in the connection (default `default`) | Read **at use time**, never copied to PasswordSafe or settings. Static keys (`aws_access_key_id`, `aws_secret_access_key`, `aws_session_token`) are read from the files directly. Profiles that get credentials another way (`sso_session` / `sso_start_url`, `role_arn`, `credential_process`, `web_identity_token_file`) are handed to the AWS CLI: `aws configure export-credentials --profile <name> --format process` (AWS CLI v2.9+), whose stdout is the credential JSON. argv carries only the profile name; stdin is closed; stdout is never logged; temporary credentials are kept in memory until five minutes before they expire |
| **IAM Identity Center (SSO)** | SSO start URL, SSO region, account ID and role name (both optional: picked when there is only one) | Access token, refresh token and the OIDC client registration (client ID and secret, expiry) in the session slot (PasswordSafe), as one JSON entry; role credentials in memory only, until five minutes before they expire. See below |
| **Environment variables** | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` of the IDE process | Read at use time; nothing stored |

*Sign In* checks the credentials with `ListSecrets {MaxResults: 1}` and shows the access key ID shortened (`AKIA…WXYZ`) and the
region. Profile and environment connections store a "signed in" marker in the session slot (as the 1Password app method does),
so background features (the keys index) don't run the AWS CLI for a connection nobody signed in to; sign-out removes it and the
in-memory credentials. An `aws` CLI failure that says the SSO session expired or the token is invalid is AUTH_REQUIRED with "run
`aws sso login --profile <name>`"; a missing CLI is CONFIGURATION with an install hint. The CLI is found like `op`: PATH, then the
usual install locations. Variables that would make it use other credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_SESSION_TOKEN`, `AWS_PROFILE`) are removed from its environment.

Setup links: the IAM console's *Security credentials* page (`https://console.aws.amazon.com/iam/home#/security_credentials`)
and the AWS docs on access keys and on named profiles / IAM Identity Center.

**IAM Identity Center (SSO) in the IDE** (`AwsSso`), without the AWS CLI, following what `aws sso login` does (the OAuth 2.0
device authorization grant):

1. `POST https://oidc.<sso region>.amazonaws.com/client/register` `{clientName: "EnvBridge-IntelliJ", clientType: "public",
   scopes: ["sso:account:access"]}` → `clientId`, `clientSecret`, `clientSecretExpiresAt` (the scope is what makes AWS issue refresh
   tokens, as the CLI's `sso_registration_scopes` default does).
2. `POST …/device_authorization` `{clientId, clientSecret, startUrl}` → `deviceCode`, `userCode`, `verificationUri(Complete)`,
   `expiresIn`, `interval`. The IDE opens `verificationUriComplete` (only an `https` address) with `SignInInteraction.openBrowser`
   and shows "check that AWS shows the code ABCD-EFGH, then allow access" through `interaction.status`, as the Doppler sign-in does.
3. `POST …/token` `{clientId, clientSecret, grantType: "urn:ietf:params:oauth:grant-type:device_code", deviceCode}` every
   `interval` seconds (default 5): `authorization_pending` → keep polling; `slow_down` → five seconds more; `expired_token` →
   AUTH_REQUIRED "the code expired"; `access_denied` → AUTH_REQUIRED; a passing network or 5xx error is tolerated a few times.
   Errors are read from the JSON `error` field or the exception name (`x-amzn-ErrorType` / `__type`).
4. Account and role: the connection's options, else `GET https://portal.sso.<region>.amazonaws.com/assignment/accounts` /
   `…/assignment/roles?account_id=` (paged): one → used, several → CONFIGURATION listing them ("set the account ID in the
   connection's settings: 1234… (Dev), …"), none → FORBIDDEN.
5. `GET …/federation/credentials?account_id=…&role_name=…` with `x-amz-sso_bearer_token: <access token>` → `roleCredentials`
   (`accessKeyId`, `secretAccessKey`, `sessionToken`, `expiration` in epoch **milliseconds**), checked with `ListSecrets`
   `{MaxResults: 1}` before anything is stored.

Afterwards, `keys()` uses the role credentials in memory until five minutes before they expire, then fetches new ones with the
stored token. The token is renewed with `CreateToken` `{grantType: "refresh_token", refreshToken, clientId, clientSecret}` when it
expires within 15 minutes (the CLI's window), or when the portal answers 401 (revoked early); a refused refresh (`invalid_grant`),
an expired client registration or a missing refresh token → AUTH_REQUIRED "sign in again". A Secrets Manager AUTH_REQUIRED drops
the role credentials and retries once. Sign-out calls `POST …/logout` with the token (best effort) and forgets the session and the
credentials. A changed start URL or SSO region makes the stored session unusable (the connection dialog drops sessions on any
option change anyway). None of these endpoints is SigV4-signed: the OIDC calls are unauthenticated JSON, the portal takes the bearer
header. Tokens never go into URLs (only account ID and role name do, which aren't secret), logs or messages.

The shapes were verified against botocore's service models (`botocore/data/sso-oidc/2019-06-10/service-2.json`,
`sso/2019-06-10/service-2.json`: operations, members, query and header names, error shapes) and the AWS CLI v2's
`SSOTokenFetcher` (registration with `scopes`, polling with `interval`, `slow_down` +5 s, `expired_token`, refresh-token grant) on
GitHub; the AWS documentation site wasn't reachable from the build environment. Tested against a mock only (`MockAwsSso`):
LocalStack's community edition has no IAM Identity Center. China regions use `amazonaws.com.cn`; other partitions (GovCloud is
`amazonaws.com` too) are untested.

Setup links for the method: the IAM Identity Center console (`https://console.aws.amazon.com/singlesignon/home`) and the IAM
Identity Center user guide (`https://docs.aws.amazon.com/singlesignon/latest/userguide/what-is.html`).

### Writes

All writes go through `SecretsService` (*Read-only* and the prod guard apply) and are explicit user actions. Every write is a
**read-modify-write of one secret's JSON object** with a real check-and-set:

1. `GetSecretValue` → the JSON object and its `VersionId` *V0* (the version that has `AWSCURRENT`).
2. Change the object (add / change / rename / remove keys; untouched keys keep their JSON types and order).
3. `PutSecretValue` `{SecretString, ClientRequestToken: <new UUID V1>, VersionStages: ["ENVBRIDGE_PENDING"]}`: a new version
   that is **not yet current** (readers still get *V0*).
4. `UpdateSecretVersionStage` `{VersionStage: "AWSCURRENT", MoveToVersionId: V1, RemoveFromVersionId: V0}`. AWS documents
   that this fails if `AWSCURRENT` is attached to another version than `RemoveFromVersionId`, so if anyone changed the secret
   after step 1, nothing becomes current and the user gets CONFIGURATION "the secret was changed meanwhile; refresh and try
   again". AWS moves `AWSPREVIOUS` to *V0* by itself.
5. `UpdateSecretVersionStage` `{VersionStage: "ENVBRIDGE_PENDING", RemoveFromVersionId: V1}`, best effort (also after a failed
   step 4), so no helper label stays behind; a version without labels is deprecated and removed by AWS.

What AWS offers was checked: `PutSecretValue` and `UpdateSecret` have no condition on the current version
(`ClientRequestToken` only makes a retry idempotent), so a plain `PutSecretValue` would silently overwrite a concurrent change.
The staging-label move is the same mechanism rotation functions use, and it costs one extra permission:
`secretsmanager:UpdateSecretVersionStage`. Without it, step 4 is FORBIDDEN and the message says so ("nothing changed; EnvBridge
needs secretsmanager:UpdateSecretVersionStage to write safely"); there is deliberately no fallback to an unchecked write. The
staging labels `AWSPENDING` (rotation's) are never touched. LocalStack implements the same check (verified by the integration
test that writes a version meanwhile).

| Operation | Behaviour |
|---|---|
| `createSecret(key, value)` | Adds the key to the environment's JSON object; refused (CONFIGURATION) if the key exists. If the secret doesn't exist (yet), `CreateSecret` with `{key: value}`. A plain-string secret is refused ("holds a plain string, not key/value JSON"): EnvBridge never converts a secret's format |
| `updateSecret(key, value, comment, newKey)` | Changes the value (as a JSON string; a value left as it was keeps its JSON type). With `newKey`: renamed in the same write, keeping its place; refused before writing if `newKey` exists. A plain-string secret's single key: its value is replaced (`PutSecretValue` of the plain string, with the same check-and-set); renaming it is refused (its name is the secret's) |
| `deleteSecret(key)` | Removes the key. The last key leaves `{}`. **The AWS secret itself is never deleted** (`DeleteSecret` is not used at all; even with a recovery window, deleting a secret other services may read is not an IDE's call). A plain-string secret's key: refused |
| `moveSecrets` | Within a connection (other environment or prefix): every key written to the destination in **one** check-and-set write (overwrite: as given; without it, any existing key refuses the whole move before anything is written), then the source re-read and the keys removed in **another**. A failed removal is reported per key in `MoveResult.notRemoved`, both copies kept. The destination secret is created if missing |
| `copySecrets` | One check-and-set write to the destination with the values as stored (JSON types kept); keys that exist there fail individually |
| `createEnvironment(projectId, name, slug)` | `CreateSecret` `<prefix>/<slug>` (named by the connection's name layout) with `{}` (Description = the dialog's name when it differs from the slug). The slug must be valid in an AWS name and contain no `/` |

Comments: Secrets Manager has no per-key comments. A secret's `Description` (from ListSecrets, no extra request) is the comment
of **all its keys**. *Edit Secret…* sends a changed comment as `UpdateSecret` `{Description}` without a value, which AWS documents
makes no new version (verified on LocalStack: the current `VersionId` stays); the dialog says it applies to the whole secret. A
comment given when creating a key becomes the description only when that creates the AWS secret or the secret has no description
yet (one `DescribeSecret`), so adding a key never overwrites a description others wrote. Moves and copies don't carry comments.
Key names are free-form JSON keys; only blank names and names with surrounding blanks are refused (`renameTarget`), since the New
Secret dialog already checks env-var style names.

**Rotation**: environments whose secret has `RotationEnabled` show "rotation on" and, as a tooltip, the last and next rotation
(with the description and tags). *Rotate Now…* (context menu of such an environment or its keys) asks for confirmation, then
again through the production guard for a production environment, honours *Read-only* (`SecretsService.rotateSecret`), and calls
`RotateSecret` with the secret's stored settings; a secret the last listing shows without rotation is refused before calling AWS.
The rotation itself runs in AWS (a Lambda or managed rotation). A rotation that finishes while EnvBridge writes the same secret
moves `AWSCURRENT`, so that write fails as "changed meanwhile, nothing was changed" (correct: the user refreshes and writes again
on top of the rotated value); EnvBridge never touches `AWSPENDING`.

### Dashboard

`https://<region>.console.aws.amazon.com/secretsmanager/secret?name=<url-encoded name>&region=<region>`, or the region's secret
list (`…/secretsmanager/listsecrets?region=<region>`) without a scope. Only for `amazonaws.com` endpoints; LocalStack and other
custom addresses get none.

### Tests

- `AwsSigV4Test`: the test-suite vectors above (canonical request, string to sign, signature), the signing key derivation.
- `AwsGrammarTest`: full names with any prefix depth, leading `/`, `#*` / `#**`, the short `#KEY` form, percent-encoding of
  names and keys, `?connection=` before and after the key, round trips, `build()` with and without a binding, and every
  malformed form with its message; name ↔ project/environment mapping.
- `AwsNameLayoutTest`: the default layout equals the original mapping; environment-first, fixed-start, suffixed and `-`/`.`
  separated layouts; the "shortest environment" rule; creation refusing names that wouldn't read back; every invalid template with
  its reason; the server-side prefix combined with the name prefix option.
- `AwsFeaturesPlatformTest` (mocks): listing, reading, writing and creating under `{env}/{project}`; the fixed start as the
  ListSecrets filter, the hidden-names note, a clash with the name prefix; the same references under both layouts; a launch reading
  its secrets in one batch (and a missing one failing closed), read-ahead values never serving a second launch, the
  AccessDenied fallback remembered for an hour, per-secret batch errors, browsing re-reading only changed versions (by version ID
  and by `LastChangedDate`), the keys index from one batch; descriptions as comments, the description edit without a new version,
  descriptions from new keys; rotation info, *Rotate Now* only with rotation on and not when read-only, a rotation during a write
  refused as "changed meanwhile"; the IAM Identity Center device flow (slow_down, pending, the code shown, the URL opened, tokens
  only in PasswordSafe, never in URLs), account/role picking, denied and expired codes, role credentials re-fetched, the token
  renewed with the refresh token, AUTH_REQUIRED when that fails, sign-out with logout; option validation.
- `MockAwsSecretsManager`: a JDK HttpServer speaking JSON 1.1. It **verifies each request's SigV4 signature** with the known
  secret (and rejects a wrong one with `InvalidSignatureException`, an unknown key with `UnrecognizedClientException`), checks
  the credential scope's region and service, and implements `ListSecrets` (pages, name filter), `GetSecretValue`,
  `CreateSecret`, `PutSecretValue` and `UpdateSecretVersionStage` (with AWS's `RemoveFromVersionId` check); `DeleteSecret` is
  answered with an error and asserted never to be called. It can deny actions per secret (`AccessDeniedException`), throttle,
  and change a secret "meanwhile".
- `AwsPlatformTest`: registration, presets and setup links; listing prefixes / environments / keys (JSON types, plain strings,
  binary refused, *(no prefix)*), pagination, the name-prefix option; each sign-in method (access key with and without session
  token, profile with static keys, profile through a fake `aws` CLI printing `export-credentials` JSON, environment variables);
  values never in URLs, `toString` or exception messages; error mapping (403 AccessDenied, ResourceNotFound, throttling, wrong
  key); writes (create, update, rename, delete, move, copy, new environment, conflicts refused before writing, the check-and-set
  refusing a concurrent change with nothing changed, the helper label removed); *Read-only*; launches resolving `aws-sm://`
  references (full names, short form following the bound environment, scope import, fail closed, *Only use the bound project*).
- `AwsIntegrationTest` against LocalStack (`scripts/integration/start.sh` starts `localstack/localstack:4.14.0` with only
  Secrets Manager; `setup.sh` seeds `envbridge-it/dev` with a description and tags, `envbridge-it/prod`, a plain string and the
  environment-first `dev/envbridge-it-ef`, `prod/envbridge-it-ef`): listing, reading, launches, writes including the
  check-and-set against a real implementation of `UpdateSecretVersionStage`; the `{env}/{project}` layout (listing, a reference
  resolving under it, creating); `BatchGetSecretValue` (LocalStack 4.14 implements it, including per-secret `Errors`; the
  AccessDenied fallback is mock-only because LocalStack doesn't enforce IAM); descriptions and tags from ListSecrets, the
  description edit keeping the current version; `RotateSecret` refused (LocalStack needs a rotation Lambda, so a real rotation
  isn't tested).
- `MockAwsSso`: the `sso-oidc` and portal endpoints (device code and refresh-token grants, OAuth errors, bearer header, logout).

### Known limits and unverified assumptions

- **Not tested against a real AWS account.** The JSON shapes, error names and the staging-label check follow the AWS API
  reference as remembered and LocalStack 4.14's behaviour. Real AWS error messages for the failed staging move may differ from
  LocalStack's ("Version stage AWSCURRENT not found in version …"); any `InvalidParameterException` from step 4 is treated as a
  concurrent change.
- SSO / assume-role profiles depend on the AWS CLI v2 (`export-credentials`, 2.9+); that path is tested with a fake CLI only.
- The in-IDE IAM Identity Center sign-in is tested against a mock only (shapes from botocore and the AWS CLI source); no real
  Identity Center instance was used. Whether every region issues refresh tokens for the `sso:account:access` scope wasn't checked;
  without one, the user signs in again when the access token expires (typically after 8 hours, set by the administrator).
  `credential_process` is not run by EnvBridge directly (the CLI runs it).
- No cross-region references; one connection per region. No `?versionStage=` / `?versionId=` (always `AWSCURRENT`).
- The keys index (completion) re-reads the bound project's environments every 10 minutes; with the version check and batches
  that is one `ListSecrets` page per 100 secrets and one `BatchGetSecretValue` per 20 changed environments (one `GetSecretValue`
  per changed environment where the batch is denied). The `LastChangedDate` fallback has second resolution on AWS; the version ID
  check, used whenever ListSecrets returns `SecretVersionsToStages`, has none of that limit.
- Listings are capped at 10 000 secrets per connection; use the name prefix option for larger accounts.
- Secrets replicated to other regions are read from the connection's region only; replica-region writes are refused by AWS
  and come back as CONFIGURATION with AWS's message.

## Threat model (summary)

| Threat | Mitigation |
|---|---|
| Tokens leaked from settings files or backups | Only PasswordSafe. XML settings hold IDs and URLs. |
| Secret values persisted by the IDE | Run configurations are cloned in memory; nothing goes into `workspace.xml`, `.run` files or caches; no disk cache. |
| Malicious repository redirects credentials (`domain` in `.infisical.yaml`) | Unknown instances need explicit trust; tokens are only sent to their own connection's base URL. |
| Login CSRF / forged callback on the loopback port | JSON-only POST (forces preflight), strict `Origin` check, single-use short-lived server, token validated against the instance; `state` + PKCE for OAuth. |
| Secrets in logs | Values never logged; `SecretValue.toString()` is masked; HTTP errors are logged without bodies. |
| Secrets on screen or in clipboard | Masked by default, auto-hide on reveal, clipboard auto-clear, console masking (M3). |
| Plaintext transport | `https://` required, except loopback. The IDE's proxy and certificate settings apply (self-hosted CAs). |
| Accidental production use | Prod guard (M3) for launches and for creating secrets in production-like environments; no edits or deletes. |
| Secrets on child process command lines (1Password CLI) | Tokens go only into the child's environment; values written go on stdin as item JSON (never `field=value` assignments); argv carries names and flags only; inherited `OP_*` credentials are removed. |
| AWS credentials leaking through signing or the CLI | The secret access key only computes the SigV4 signature and is never sent; `aws configure export-credentials` gets only the profile name on argv, its stdout (credentials) is parsed in memory and never logged; profile credentials are read at use time, never copied to settings or PasswordSafe. |
| A concurrent change to an AWS secret silently overwritten by a read-modify-write | Writes go to a version that isn't current yet and become current only through `UpdateSecretVersionStage` with `RemoveFromVersionId` = the version read; AWS refuses the move if anything else became current meanwhile. |
| IAM Identity Center tokens leaking | Access and refresh tokens and the client secret live in PasswordSafe only, go only to AWS's OIDC and portal endpoints of the configured region (request bodies, the `x-amz-sso_bearer_token` header), never into URLs, logs or messages; role credentials stay in memory; sign-out logs out on the portal. The device code is phishing-resistant as for Doppler: the IDE shows the code it asked for, only an `https` verification address is opened. |
| Phishing of a device-code style sign-in (Doppler) | The IDE shows the code it asked for and tells the user to approve only if the browser shows the same one; the polling code never leaves the IDE except to Doppler, in a request body; only an `https` sign-in address is opened. |
| Untrusted repository ships run configurations with references | Running a configuration hands the referenced secrets to its code, as running it with any environment would. References are visible in the run configuration, and the IDE's Trusted Projects mode keeps untrusted projects from running anything. |

## Milestones

- **M1 (foundation, done)**: repository and CI, provider interface, Infisical client, the three sign-in methods, settings, project
  binding (`.infisical.yaml`), tool window (browse, reveal, copy, run configuration preview), `infisical://` references and scope
  imports, injection for JVM / Gradle / Maven and other configurations with standard environment variables, fail closed.
- **M2** (done): Node.js / npm / Bun, Python, Go, Docker and Compose, `.env` files with references, terminal.
- **M3** (done): editor integration, console masking, status bar, prod guard, *Run with environment…*, environment comparison,
  local overrides.
- **M4** (done): Rider .NET and appsettings, database data sources.
