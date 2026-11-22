# EnvBridge

Secrets from your secret manager in the IDE's environment variables, without `.env` files. Works with **Infisical**,
**HashiCorp Vault**, **OpenBao**, **1Password**, **Doppler** and **AWS Secrets Manager**.

<!-- Plugin description -->
<p><b>Unofficial integration</b>, not affiliated with, endorsed by or sponsored by Infisical Inc., HashiCorp (an IBM company),
the OpenBao project, AgileBits Inc. (1Password), Doppler Inc. or Amazon Web Services, Inc. "Infisical", "HashiCorp Vault",
"OpenBao", "1Password", "Doppler", "AWS" and "AWS Secrets Manager" are trademarks of their owners and are used only to say which
services this plugin works with.</p>

<p>Use your <b>Infisical</b> secrets in IntelliJ IDEA, WebStorm, PyCharm, GoLand, Rider and the other JetBrains IDEs, so you can run
and test locally <b>without keeping secrets in <code>.env</code> files</b>. Values are fetched when a run configuration starts and
handed to the process in memory. Nothing is written to disk.</p>

<ul>
  <li><b>Reference secrets in the environment variables you already use</b>: in a run configuration's <i>Environment variables</i>,
      set <code>DB_PASSWORD=infisical://DB_PASSWORD</code>, or <code>INFISICAL=infisical://*</code> to import a whole folder.
      Only the reference is stored, so <code>.run</code> files can be shared safely</li>
  <li>Works with <b>Application, JUnit, TestNG, Spring Boot, Kotlin, Gradle, Maven, Node.js, npm/pnpm/Yarn/Bun scripts, Python
      (pytest, Django, FastAPI, Flask), Go</b> and <b>Docker</b> (image, Dockerfile, <b>Docker Compose</b>) run configurations, for
      Run, Debug, Coverage and Profile</li>
  <li><b><code>.env</code> files with references</b>: keep a committed <code>.env</code> that holds <code>infisical://</code>
      references instead of secrets: attach it to the run configuration, or let Nuxt, Vite and Next.js read it as usual. Or bind a
      variable with a comment, <code>API_URL=http://localhost:8090 # infisical://prod:API_URL</code>: tools outside the IDE read
      the plain value, launches from the IDE get the secret, and the editor shows its masked value. Plaintext secrets in
      <code>.env</code> files are marked, and Alt+Enter binds them</li>
  <li><b>Terminal</b>: <i>New Terminal Tab with Secrets</i> opens a tab with the secrets; <i>Load Secrets
      into This Terminal</i> sets them in the current tab, in its shell's syntax (PowerShell, cmd, bash, zsh, fish, Git Bash,
      WSL), without showing them or leaving them in the history; opt in to give every new tab of the project the secrets</li>
  <li><b>In the editor</b>, in every language: completion of secret keys where code reads environment variables
      (<code>System.getenv("…")</code>, <code>process.env.…</code>, <code>os.environ["…"]</code>, <code>os.Getenv("…")</code>,
      <code>${…}</code>, …) and after <code>infisical://</code>; a masked hint with the environment; a key icon in the gutter to
      reveal, copy or locally override the value; a tooltip with the environments that define the key; a warning when a secret
      is missing in the bound environment</li>
  <li><b>Environments at a glance</b>: the bound environment in the status bar with a switcher, <i>Run with Infisical
      Environment…</i> to run a configuration once against another environment, a <b>prod guard</b> that asks before secrets from
      production-like environments reach a process, and a key-by-key <b>comparison</b> of two environments</li>
  <li>Injected values are <b>masked in Run and Debug consoles</b>; <b>local overrides</b> (kept in the IDE's password storage)
      replace a secret for you only</li>
  <li><b>Rider / .NET</b>: <i>.NET Project</i> and <i>.NET Executable</i> run configurations, ASP.NET Core included; in
      <code>appsettings*.json</code> the settings an Infisical secret overrides (<code>ConnectionStrings:Default</code> ⇄
      <code>ConnectionStrings__Default</code>) are marked, and plaintext secrets are reported</li>
  <li><b>Database data sources</b> (DataGrip and the Database tools): an <i>Infisical</i> authentication option whose user and
      password are <code>infisical://</code> references, resolved when the connection opens</li>
  <li><b>Fail closed</b>: if a secret can't be loaded, the launch stops with <i>Sign in</i>, <i>Retry</i> and <i>Run without secrets once</i></li>
  <li><b>Secrets tool window</b>: browse projects, environments, folders and secrets. Values stay masked; <i>Reveal</i> hides them
      again after a few seconds, and copied values are cleared from the clipboard after a while. Copy a ready-made
      <code>infisical://</code> reference, preview what a run configuration would receive (dry run), and create secrets
      (<i>New Secret…</i>)</li>
  <li><b>Any instance</b>: Infisical Cloud US, EU, dedicated or self-hosted (base URL per connection; the IDE's proxy and certificate
      settings apply)</li>
  <li><b>Sign in through the system browser</b> (like the Infisical CLI), with a read-only <b>OAuth application</b> (PKCE), or with a
      <b>machine identity</b>. Tokens and client secrets are kept only in the IDE's password storage</li>
  <li><b>HashiCorp Vault and OpenBao</b> (KV version 1 and 2, namespaces, or environments in paths such as
      <code>secret/myapp/dev</code>): <code>DB_PASSWORD=vault://secret/app/db#password</code>, or
      <code>vault://secret/app/db#*</code> for every field of a secret, or <code>vault://./db#password</code> in the selected environment. Sign in with <b>OIDC in the system browser</b> (like
      <code>vault login -method=oidc</code>), a token (or the Vault CLI's <code>~/.vault-token</code>), AppRole, userpass or LDAP.
      Create, edit, move and delete fields too; a secret's KV v2 <code>custom_metadata</code> is shown on hover and edited as its
      description and tags</li>
  <li><b>1Password</b>: your existing <code>op://vault/item/field</code> secret references work as they are, plus
      <code>op://vault/item/*</code> for every field of an item. Access through the <b>1Password app</b> (the 1Password CLI asks
      the app to approve, with Touch ID, Windows Hello or your system password: nothing to paste), a <b>service account</b>
      token or a <b>1Password Connect</b> server. New, edited, moved and deleted secrets are fields of items (a new secret at a
      vault's root is a new password item); values reach the CLI only on its standard input, never its command line. Item tags
      are shown, and <i>Only items tagged</i> narrows a connection to the items you work with</li>
  <li><b>Doppler</b>: <code>DB_PASSWORD=doppler://DB_PASSWORD</code> from the bound config, <code>doppler://prd:DB_PASSWORD</code>
      from another, or <code>doppler://*</code> for every secret of a config. Sign in <b>through the system browser</b> (like
      <code>doppler login</code>) or with a personal, service account or service token; reads the CLI's <code>doppler.yaml</code>.
      Create, edit, move and delete secrets, and create branch configs</li>
  <li><b>AWS Secrets Manager</b>: a secret named <code>myapp/dev</code> holding key/value JSON is environment <code>dev</code> of
      <code>myapp</code>: <code>DB_PASSWORD=aws-sm://myapp/dev#DB_PASSWORD</code>, <code>aws-sm://myapp/dev#*</code> for every key,
      or <code>aws-sm://#DB_PASSWORD</code> for the bound secret, following the selected environment; other naming schemes
      (<code>prod/myapp</code>) through a <b>name layout</b>. Sign in with <b>IAM Identity Center</b> in the browser (no AWS CLI
      needed), an access key, an <b>AWS profile</b> (SSO and role profiles through the AWS CLI) or the IDE's environment variables.
      Create, edit, rename, move and delete keys with check-and-set writes; descriptions, tags and rotation shown, <i>Rotate
      Now</i>; secrets read in batches to keep billed requests down; AWS secrets themselves are never deleted. No AWS SDK: requests
      are signed by the plugin</li>
  <li>Reads the Infisical CLI's <code>.infisical.yaml</code> / <code>.infisical.json</code> (project, environment, Git branch
      mapping, folder), so the plugin and <code>infisical run</code> pick the same environment</li>
</ul>

<p>Settings: <i>Settings › Tools › EnvBridge</i>. The plugin only reads secrets, except when you change one yourself
(<i>New Secret…</i>, <i>Edit Secret…</i>, <i>Move…</i>, <i>Delete…</i>, <i>Extract Value to Secret Manager…</i>; Infisical, Vault and OpenBao, 1Password, Doppler, AWS Secrets Manager).
<i>Read-only</i> in the settings turns all writing off.</p>
<!-- Plugin description end -->

Works in JetBrains IDEs based on the IntelliJ Platform **2026.1 and newer**. The plugin is built and tested against the oldest
supported IDE (`platformVersion` in `gradle.properties`) and verified against the latest IntelliJ IDEA, WebStorm, PyCharm, GoLand,
Rider and DataGrip in CI. It declares no upper build limit.

> **Status: all milestones of the [design](docs/DESIGN.md) are implemented (M1–M4).** Feedback and issues welcome.

## Quick start

1. *Settings › Tools › EnvBridge › Connections › +*: pick **Infisical Cloud (US)**, **(EU)** or enter your instance's
   URL, and choose a sign-in method (see [Signing in](#signing-in)). Then **Sign In**.
2. Tell the IDE which Infisical project to use. Either run `infisical init` in the project (the plugin reads `.infisical.yaml`),
   or pick the project and environment under *This Project* in the same settings page.
3. In a run configuration, open *Environment variables* and add references:

   ```
   DB_PASSWORD=infisical://DB_PASSWORD
   STRIPE_KEY=infisical://prod:payments/STRIPE_KEY
   INFISICAL=infisical://*
   ```

4. Run or debug it. The variables arrive in the process as real values. The *Secrets* tool window (right side) shows what's
   available, and its *Run Configuration Preview* shows what a configuration would receive.

## References

A reference is the value of an environment variable:

| Reference | Means |
|---|---|
| `infisical://KEY` | `KEY` from the root folder of the bound environment |
| `infisical://workers/KEY` | `KEY` from folder `/workers` of the bound environment |
| `infisical://prod:KEY` | `KEY` from the root folder of environment `prod` |
| `infisical://prod:backend/api/KEY` | `KEY` from folder `/backend/api` of `prod` |
| `infisical://*` | every secret of the root folder of the bound environment. The variable's own name is only a label and is not passed on |
| `infisical://staging:backend/**` | every secret of `/backend` in `staging`, subfolders included |
| `infisical://KEY?project=<id>&connection=<name>` | another project or connection (`?env=` and `?path=` are accepted too) |

Folders are absolute from the environment's root, so a reference alone says which secret it is; only the environment comes from
the binding unless written before a colon. The folder in *This Project* is a default for new secrets and the tool window, not for
references. A reference written the old way (`infisical://prod/KEY`, environment first) now names folder `/prod`; a launch with it
stops and says to write `infisical://prod:KEY`.

- **Precedence**: values typed into the run configuration win over references, and references win over whole-folder imports.
  Among imports, later ones win.
- Secret imports and `${…}` references inside Infisical are resolved like the Infisical CLI resolves them (folder secrets win over
  imported ones). Personal overrides are applied.
- The environment comes from (highest first): the reference → `ENVBRIDGE_ENVIRONMENT` (or the older `INFISICAL_ENVIRONMENT`) set on the run configuration → the
  environment picked in the IDE → `.infisical.yaml`'s Git branch mapping → its default environment → `dev`.

## Where references work

| Where | How |
|---|---|
| Run configurations: Application, JUnit, TestNG, Spring Boot, Kotlin, shell scripts, … | *Environment variables* of the configuration |
| Gradle, Maven | *Environment variables* of the run configuration (Maven: or the project's Maven runner settings) |
| Node.js, npm / pnpm / Yarn / Bun scripts | *Environment variables*; Bun and Deno work as interpreters / package managers of these configurations |
| Python: scripts, modules, pytest, Django, FastAPI, Flask | *Environment variables* |
| Go: Go Build, Go Test | *Environment* |
| Docker Image, Dockerfile, Docker Compose | *Environment variables* of the Docker run configuration. They become the container's environment, or for Compose the environment of `docker compose`, so `${VAR}` interpolation and `environment: [VAR]` pass-through pick them up. Nothing is written to disk |
| `.env` files | Any run configuration that loads env files (also the *Environment file* field of Node.js and npm): when an attached `.env` contains references, the plugin reads it, resolves the references and passes all its variables. Node.js and npm launches also resolve references in the `.env` / `.env.local` the tool reads by itself (Nuxt, Vite, Next.js): those keep process variables over the file. Values set on the configuration still win. **Binding comments**: `KEY= # infisical://KEY` or `KEY=local-fallback # infisical://prod:KEY` keeps the file valid for tools outside the IDE (they read the plain value) while IDE launches get the secret. An inspection marks plaintext values of secrets in `.env` files; Alt+Enter binds them (clearing the value or keeping it as a local fallback), and *Bind to Secret* binds an empty `KEY=` line |
| Decorators in `.env` files (env-spec) | Comments such as `# @required @sensitive @type=string(startsWith=sk-)` above a variable (or after it: `KEY= # @required`), as in varlock's `.env.schema`, work in every `.env` file a launch reads. **`@required`** (or the header's `@defaultRequired=true` / `infer`; `@optional` overrides): a launch whose value ends up empty or unset is stopped, naming the variables. **`@sensitive`** (or `@defaultSensitive`; `@public` overrides): the value is masked in the console like a secret. **`@internal`**: checked, but not passed to the process. **`@type`** (`string(startsWith=, endsWith=, matches=regex(…), minLength=, maxLength=)`, `enum(a, b)`, `number`, `integer`, `port`, `url`, `boolean`, `email`, `uuid`): a value that doesn't fit is reported by name when the launch starts (the launch goes on). Launches of configurations whose files use none of these are unchanged; *Settings › Tools › EnvBridge › Apply env-spec decorators of .env files at launch* (on) turns it off. A `.env.schema` next to the files adds its decorators |
| Layered `.env` files | *Settings › Tools › EnvBridge › Layered .env files* (off by default): launches read `.env.schema` < `.env` < `.env.local` < `.env.[environment]` < `.env.[environment].local` (later ones win) from the program's directory (Node.js, npm) and next to an attached `.env`, `[environment]` being the launch's environment (status bar, `ENVBRIDGE_ENVIRONMENT`, *Run with environment…*), and pass all their variables, references resolved. From `.env.schema` only plain values count (defaults); `KEY=` and function calls there are left out |
| Rider: .NET Project, .NET Executable (ASP.NET Core included) | *Environment variables* of the configuration. *Launch profile* configurations take their variables from `launchSettings.json`, which the plugin can't intercept: use a .NET Project configuration with references instead |
| Database data sources | *Data Source › Authentication: Infisical (EnvBridge)*: user and password as references or plain values, resolved when a connection opens; only the references are saved |
| Terminal | **New Terminal Tab with Secrets** (next to the terminal's **+**, or *Tools* menu) opens one tab with the secrets, in any shell (PowerShell, cmd, bash, zsh). To give every new tab secrets: *Settings › Tools › EnvBridge › Terminal*, opt in and list what tabs get (`infisical://*` or `NAME=infisical://KEY`, one per line). **Load Secrets into This Terminal** puts them into the shell of the current tab instead, in that shell's own syntax, decided from the tab's shell: PowerShell, cmd, bash / zsh / sh (also Git Bash and WSL on Windows) or fish. The values go through a short-lived owner-only file the shell loads and deletes, so they appear neither on screen nor in the shell history. Typing `export KEY=infisical://…` yourself does nothing: nothing resolves references inside a running shell |

## In the editor

| Feature | Where |
|---|---|
| Folders | References name their folder: `infisical://workers/KEY` (a bare `infisical://KEY` is the root). After `infisical://`, completion lists the keys of every folder, each with its folder, and inserts `workers/KEY` for other folders; after `infisical://workers/` it lists that folder; masked hints show the folder (`dev:/workers ••••••`); a launch that misses a key names the folders that have it |
| Completion of secret keys | Inside `System.getenv("…")`, `process.env.…`, `Bun.env.…`, `import.meta.env.…`, `os.environ["…"]`, `os.getenv("…")`, `os.Getenv("…")`, `Environment.GetEnvironmentVariable("…")`, `ENV["…"]`, `env("…")`, `${…}` and after `infisical://`, in any file. Keys that exist only in other environments are shown struck out |
| Key icon in the gutter | Click to reveal the value briefly; menu: copy (the clipboard is cleared again), override locally, show in the tool window (selects the secret) |
| Masked hint (`dev ••••••`) | After code that reads a secret and on references in `.env` files; click it to open the secret in the Secrets tool window |
| `.env` intentions | *Bind to Secret…* (choose from every folder of the bound environment, root included, same and similar names first; a key from another folder is written as `infisical://folder/KEY`; clear the value or keep it as a local fallback) and *Extract Value to Secret Manager…* (creates the secret from the value, then binds the line and clears it) |
| env-spec decorators | In `.env` files that use decorators: completion of decorator names after `# @`; the inspection *env-spec decorators in .env files* reports malformed decorator comments, unknown or misspelled decorators (quick fix), header-only decorators on a variable and the other way round, repeated ones, `@required` with `@optional`, invalid `@type` values and plain values that don't fit their `@type` |
| Editing secrets | *Edit Secret…* in the tool window: change the value (filled in masked, *Show* reveals it), the comment and the key of a secret defined in that folder. Changing the key renames the secret (refused when that name is taken in the folder; the value is kept unless you change it), and *Update references in this project* (on by default) rewrites run configurations, `.env` files and the terminal's references to the new name, undoable with Edit › Undo. References that name no environment follow the selected one, so they are left as they are (the rename applies to one environment); the notification offers *Update Those Too*. Same permissions, prod guard and change requests as creating |
| Moving secrets | *Move…*, Cut / Paste (the IDE's shortcuts) and drag and drop onto a folder or environment in the tool window, always through a confirmation where destination folder (with completion), environment and overwriting can be changed. Value, comment, tags and metadata are copied first; the originals are removed only after that worked |
| Tags, rotation and metadata | Shown after a secret's name and in its tooltip. **Infisical**: tags, a *rotated* badge on secrets a secret rotation manages (hover for the rotation's name and schedule), reminders; *Edit Secret…* and *New Secret…* pick tags from the project's tags; *Rotate Now…* on a rotated secret runs its secret rotation (confirmation, prod guard, not while *Read-only*; needs the Secret Rotation *Rotate Secrets* permission). A rotated secret's value and key aren't edited from the IDE (Infisical refuses it), its comment and tags are. **Vault/OpenBao** (KV v2): the secret path's `custom_metadata`, read only when you hover or open *Edit Secret…*, edited there as a description and `key=value` lines. **1Password**: item tags. **AWS**: an environment's description, tags and rotation |
| Deleting secrets | *Delete…* (or the Delete key): type the key to confirm (*Copy Name* copies it), or the number of secrets for several |
| Read-only | *Settings › Tools › EnvBridge › Read-only*: this IDE never creates or changes secrets and environments; the write actions are turned off and every write is refused before it is sent. Reading and injecting are unaffected |
| Creating environments | *New Environment…* in the tool window (with a project selected): name and slug. Same permissions as creating secrets |
| Creating secrets | *New Secret…* in the tool window and *Extract Value to Secret Manager…* in `.env` files: key, value, comment, environment and folder. Needs browser sign-in or a machine identity with write permission (OAuth applications are read-only). A folder with a change policy gets a change request instead |
| Tooltip | The environments that define the key, and its comment. Values are never shown unless you ask |
| Keeping up to date | What completion, hints and tooltips know is read in the background, keys only, and refreshed every 10 minutes (or the cache time, if you set it longer) and when the binding changes; it reuses what the tool window just read. *Refresh* in the tool window reloads it. Vault/OpenBao and 1Password index only the bound folder (secret path or item; with an environment layout, Vault also each environment's own path), and Doppler only root configs plus the bound one, to save requests (see their sections) |
| Masked hint | `dev ••••••` after code that reads a secret (Settings › Editor › Inlay Hints › EnvBridge) |
| Inspection *Secret missing in the bound environment* | An `infisical://KEY` whose key doesn't exist, or a variable other environments define but the bound one doesn't. Quick fix: switch environment |
| Status bar | `Infisical: dev`, click to switch. Production-like environments show a warning icon |
| *Run › Run with Secrets Environment…* | Runs the selected configuration once against another environment |
| Prod guard | Asks before a launch or terminal uses secrets from production environments, and before secrets there are created or changed. Production is the project's own pick (*This Project › Production environments*, e.g. `main, eu-live`) or else the name pattern `prod*, production*, prd*, live*` (*General*; `prd` is Doppler's production config). *Ask before secrets from production environments are used or changed* turns the prompt off; production stays marked |
| Console masking | Values handed to a running process are replaced by `••••••` in its Run / Debug console (values of at least 4 characters) |
| Local overrides | Your own value for a secret, for this project on this computer, kept in the IDE's password storage |
| `appsettings*.json` | Settings an Infisical secret overrides at run time get a key icon and tooltip; the inspection *Plaintext secret in appsettings* reports values Infisical provides (quick fix: clear the value) |
| Bound project only | With a project bound (and *Only use the bound project*, default on), the tool window shows only that project and references to other projects or connections are refused |

## Signing in

| Method | Use when | Setup |
|---|---|---|
| **Browser sign-in** | You have an Infisical account (default) | None. The plugin opens `<instance>/login` in your browser and receives the session on a one-time `127.0.0.1` port, as `infisical login` does |
| **OAuth application** | Your organization wants least-privilege, read-only tokens | An org admin creates an OAuth application (*Organization Settings › OAuth Applications*) with the redirect URI `http://127.0.0.1:51327/callback` (or your chosen port) and shares the client ID and secret. The token can only read secrets (`secrets:read`). Such tokens may not be allowed to list projects; bind the project ID through `.infisical.yaml` or the settings |
| **Machine identity** | No browser, or a dedicated identity | A machine identity with Universal Auth: client ID and client secret (and optionally the organization slug) |

### HashiCorp Vault and OpenBao

One provider covers both (same API). In EnvBridge terms a **KV mount** (version 1 or 2) is a project, a **namespace** is an environment
(`root` without one), a **secret path** is a folder and the secret's **fields** are its keys. References name the mount and path the
way `vault kv get secret/app/db` does:

```
DB_PASSWORD=vault://secret/app/db#password
VAULT=vault://secret/app/db#*                          every field of secret/app/db
VAULT=vault://secret/app#**                            every field of every secret below secret/app
TEAM_DB=vault://secret/app/db#password?namespace=team-a
```

| Method | Setup |
|---|---|
| **OIDC (browser)** | The OIDC auth method's role must allow the redirect URI `http://localhost:8250/oidc/callback` (the Vault CLI's; the port can be changed). Role and auth mount path are optional |
| **Token** | Paste a token, or leave it empty to use the Vault CLI's `~/.vault-token`. EnvBridge never revokes such a token on sign-out |
| **AppRole** | Role ID, and the secret ID (kept in the password storage) |
| **Userpass / LDAP** | Username, and the password (kept in the password storage) |

Every method also takes a **namespace** (where to sign in; the default environment), **more namespaces** to show, and the **KV
mounts**, needed only when the token may not list secret engines. Renewable tokens are renewed before they expire; AppRole,
userpass and LDAP sign in again by themselves; OIDC asks you to sign in again.

**KV version 1 and 2** mounts both work, for reading, injecting and writing. EnvBridge finds a mount's version the way the
Vault CLI does (it asks the server once per mount and remembers it for a few minutes), so nothing needs to be configured.

**Environments in paths (environment layout).** Open-source Vault and OpenBao have no namespaces, so most teams put the
environment into the path: `secret/myapp/dev/db`, `secret/myapp/prod/db`. Set the connection's **Environment layout** to say
where, with `{project}` and `{env}` as whole path segments: `{project}/{env}` (`secret/myapp/dev/db`), `{env}/{project}`
(`secret/dev/myapp/db`), `{env}` (a mount per application) or with fixed segments (`apps/{project}/{env}`). Then a project is the
mount plus the application (`secret/myapp`), its environments are the paths found at `{env}`, and the status bar switcher, the
production guard, *Compare environments* and *Run with environment…* work as with Infisical. Empty (the default) keeps
namespaces as environments, so existing connections and references are unchanged. With a layout everything is read in the
connection's namespace. A short reference follows the selected environment:

```
DB_PASSWORD=vault://./db#password            secret/myapp/<selected environment>/db
MODE=vault://#MODE                           the environment's own path, secret/myapp/<selected environment>
PROD_DB=vault://./db#password?env=prod       always prod
DB_PASSWORD=vault://secret/myapp/dev/db#password   a full path still means exactly that secret
```

`./` (or nothing before `#`) means "in the bound project and the selected environment", so `ENVBRIDGE_ENVIRONMENT` and
*Run with environment…* switch these references too, and *Copy Reference* writes them for the bound project. *New Environment…*
writes nothing to Vault: the environment shows until you add its first secret, which creates its path. Renames and moves update
full references by their path and short ones like other references that follow the selected environment.

**Writing**: *New Secret…*, *Edit Secret…*, *Move…* and *Delete…* change one field of a secret (the secret path is the folder; at
least one path segment below the mount). Each write reads the secret, changes the field and writes the whole secret back, so the
other fields keep their values and types. Moves work between secrets, mounts (also between KV versions) and namespaces; a field
is written to its new place before it is removed from the old one, and a name already taken there stops the move before anything
is written. The token's policy decides what is allowed. Namespaces themselves are not created from the IDE (environments of an
environment layout are, see above).

- **KV v2**: the write is a new version with check-and-set, so a change someone made meanwhile is refused instead of
  overwritten. Removing a secret's last field deletes its latest version, which Vault can restore.
- **KV v1** has no versions and no check-and-set: **a change someone else makes to the same secret between EnvBridge's read and
  its write (a fraction of a second) is overwritten, and can't be detected.** Removing a secret's last field deletes the secret
  for good (KV v1 keeps no history).

**Description and metadata** (KV v2): hovering over a secret path or one of its fields in the tool window reads its
`custom_metadata` (one request, only then; never while listing) and shows the key `description` as its description and the
other keys as tags. *Edit Secret…* edits them (they apply to the whole path, shared by its fields): saved with
`POST <mount>/metadata/<path>`, which the token's policy must allow (`update` on `<mount>/metadata/<path>`, `read` to show it).
If it doesn't, the value is still saved and you are told the metadata wasn't. Changing only the metadata writes no new version
of the secret. KV v1 has no metadata.

**Editor features** (completion, hints, tooltips, the missing-secret inspection) know the fields of the **bound folder**
(*Settings › Tools › EnvBridge › This Project › Folder*, e.g. `/app/db`) and of the secrets below it, read in the background with
a couple of requests. Fields of other secrets aren't suggested and aren't reported as missing; references to them still resolve
when you run. With the folder left at `/`, the whole mount is read (one request per folder and secret, up to 500 secrets), as
before.

### 1Password

A **vault** is a project, an **item** is a folder and the item's **fields** are its keys. 1Password has no environments, so every
vault has one, `default`. References are 1Password's own [secret references](https://developer.1password.com/docs/cli/secret-reference-syntax/),
so the ones you already use with `op run` or `op inject` work unchanged:

```
DB_PASSWORD=op://Private/Database/password
API_KEY=op://Dev/AWS Keys/prod creds/secret access key      a field in a section ("prod creds")
OP=op://Dev/Stripe/*                                          every field of the item (EnvBridge extension)
OP=op://Dev/**                                                every field of every item in the vault (at most 100 items)
WORK_DB=op://Private/Database/password?connection=Work
```

- Vault, item, section and field may be names or IDs. Names are written as they are, spaces included (as 1Password writes them);
  other special characters can be percent-encoded (`%3A`); a name with a `/` must be given by its ID, as in 1Password.
- The section may be left out: `op://Dev/Database/api key` finds the field in any section, top-level fields first. Fields are
  matched by label (case-sensitive); empty fields are skipped.
- `?attribute=` other than `value` (`otp`, `type`, …) and `?ssh-format=` are not supported and say so.
- Items that share a title, or whose title contains `/`, are shown and addressed by their IDs.
- With *Only use the bound project*, a reference may name the bound vault by its name or its ID.

| Method | Setup |
|---|---|
| **1Password app (through the CLI)** (default) | Install the [1Password CLI](https://developer.1password.com/docs/cli/get-started/) and turn on *Settings › Developer › Integrate with 1Password CLI* in the 1Password app. **Sign In** makes the app ask you to approve; no password or token is entered in the IDE. Optional: the account (shorthand, sign-in address or ID, when you have several) and the path to `op` if it isn't found on `PATH` or in the usual install locations. Sign-out stops EnvBridge from running `op` in the background |
| **Service account (through the CLI)** | A [service account](https://developer.1password.com/docs/service-accounts/) token, kept in the IDE's password storage and given to `op` only in the environment variable `OP_SERVICE_ACCOUNT_TOKEN`, never on its command line. Sign-out forgets the token |
| **1Password Connect server** | The server's address (`http://localhost:8080` for one on your machine) and an access token, kept in the password storage. Sign-out forgets the token |

**Tags**: an item's tags are shown on the item and its fields (read-only). The connection option **Only items tagged**
(comma-separated, e.g. `dev, payments`; a tag includes its nested tags, so `env` matches `env/dev`) shows only items with one of
these tags in the tool window, whole-vault imports (`op://Dev/**`) and completion; references naming an item still work
whatever its tags.

`op` takes about half a second per call: a whole-item import is one call, a whole-vault import one call per item, and later
whole-vault imports only one call per item that changed since (1Password counts every change in the item's version). Values are
cached in memory like any other secrets.

**Editor features** (completion, hints, tooltips) know the fields of the **bound item** (*This Project › Folder*, e.g.
`/Database`): one `op item get` or Connect request every 10 minutes, which keeps a service account well within its hourly limit.
Other items' fields aren't suggested; references to them still resolve when you run. With the folder left at `/`, every item of
the vault is indexed: one listing, plus one read per item that changed.

**Open in Dashboard** (CLI methods): an item, or a field of it, opens through its 1Password private link
(`https://start.1password.com/open/i?…`, as *Copy Private Link* in the 1Password app makes), in the 1Password app or on the web;
a vault opens your account's web app. EnvBridge learns the account with `op whoami` when you sign in or the vaults are listed.
Connect servers have no account to open, so there is no link for them.

**Writing** (tool window *New Secret… / Edit Secret… / Move… / Delete…*, `.env` *Extract value to …*; off with *Read-only*):

| Action | In an item (`/Database`, or a section `/Database/Extra`) | At the vault's root (`/`) |
|---|---|---|
| New secret | Adds a concealed field with that label (to the section, if given). Refused if the item already has a field with that label. A comment is added to the item's notes as `KEY: comment` | Creates a **Password** item titled with the key, the value in its `password` field (`op://Dev/KEY/password`), the comment as its notes. Refused if an item with that title exists |
| Edit | Changes the field's value. Fields have no comments: the item's **notes** are shown as the comment, and editing it changes them. A new key changes the field's label (refused if another field of the item has it, and for built-in fields such as a login's username or password) | — |
| Delete | Removes the field (a built-in one, such as a login's password, is emptied instead). **Items are never deleted** | — |
| Move | Copies the fields into the destination item, then removes them from the source item; if that fails, you are told which now exist in both. Without *Overwrite*, a field already in the destination stops the move before anything changes | Not a destination: choose an item |

Each write reads the item and saves it whole. With Connect, the item's version is sent along: if it changed meanwhile, the save
is refused ("refresh and try again"). Through the CLI, values are handed to `op item create` / `op item edit` as the item's JSON
on standard input, never as `field=value` arguments (which other processes could read); this needs an `op` that accepts piped
item JSON. EnvBridge checks that the change took effect and says so if it didn't.

### Doppler

A Doppler **project** is a project and a **config** is an environment: `dev`, `dev_personal`, `stg`, `prd` and branch configs
such as `dev_feature` (shown as *dev_feature (branch of dev)*). Doppler has no folders. Values are Doppler's computed ones, so
Doppler references (`${DB_HOST}`) are resolved as `doppler run` resolves them.

```
DB_PASSWORD=doppler://DB_PASSWORD                    from the bound config
PRD_PASSWORD=doppler://prd:DB_PASSWORD               from config prd
DOPPLER=doppler://dev_personal:*                     every secret of config dev_personal
WEB_URL=doppler://PUBLIC_URL?project=web&connection=Work
```

- A folder in a reference (`doppler://app/KEY`) is refused: Doppler has none.
- Doppler's own `DOPPLER_PROJECT`, `DOPPLER_CONFIG` and `DOPPLER_ENVIRONMENT` are not injected (`doppler run` sets them).
- The CLI's `doppler.yaml` (`setup:` with `project` and `config`, or a list of them with a `path` per directory) binds the
  project and config, as `doppler setup` does. The CLI's own `~/.doppler/.doppler.yaml`, which holds its tokens, is never read.
- **Writes**: *New Secret…*, *Edit Secret…* (value, note and name; a note is shared by all configs of the project and follows a
  renamed secret to its new name), *Move…* to another
  config (copies the raw value, then deletes the original; a secret that can't be deleted stays in both and is reported),
  *Delete…*, and *New Environment…*, which creates a branch config: name it after its environment, `dev_feature` or
  `dev-feature`. Names must be uppercase letters, digits and underscores. *Read-only* turns all of it off.

| Method | Setup |
|---|---|
| **Browser sign-in** (default) | None. **Sign In** opens Doppler in your browser and shows a code in the IDE; approve in the browser if it shows the same code, as with `doppler login`. The token is kept in the IDE's password storage and revoked on sign-out |
| **Token** | A personal token (`dp.pt.…`), a service account token (`dp.sa.…`) or a service token (`dp.st.…`), kept in the password storage. A service token reads one config, so only that project and config are offered. Sign-out forgets the token but doesn't revoke it |

Editor features (completion, tooltips' "defined in", the missing-secret inspection) read the root configs (`dev`, `stg`, `prd`) and
the bound config, not the other branch and personal configs; the status bar and *Run with Secrets Environment…* still offer every config.

Doppler is a hosted service: the API address is `https://api.doppler.com` (it can be changed, e.g. for a proxy in front of it).
"Open in dashboard" opens the config in the Doppler dashboard. Doppler can't be self-hosted, so this integration is tested
against a mock of its API only, not a live account.

### AWS Secrets Manager

A connection is an AWS account in one **region**: pick the region's endpoint (`https://secretsmanager.eu-west-1.amazonaws.com`)
or type another one (a VPC endpoint, or LocalStack's `http://localhost:4566` with the *Region* option set). EnvBridge follows the
common convention of **one secret per application and environment holding key/value JSON**: the last segment of a secret's name
is the **environment**, everything before it the **project** (the name prefix), and the JSON object's top-level fields are the
keys. `myapp/dev` → project `myapp`, environment `dev`; `team/myapp/prod` → `team/myapp`, `prod`. Names without `/` are listed
under *(no prefix)*. There are no folders.

**Name layout** (optional, per connection): if your secrets are named differently, describe the names with `{project}` and
`{env}`, each exactly once: `{env}/{project}` for `prod/myapp`, `acme/{env}/{project}` for `acme/prod/myapp`, `{project}-{env}`
for `myapp-prod`. The default is `{project}/{env}`. The text around the placeholders must match exactly; `{env}` never contains
`/`, `{project}` may span several segments, and where a name could be split two ways the environment is the shortest part
(`{env}/{project}`: the first segment; `{project}-{env}`: the text after the last `-`). Names that don't follow the layout are
**not shown**; the connection says how many were left out. Fixed text at the start (`acme/`) is used as a server-side filter.
New environments are named by the layout. **References don't change with the layout**: `aws-sm://myapp/prod#KEY` always means
project `myapp`, environment `prod`, whatever the secret's name in AWS.

```
DB_PASSWORD=aws-sm://myapp/dev#DB_PASSWORD             key DB_PASSWORD of secret myapp/dev
AWS=aws-sm://myapp/dev#*                               every key of myapp/dev
DB_PASSWORD=aws-sm://#DB_PASSWORD                      the bound secret (bound prefix, selected environment)
PROD_PASSWORD=aws-sm://#DB_PASSWORD?env=prod           the bound prefix, environment prod
EU_PASSWORD=aws-sm://myapp/prod#DB_PASSWORD?connection=EU
```

- Values that aren't strings (numbers, booleans, nested objects) are passed as their JSON text. A secret holding a **plain
  string** instead of JSON is one key named after its name's last segment (`aws-sm://myapp/STRIPE_KEY#STRIPE_KEY`). Binary
  secrets are not supported.
- A connection is one region; for a secret in another region, add a connection for it and name it with `?connection=`.
- *Secret name prefix* (optional) lists only secrets whose names start with it, for large accounts (combined with the name
  layout's fixed start, if any).
- **Descriptions, tags and rotation**: a secret's description is the comment of all its keys; changing a key's comment in *Edit
  Secret…* changes the secret's description (for every key of it; no new version is made). The tool window shows each
  environment's description, tags and automatic rotation (on/off, last and next rotation) as a tooltip, and *Rotate Now…* (in the
  environment's context menu, only where rotation is on) starts a rotation with the secret's rotation settings, after a
  confirmation (and the production guard's).
- **Fewer billed requests**: Secrets Manager bills API calls. Secrets needed together (a launch's references, the environments
  read for completion) are read up to 20 at a time with `BatchGetSecretValue`; values are kept in memory with their version, so
  browsing and completion re-read only secrets whose version changed since the last listing. Launches always read current values.
  If `BatchGetSecretValue` isn't allowed, EnvBridge reads one by one with `GetSecretValue` (and doesn't try the batch again for an
  hour).

| Method | Setup |
|---|---|
| **Access key** (default) | Access key ID, secret access key and, for temporary credentials, the session token. All three are kept in the IDE's password storage. Sign-out forgets them; the key itself isn't deactivated |
| **AWS profile** | A profile name from `~/.aws/credentials` / `~/.aws/config` (default `default`), read when needed and never copied. Profiles with static keys work as they are; **IAM Identity Center (SSO)**, role and `credential_process` profiles get temporary credentials from the AWS CLI v2 (`aws configure export-credentials`, kept in memory only): run `aws sso login --profile <name>` first when the SSO session has expired |
| **IAM Identity Center (SSO)** | Sign in in the browser without the AWS CLI: enter the SSO start URL (`https://my-company.awsapps.com/start`), the SSO region, and the account ID and role name (both optional when you have only one). *Sign In* opens AWS's confirmation page and shows a code to compare with the one there. The session tokens are kept in the IDE's password storage and renewed automatically; the short-lived role credentials stay in memory. When the session can't be renewed, sign in again. Sign-out logs out of the AWS access portal and forgets everything |
| **Environment variables** | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` of the IDE's process |

**Writing**: *New Secret…* adds a key to the environment's secret (creating the secret if it doesn't exist), *Edit Secret…*
changes a value or renames a key, *Move…* / *Copy* move keys between environments and prefixes, *Delete…* removes a key (the
last one leaves `{}`), and *New Environment…* creates the secret `<prefix>/<name>` holding `{}`. **AWS secrets are never
deleted.** Every write reads the secret, writes a new version that isn't current yet, and makes it current only if nobody changed
the secret meanwhile (AWS's `UpdateSecretVersionStage` with `RemoveFromVersionId`); otherwise nothing changes and you are asked to
refresh (this also happens when a rotation finishes during the write). Plain-string secrets can change their value but are never
converted to JSON. A new key's comment becomes the secret's description only when it creates the secret or the secret has none.

**IAM permissions**: reading needs `secretsmanager:ListSecrets`, `GetSecretValue` and (recommended, for fewer requests)
`BatchGetSecretValue`, which AWS only allows together with `GetSecretValue` on each secret. Writing needs `PutSecretValue`,
`UpdateSecretVersionStage`, `CreateSecret` (new environments), `DescribeSecret` and `UpdateSecret` (descriptions); *Rotate Now*
needs `RotateSecret` (and the rotation function's own permissions).

"Open in dashboard" opens the secret in the AWS console of its region. Requests are signed with AWS Signature Version 4 by the
plugin itself (no AWS SDK). Tested against LocalStack and a signature-checking mock, not a live AWS account.

## Security

- **Credentials** (session tokens, refresh tokens, client secrets) are stored only in the IDE's password storage (system keychain,
  KeePass or the master-password protected store, per *Settings › Appearance & Behavior › System Settings › Passwords*). Settings
  files hold IDs and URLs only.
- **Secret values** never touch the disk: they are fetched for each launch, put into an in-memory copy of the run configuration
  (the configuration itself is never changed, so nothing reaches `workspace.xml`) and cached in memory only, for browsing (5
  minutes by default, cleared on sign-out). They are not logged.
- **Instances**: `https://` is required (plain `http://` only for `localhost`). Requests don't follow redirects, so a token can't
  be redirected to another host. A `domain` in a project file only *selects* among the connections you created; it never makes the
  plugin send credentials to a new server.
- **Browser sign-in callback**: bound to `127.0.0.1` only, single use, short-lived. It accepts only a JSON `POST` from the
  instance's own origin (checked on the CORS preflight and on the request) and a loopback `Host` (DNS-rebinding protection). The
  token is validated against the instance before it is stored. OAuth uses PKCE (S256) and `state`.
- **1Password CLI**: `op` is run as a child process with `--format json` and no input. A service account token reaches it only
  through its environment (`OP_SERVICE_ACCOUNT_TOKEN`), never through its command line, which other processes can read;
  inherited `OP_SESSION_*`, `OP_CONNECT_*` and `OP_SERVICE_ACCOUNT_TOKEN` variables are removed first. Its output is not logged;
  its error text is shown shortened.
- **AWS**: the secret access key only computes each request's signature and is never sent. Profile credentials stay in the
  AWS files and are read when needed; the AWS CLI gets only the profile name on its command line, and the temporary credentials it
  prints are kept in memory, never logged. IAM Identity Center tokens (and the client registration) are kept in the IDE's password
  storage only and sent only to AWS's sign-in and portal endpoints of the configured region, in request bodies or the
  `x-amz-sso_bearer_token` header (never in a URL); the role credentials are kept in memory.
- See [SECURITY.md](SECURITY.md) for reporting vulnerabilities, and the [threat model](docs/DESIGN.md#threat-model-summary).

## Building

```shell
./gradlew build          # compile, test, build the plugin ZIP
./gradlew runIde         # a sandbox IDE with the plugin
./gradlew verifyPlugin   # the JetBrains Plugin Verifier against the IDEs in gradle.properties
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the project layout and conventions.

## License and trademarks

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

This is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by Infisical Inc., HashiCorp (an
IBM company), the OpenBao project, AgileBits Inc., Doppler Inc. or Amazon Web Services, Inc. "Infisical" and the Infisical logo are trademarks of Infisical Inc.;
"HashiCorp" and "Vault" are trademarks of HashiCorp; "OpenBao" is a trademark of the Linux Foundation; "1Password" is a trademark
of AgileBits Inc.; "Doppler" is a trademark of Doppler Inc.; "AWS" and "AWS Secrets Manager" are trademarks of Amazon.com, Inc. or its affiliates. This plugin uses none of their logos or other brand assets, and it contains none of their source code. It talks
to their documented APIs, runs the 1Password CLI and AWS CLI the user installed, and reimplements documented behaviour of their CLIs
(Infisical's browser sign-in callback and `.infisical.yaml`, Vault's OIDC redirect URI and `~/.vault-token`, 1Password's secret
reference syntax, Doppler's `doppler login` flow and `doppler.yaml`).
