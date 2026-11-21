# Changelog

## [Unreleased]

### Added

- **Moving secrets updates their references** in the project: run configurations' environment variables, `.env` files (values
  and binding comments) and the terminal's references follow the secrets to their new folder or environment (*Update
  references in this project*, on by default). Edit › Undo puts them back in one step; *Undo Move* in the notification also
  moves the secrets back
- **1Password: writing.** *New Secret…*, *Edit Secret…*, *Move…*, *Delete…* and *Extract value to …* work for 1Password
  connections (all three access methods): a secret is a field of an item; a new secret at a vault's root becomes a Password item
  (`op://vault/KEY/password`); the item's notes are the secrets' comment; fields are moved between items (copy first, remove
  after); items are never deleted. With the CLI, values go to `op` only on its standard input as item JSON, never on its command
  line; with Connect, an item changed meanwhile is refused instead of overwritten. *Read-only* turns it off as for every provider
- **Doppler** (unofficial integration), reading, injecting and writing: Doppler projects are projects and configs (`dev`,
  `dev_personal`, `stg`, `prd`, branch configs) are environments. References `doppler://KEY`, `doppler://prd:KEY`,
  `doppler://*` for every secret of a config, `?project=` and `?connection=`; Doppler's computed values (its `${…}` references
  resolved). Sign in through the system browser like `doppler login` (the IDE shows the code to compare, the token is kept in the
  IDE's password storage and revoked on sign-out) or with a personal, service account or service token (a service token offers
  just its project and config). Create, edit (with notes), move between configs and delete secrets, and create branch configs;
  uppercase names are checked before anything is sent. Reads the Doppler CLI's `doppler.yaml`. Tested against a mock of the
  Doppler API (Doppler can't be self-hosted)
- **1Password** (unofficial integration), reading and injecting: 1Password's own secret references work as they are
  (`op://vault/item/[section/]field`, names or IDs), plus `op://vault/item/*` for every field of an item, `op://vault/**` for
  every item of a vault and `?connection=`. Access through the 1Password app via the 1Password CLI (the app approves, nothing to
  paste), a service account token (given to the CLI only in its environment, never on its command line) or a 1Password Connect
  server. Vaults are projects with one environment (`default`), items are folders
- Sign-in methods that need no instance address (a CLI talking to its desktop app): the connection dialog hides the address
- *Only use the bound project* accepts references that name the bound project by its name (a 1Password vault), not only its ID
- **HashiCorp Vault and OpenBao** (KV v2, namespaces), reading and injecting: `vault://secret/app/db#password`, `#*` for every
  field of a secret and `#**` for every secret below a path, `?namespace=` and `?connection=`. Sign in with OIDC in the system
  browser (the Vault CLI's redirect URI), a token or the Vault CLI's `~/.vault-token`, AppRole, userpass or LDAP; tokens and
  passwords only in the IDE's password storage, renewable tokens renewed before they expire. Mounts are listed from the server or
  set on the connection. Creating, editing, moving and deleting fields, with check-and-set (a change made meanwhile is refused,
  other fields keep their values and types)
- Choosing the secret manager when adding a connection, once there are several
- Opt-in integration tests against real Doppler and 1Password test accounts (run when their credentials are set as
  repository secrets)
- Integration tests against real HashiCorp Vault, OpenBao and self-hosted Infisical servers in Docker (images pinned)
  (`scripts/integration/start.sh`, `./gradlew integrationTest`), run in CI

### Fixed

- 1Password: moving fields to the source item named another way (a title in other letter case) could delete them
- Editing a connection (a new token or password, another namespace or option) takes effect right away instead of after the
  old session expires
- Doppler: production configs (`prd`, `prd_*`) are production for the prod guard; a `doppler.yaml` list binds the entry that
  holds the project directory (or nothing), not the first one; a passing server problem no longer ends a browser sign-in
- Vault: a move that would overwrite without Overwrite, or whose source field is gone, changes nothing and says why;
  removing a secret's last field is protected by check-and-set; `.` / `..` in paths are refused
- 1Password names with emoji work in references; item names starting with `-` are refused rather than passed to the CLI
- *Settings › This Project* no longer reads project files on the UI thread, and uses the selected connection's provider
- Changing secrets (and other requests with a body) on Infisical instances served over plain `http://` (a local self-hosted one)
  failed with HTTP 405: such requests now use HTTP/1.1 instead of an HTTP/2 upgrade
- OpenBao: an expired or revoked token is recognised although the server only says "permission denied", so the plugin signs in
  again instead of reporting missing permissions

### Changed

- The run configuration variable that picks the environment is `ENVBRIDGE_ENVIRONMENT` (for every secret manager);
  `INFISICAL_ENVIRONMENT` keeps working
- Intention and inspection descriptions no longer assume Infisical
- Actions and messages shared by every secret manager no longer say "Infisical": *Run with Secrets Environment…*, *Add Secrets
  to Run Configuration…*, *New Terminal Tab with Secrets*, *Load Secrets into This Terminal*, *Bind to Secret…*, *Extract Value
  to Secret Manager…*
- New plugin icon: a suspension bridge carrying a key (the Secrets tool window keeps its key icon)
- **Reference format**: folders are absolute from the environment's root and the environment goes before a colon:
  `infisical://KEY` (root), `infisical://workers/KEY`, `infisical://prod:KEY`, `infisical://prod:workers/KEY`. The binding's
  folder is only a default for new secrets and the tool window. References written the old way (`infisical://prod/KEY`) now
  name folder `/prod`; a launch with one stops and says what to write instead
- Completion always shows each key's folder; after `infisical://workers/` it lists that folder's keys

### Added

- The plugin is called **EnvBridge** (an unofficial Infisical integration); existing settings and sign-ins are kept

- Connections to Infisical Cloud (US, EU), dedicated and self-hosted instances, with three sign-in methods: browser sign-in (like
  the Infisical CLI), OAuth application (read-only, PKCE) and machine identity (Universal Auth). Credentials are stored only in
  the IDE's password storage
- Secret references in the environment variables of run configurations: `KEY=infisical://KEY`, `infisical://prod:backend/KEY`,
  whole-folder imports with `infisical://*` and `…/**`. Values are fetched fresh for every launch and put into an in-memory copy of
  the configuration
- Injection for Application, JUnit, TestNG, Spring Boot, Kotlin, Gradle, Maven and other run configurations with standard
  environment variables, for every executor (Run, Debug, Coverage, Profile)
- Fail closed: a launch whose secrets can't be loaded stops, with Sign in, Retry and Run without secrets once
- Project binding from the Infisical CLI's `.infisical.yaml` / `.infisical.json` (project, environment, Git branch mapping, folder)
  or from the settings, with `INFISICAL_ENVIRONMENT` per run configuration
- Injection for Node.js, npm / pnpm / Yarn / Bun scripts, Python (pytest, Django, FastAPI, Flask), Go and Docker (image,
  Dockerfile, Docker Compose) run configurations. Docker gets the values as container / `docker compose` environment, without
  writing files
- `.env` files attached to a run configuration may hold `infisical://` references instead of secrets
- Terminal (opt-in per project in the plugin's settings): new tabs get the referenced secrets
- Editor integration in every language: completion of secret keys where code reads environment variables and after
  `infisical://`, a gutter icon to reveal / copy / override a secret, a tooltip with the environments that define it, masked
  inlay hints, and an inspection for secrets missing in the bound environment
- Status bar widget with the bound environment and a switcher; *Run with Infisical Environment…*; a prod guard that asks before
  production secrets reach a process; environment comparison
- Injected values are masked in Run and Debug consoles; local overrides of secrets are kept in the IDE's password storage
- Rider: injection for .NET Project and .NET Executable run configurations (ASP.NET Core included); `appsettings*.json`
  marks settings an Infisical secret overrides and reports plaintext secrets
- Database data sources: an "Infisical" authentication option with user and password as secret references
- *Add Secrets to Run Configuration…* (Run menu): pick secrets of the bound folder, or the whole folder, and their references are
  written into a run configuration's environment variables. *Copy as Environment Variables* in the tool window copies
  `NAME=infisical://…` lines to paste into any Environment Variables dialog
- The Run console starts with a line naming the variables that came from secrets and their environment and folder (no values);
  secrets with empty values are named there, warned about at launch and shown as "(empty)" in the run configuration preview
- **New Terminal Tab with Infisical Secrets** (terminal toolbar, *Tools* menu): one terminal tab, in any shell, whose environment
  holds the secrets of the bound folder or the project's terminal references, without enabling the terminal integration
- `.env` binding comments: `KEY= # infisical://KEY` or `KEY=local-fallback # infisical://prod:KEY`. Tools outside the IDE
  read the plain value; launches from the IDE get the secret; the editor shows a masked shadow value (`dev ••••••`) and the
  key icon (reveal, copy, override) on the binding
- `.env` files: an inspection marks plaintext values of variables that are secrets in the bound folder; its quick fixes (and
  *Fix all*) bind them, clearing the value or keeping it as a local fallback. *Bind to Infisical* binds an empty `KEY=` line
- Node.js and npm: references in the *Environment file* field, and in the `.env` / `.env.local` that Nuxt, Vite or Next.js
  read by themselves, are resolved at launch
- **Move secrets** in the tool window: *Move…*, Cut / Paste and drag and drop onto folders or environments, with a confirmation
  (destination folder with completion, environment, overwrite). Built on Infisical's documented duplicate + delete: the
  original is removed only after the copy worked; a destination waiting for approval keeps it
- **Delete secrets** in the tool window, confirmed by typing the key (with *Copy Name*) or the number of secrets
- Completion after `infisical://` offers the keys of every folder of the bound environment, each with its folder, inserting
  `folder/KEY`; folder references get the gutter icon, the masked hint (now with the folder) and the missing-secret check
- A launch that can't find a key in the bound folder names the folders that define it, with the reference to use
- Prod guard: can be turned off (*Ask before secrets from production environments are used or changed*), and a project can pick
  its own production environments (*This Project › Production environments*, with a chooser) for other naming conventions
- *New Secret…*: the folder field completes the environment's folders; *Extract value to Infisical…* prefills the comment
  from the `#` comment lines directly above the variable
- **Edit Secret…** in the Secrets tool window: value (filled in masked) and comment, through `PATCH /api/v4/secrets/{name}`;
  prod guard and change requests as for creating
- **Read-only** setting: turns off creating and editing secrets and environments; every write is refused before it is sent
- *Bind to Infisical secret…* offers the secrets of every folder of the bound environment, root included, with their folder;
  keys from another folder are bound as `infisical://folder/KEY`
- *Copy Key* in the Secrets tool window (toolbar and context menu); several selected secrets give one key per line
- **Load Infisical Secrets into This Terminal** (terminal toolbar and context menu, *Tools* menu): sets the secrets in the shell of
  the current tab, in its own syntax: PowerShell (no script execution needed), cmd, bash / zsh / sh (Git Bash and WSL paths
  on Windows) or fish. Values travel through an owner-only temporary file the shell loads and deletes, never through the
  terminal's screen or history; refused while a command is running
- **New Environment…** in the Secrets tool window: name and slug (derived from the name), then selected
- **Create secrets**: *New Secret…* in the Secrets tool window, and *Extract value to Infisical…* on a plaintext `.env` value
  (creates the secret, then binds the line and clears the value). Production-like environments ask first; folders with a
  change policy get a change request. OAuth connections are read-only and say so
- `.env`: *Bind to Infisical secret…* chooses any key of the bound environment (same and similar names first) and asks whether
  to clear the value or keep it as a local fallback
- Click a masked hint (`dev ••••••`) to open the secret in the Secrets tool window, selected; the gutter's *Show in tool window*
  selects it too
- Secrets tool window: browse projects, environments, folders and secrets with masked values, reveal with auto-hide, copy with
  clipboard auto-clear, copy reference, open in dashboard, run configuration preview

### Fixed

- A personal override now always wins over the shared secret of the same folder, whichever the API lists first
