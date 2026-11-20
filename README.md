# EnvBridge

Secrets from your secret manager in the IDE's environment variables, without `.env` files. Works with **Infisical**.

<!-- Plugin description -->
<p><b>Unofficial Infisical integration</b>, not affiliated with, endorsed by or sponsored by Infisical Inc.
"Infisical" is a trademark of Infisical Inc. and is used only to say which service this plugin works with.</p>

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
  <li><b>Terminal</b>: <i>New Terminal Tab with Infisical Secrets</i> opens a tab with the secrets; <i>Load Infisical Secrets
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
  <li>Reads the Infisical CLI's <code>.infisical.yaml</code> / <code>.infisical.json</code> (project, environment, Git branch
      mapping, folder), so the plugin and <code>infisical run</code> pick the same environment</li>
</ul>

<p>Settings: <i>Settings › Tools › EnvBridge</i>. The plugin only reads secrets, except when you create or edit one yourself
(<i>New Secret…</i>, <i>Edit Secret…</i>, <i>Extract value to Infisical…</i>); it never deletes anything. <i>Read-only</i> in the
settings turns all writing off.</p>
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
- The environment comes from (highest first): the reference → `INFISICAL_ENVIRONMENT` set on the run configuration → the
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
| `.env` files | Any run configuration that loads env files (also the *Environment file* field of Node.js and npm): when an attached `.env` contains references, the plugin reads it, resolves the references and passes all its variables. Node.js and npm launches also resolve references in the `.env` / `.env.local` the tool reads by itself (Nuxt, Vite, Next.js): those keep process variables over the file. Values set on the configuration still win. **Binding comments**: `KEY= # infisical://KEY` or `KEY=local-fallback # infisical://prod:KEY` keeps the file valid for tools outside the IDE (they read the plain value) while IDE launches get the secret. An inspection marks plaintext values of secrets in `.env` files; Alt+Enter binds them (clearing the value or keeping it as a local fallback), and *Bind to Infisical* binds an empty `KEY=` line |
| Rider: .NET Project, .NET Executable (ASP.NET Core included) | *Environment variables* of the configuration. *Launch profile* configurations take their variables from `launchSettings.json`, which the plugin can't intercept: use a .NET Project configuration with references instead |
| Database data sources | *Data Source › Authentication: Infisical (EnvBridge)*: user and password as references or plain values, resolved when a connection opens; only the references are saved |
| Terminal | **New Terminal Tab with Infisical Secrets** (next to the terminal's **+**, or *Tools* menu) opens one tab with the secrets, in any shell (PowerShell, cmd, bash, zsh). To give every new tab secrets: *Settings › Tools › EnvBridge › Terminal*, opt in and list what tabs get (`infisical://*` or `NAME=infisical://KEY`, one per line). **Load Infisical Secrets into This Terminal** puts them into the shell of the current tab instead, in that shell's own syntax, decided from the tab's shell: PowerShell, cmd, bash / zsh / sh (also Git Bash and WSL on Windows) or fish. The values go through a short-lived owner-only file the shell loads and deletes, so they appear neither on screen nor in the shell history. Typing `export KEY=infisical://…` yourself does nothing: nothing resolves references inside a running shell |

## In the editor

| Feature | Where |
|---|---|
| Folders | References name their folder: `infisical://workers/KEY` (a bare `infisical://KEY` is the root). After `infisical://`, completion lists the keys of every folder, each with its folder, and inserts `workers/KEY` for other folders; after `infisical://workers/` it lists that folder; masked hints show the folder (`dev:/workers ••••••`); a launch that misses a key names the folders that have it |
| Completion of secret keys | Inside `System.getenv("…")`, `process.env.…`, `Bun.env.…`, `import.meta.env.…`, `os.environ["…"]`, `os.getenv("…")`, `os.Getenv("…")`, `Environment.GetEnvironmentVariable("…")`, `ENV["…"]`, `env("…")`, `${…}` and after `infisical://`, in any file. Keys that exist only in other environments are shown struck out |
| Key icon in the gutter | Click to reveal the value briefly; menu: copy (the clipboard is cleared again), override locally, show in the tool window (selects the secret) |
| Masked hint (`dev ••••••`) | After code that reads a secret and on references in `.env` files; click it to open the secret in the Secrets tool window |
| `.env` intentions | *Bind to Infisical secret…* (choose from every folder of the bound environment, root included, same and similar names first; a key from another folder is written as `infisical://folder/KEY`; clear the value or keep it as a local fallback) and *Extract value to Infisical…* (creates the secret from the value, then binds the line and clears it) |
| Editing secrets | *Edit Secret…* in the tool window: change the value (filled in masked, *Show* reveals it) and the comment of a secret defined in that folder. Same permissions, prod guard and change requests as creating |
| Moving secrets | *Move…*, Cut / Paste (the IDE's shortcuts) and drag and drop onto a folder or environment in the tool window, always through a confirmation where destination folder (with completion), environment and overwriting can be changed. Value, comment, tags and metadata are copied first; the originals are removed only after that worked |
| Deleting secrets | *Delete…* (or the Delete key): type the key to confirm (*Copy Name* copies it), or the number of secrets for several |
| Read-only | *Settings › Tools › EnvBridge › Read-only*: this IDE never creates or changes secrets and environments; the write actions are turned off and every write is refused before it is sent. Reading and injecting are unaffected |
| Creating environments | *New Environment…* in the tool window (with a project selected): name and slug. Same permissions as creating secrets |
| Creating secrets | *New Secret…* in the tool window and *Extract value to Infisical…* in `.env` files: key, value, comment, environment and folder. Needs browser sign-in or a machine identity with write permission (OAuth applications are read-only). A folder with a change policy gets a change request instead |
| Tooltip | The environments that define the key, and its comment. Values are never shown unless you ask |
| Masked hint | `dev ••••••` after code that reads a secret (Settings › Editor › Inlay Hints › EnvBridge) |
| Inspection *Secret missing in the bound environment* | An `infisical://KEY` whose key doesn't exist, or a variable other environments define but the bound one doesn't. Quick fix: switch environment |
| Status bar | `Infisical: dev`, click to switch. Production-like environments show a warning icon |
| *Run › Run with Infisical Environment…* | Runs the selected configuration once against another environment |
| Prod guard | Asks before a launch or terminal uses secrets from production environments, and before secrets there are created or changed. Production is the project's own pick (*This Project › Production environments*, e.g. `main, eu-live`) or else the name pattern `prod*, production*, live*` (*General*). *Ask before secrets from production environments are used or changed* turns the prompt off; production stays marked |
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

This is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by Infisical Inc. "Infisical" and
the Infisical logo are trademarks of Infisical Inc. This plugin uses no Infisical logos or other brand assets, and it contains no
Infisical source code. It talks to the documented Infisical API and reimplements the documented behaviour of the Infisical CLI
(browser sign-in callback, `.infisical.yaml`).
