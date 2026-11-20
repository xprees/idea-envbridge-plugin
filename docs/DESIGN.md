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

Non-goals for v1: deleting secrets, syncing secrets to files. Creating and editing secrets and creating environments are the only writes, always on an explicit user action (*New Secret…*, *Edit Secret…*, *Extract value to Infisical…*, *New Environment…*), through `SecretsClient.createSecret` / `updateSecret` / `createEnvironment`, all guarded by `SecretsService` so the *Read-only* setting holds everywhere.

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
editor hints). Creating, editing, moving and deleting follow later; until then the write actions are off for Vault
connections, as with *Read-only*. Engines: **KV v2** and **namespaces** (Vault Enterprise / HCP Vault, OpenBao). KV v1 and
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
- `SecretProvider.supportsWrites` is false for Vault: the tool window's and `.env` write actions are off, and the service refuses
  writes as with *Read-only*.

### Tests

A mock Vault server (like `MockInfisical`): mounts, LIST, KV v2 reads, namespaces, each auth method including the OIDC
round trip, expired tokens and permission errors; the grammar round-trips; launches resolving `vault://` references.

## Third provider: 1Password

Chosen after Vault for reach among individual developers and small teams: many already keep their API keys in 1Password and
write `op://` references for `op run` / `op inject`. EnvBridge reads those same references, so a `.env` or run configuration
written for the CLI works in the IDE unchanged, without `op run` wrapping every launch.

**Scope, first version:** reading and injecting (tool window, references in run configurations, terminal, `.env`, editor
hints). `supportsWrites` is false, so the write actions are off and `SecretsService` refuses writes, as with *Read-only*.

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
| **1Password Connect server** | `GET /v1/vaults`, `GET /v1/vaults/{vaultId}/items`, `GET /v1/vaults/{vaultId}/items/{itemId}` with `Authorization: Bearer`. Names are resolved to IDs by listing (one list per client, i.e. per operation); a 26-character ID is fetched directly | The access token is a secret option (PasswordSafe) |

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

### Security notes

- No secret on a command line: argv carries only vault / item names, flags and the account. The service account token goes
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
  ID, section given or not, item import, fail closed, another vault refused while restricted); writes refused.

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
| Secrets on child process command lines (1Password CLI) | Tokens go only into the child's environment; argv carries names and flags only; inherited `OP_*` credentials are removed. |
| Untrusted repository ships run configurations with references | Running a configuration hands the referenced secrets to its code, as running it with any environment would. References are visible in the run configuration, and the IDE's Trusted Projects mode keeps untrusted projects from running anything. |

## Milestones

- **M1 (foundation, done)**: repository and CI, provider interface, Infisical client, the three sign-in methods, settings, project
  binding (`.infisical.yaml`), tool window (browse, reveal, copy, run configuration preview), `infisical://` references and scope
  imports, injection for JVM / Gradle / Maven and other configurations with standard environment variables, fail closed.
- **M2** (done): Node.js / npm / Bun, Python, Go, Docker and Compose, `.env` files with references, terminal.
- **M3** (done): editor integration, console masking, status bar, prod guard, *Run with environment…*, environment comparison,
  local overrides.
- **M4** (done): Rider .NET and appsettings, database data sources.
