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

Non-goals for v1: deleting secrets, syncing secrets to files. Creating and editing secrets and creating environments are the only writes, always on an explicit user action (*New Secret…*, *Edit Secret…*, *Extract Value to Secret Manager…*, *New Environment…*), through `SecretsClient.createSecret` / `updateSecret` / `createEnvironment`, all guarded by `SecretsService` so the *Read-only* setting holds everywhere.

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

A provider also contributes how it is configured per project (Infisical: `.infisical.yaml`). Everything else (run
configurations, references, tool window, caching, credentials) is shared.

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
2. `INFISICAL_ENVIRONMENT` set on the run configuration
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
- A keys-only index (no values) lives longer, for completion.

## Tool window "Secrets" (right side)

- Tree: connections › projects › environments › folders › secrets (imported and personal-override secrets are marked), with speed
  search. Keys and comments are visible, values masked.
- *Reveal* hides the value again after a few seconds. *Copy value* clears the clipboard after 30 s if it still holds the secret.
  *Copy reference* gives `infisical://…`, *Copy as KEY=…*, and *Open in dashboard*.
- *Run configuration preview* (dry run): pick a run configuration and see the final variables it would receive and where each
  comes from (explicit, reference, scope), values masked.

## Editor integration (M3, done)

Language-agnostic by design: variable names are found by text patterns, so completion (`completion.contributor language="any"`),
hover and gutter (`externalAnnotator language=""`) and the missing-secret inspection (no language) work in every file type. Inlay
hints have to be registered per language, so they are, in the optional configs of the plugins that define the languages (Java,
Kotlin, JavaScript/TypeScript, Python, Go, YAML, Properties, XML, Shell, `.env`, PHP, Ruby, Rust, plain text). A keys-only index
(`KeysIndex`) of the bound folder in every environment feeds them; it never holds values.


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
editor hints), then writing fields (see *Writes* below). Engines: **KV v2** and **namespaces** (Vault Enterprise / HCP Vault, OpenBao). KV v1 and
dynamic secrets are later candidates.

### Model mapping

| EnvBridge | Vault / OpenBao |
|---|---|
| Project | A KV v2 mount (`secret`, `kv`, …) |
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

### API (KV v2, namespaces via `X-Vault-Namespace`, token via `X-Vault-Token`)

| Purpose | Call |
|---|---|
| Mounts the token can use | `GET /v1/sys/internal/ui/mounts` (KV v2 only); no compatibility promise, so mounts can also be listed in the connection |
| Folders and secrets of a path | `LIST /v1/<mount>/metadata/<path>` (`GET …?list=true`); keys ending in `/` are folders |
| Fields of a secret | `GET /v1/<mount>/data/<path>` → `data.data`; non-string values are passed as JSON |
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

### Tests

A mock Vault server (like `MockInfisical`): mounts, LIST, KV v2 reads, namespaces, each auth method including the OIDC
round trip, expired tokens and permission errors; the grammar round-trips; launches resolving `vault://` references.

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
- `FakeOp`: a POSIX shell script written to a temp dir that answers `vault list`, `item list`, `item get`, `whoami` from
  fixture JSON, logs argv and whether `OP_SERVICE_ACCOUNT_TOKEN` was set, can require a token, act signed out, and report an
  ambiguous title like `op` does. `MockOnePasswordConnect`: a JDK HttpServer with the three Connect endpoints, bearer check,
  403 and 404.
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
  label}, reference}`), CLI vaults (`id`, `name`), `whoami` (`url`, `email`, `user_type`), Connect vaults (`id`, `name`) and
  items (`sections[]`, fields with `section {id}` only). The parser is lenient (missing keys are skipped, a non-string `value`
  is passed as JSON, `null` / empty output is an empty list).
- Whether `op read` resolves `op://vault/item/field` for a field inside a section is assumed, not verified; EnvBridge does.
- The `op` error texts used for classification are known examples; an unknown text is SERVER with the text shown.
- `--reveal` behaviour of older CLI versions is assumed (retry without it on "unknown flag").
- Background features (completion's keys index, every 10 minutes) run `op` for a signed-in app connection; when the app's
  authorization of the CLI has expired, the app may ask to approve at that moment. Signing out stops it.
- `?attribute=otp`, `?attribute=type`, `?ssh-format=` and field lookup by field ID (except where ID and label are equal, as for
  built-in `username` / `password`) are not supported.
- A whole-vault import makes one `op` call per item (≈0.5 s each); it is capped at 100 items.
- No dashboard link (opening items needs the account's UUID; a later version can use `whoami`).
- Writes through the CLI rely on `op item edit` accepting piped item JSON (confidence above); there is no argv fallback.
- Writes don't check that the item is unchanged since the tool window showed it, only since the operation read it (Connect:
  `version`; CLI: none). Items are never deleted; sections aren't created; moving into a vault's root is refused.
- A whole item is sent back on every write, values of all its fields included (to the same `op` / Connect server it was read
  from, which already holds them).

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
| Untrusted repository ships run configurations with references | Running a configuration hands the referenced secrets to its code, as running it with any environment would. References are visible in the run configuration, and the IDE's Trusted Projects mode keeps untrusted projects from running anything. |

## Milestones

- **M1 (foundation, done)**: repository and CI, provider interface, Infisical client, the three sign-in methods, settings, project
  binding (`.infisical.yaml`), tool window (browse, reveal, copy, run configuration preview), `infisical://` references and scope
  imports, injection for JVM / Gradle / Maven and other configurations with standard environment variables, fail closed.
- **M2** (done): Node.js / npm / Bun, Python, Go, Docker and Compose, `.env` files with references, terminal.
- **M3** (done): editor integration, console masking, status bar, prod guard, *Run with environment…*, environment comparison,
  local overrides.
- **M4** (done): Rider .NET and appsettings, database data sources.
