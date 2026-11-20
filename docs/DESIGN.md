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

1. An explicit environment in the reference (`infisical://prod/…`) or the run configuration's Infisical section
2. `INFISICAL_ENVIRONMENT` set on the run configuration
3. The environment picked in the IDE (status bar / binding)
4. `.infisical.yaml` git-branch override (last segment of the branch name)
5. `.infisical.yaml` default environment
6. `dev`

## Injecting secrets into run configurations

Opt-in per run configuration, inside the run configuration's own **Environment variables** (no new dialog):

- **Per-variable references**: `DB_PASSWORD=infisical://DB_PASSWORD`. The full syntax is
  `infisical://[<env>/][<folder>/…/]<KEY>[?project=<id>&connection=<id>]`. With one segment, the environment and folder come from
  the binding. With more, the first segment is the environment and the middle ones the folder. A path can also be passed as
  `?path=/backend` and the environment as `?env=prod`.
- **Whole scope**: a variable whose value ends with `/*` (`/**` for subfolders too) imports every secret of that scope. Its key is
  only a label and is not passed on, e.g. `INFISICAL=infisical://*` or `INFISICAL=infisical://staging/backend/**`.
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
| Untrusted repository ships run configurations with references | Running a configuration hands the referenced secrets to its code, as running it with any environment would. References are visible in the run configuration, and the IDE's Trusted Projects mode keeps untrusted projects from running anything. |

## Milestones

- **M1 (foundation, done)**: repository and CI, provider interface, Infisical client, the three sign-in methods, settings, project
  binding (`.infisical.yaml`), tool window (browse, reveal, copy, run configuration preview), `infisical://` references and scope
  imports, injection for JVM / Gradle / Maven and other configurations with standard environment variables, fail closed.
- **M2** (done): Node.js / npm / Bun, Python, Go, Docker and Compose, `.env` files with references, terminal.
- **M3** (done): editor integration, console masking, status bar, prod guard, *Run with environment…*, environment comparison,
  local overrides.
- **M4** (done): Rider .NET and appsettings, database data sources.
