# Changelog

## [Unreleased]

### Added

- **Decorators in `.env` files.** Comments in the env-spec format of varlock's `.env.schema` work in every `.env` file a launch reads: `# @required` stops a launch whose value is empty or unset and names the variables (also the header's `@defaultRequired=true` / `infer`; `@optional` overrides), `# @sensitive` masks the value in the console like a secret, `# @internal` keeps a variable out of the process, and `# @type=port` (also `string(startsWith=…)`, `enum(…)`, `url`, `number`, `boolean`, `email`, `uuid`) reports values that don't fit when the launch starts. Projects whose `.env` files use none of these launch exactly as before; *Apply env-spec decorators of .env files at launch* in the settings turns it off. In the editor, decorator names are completed after `# @` and a new inspection reports malformed, unknown or misspelled decorators (with a fix), `@required` together with `@optional`, invalid types and plain values that don't match their type
- **Layered `.env` files** (setting, off by default): launches read `.env.schema`, `.env`, `.env.local`, `.env.[environment]` and `.env.[environment].local`, later ones winning, where the environment is the one the launch uses (status bar, `ENVBRIDGE_ENVIRONMENT`, *Run with environment…*)
- **varlock compatibility mode** setting (off by default), the switch for varlock support
- **varlock editor support** (with *varlock compatibility mode* on, in `.env.schema` files, the `.env` files next to one and `.env`
  files using decorators): highlighting of decorators, functions, `$KEY` references and types that works on top of a DotEnv plugin
  or plain text (colours under *Editor › Color Scheme › varlock (EnvBridge)*); completion of header and variable decorators by
  position, `@type` names and options, decorator values, functions (core and the 1Password, Infisical and Vault plugins'), enum
  values after `KEY=` (also from `.env.schema` when editing `.env`) and variable names of all layered files after `$` and in
  `ref(…)`; a *varlock rules in .env files* inspection for deprecated decorators, unknown functions, plugin functions without their
  plugin, references to undefined variables, `@currentEnv` pointing at a missing variable, `@import` of missing files and values
  that don't match a `@type` from another file; documentation on hover for decorators and functions; Ctrl+click from `$KEY` /
  `ref(KEY)` to the variable and from `@import(…)` to the file; Enter continues `#` comments. Nothing in these files is ever run
- **Vault/OpenBao: environments in paths.** A connection's new *Environment layout* says where the environment is in your
  paths, with `{project}` and `{env}`: `{project}/{env}` for `secret/myapp/dev/db`, `{env}/{project}`, `{env}` for a mount per
  application. Projects then are `secret/myapp` with the environments found in Vault, so the environment switcher, the
  production guard, *Compare environments* and *Run with environment…* work without namespaces. The short reference
  `vault://./db#password` (or `vault://#KEY` for the environment's own path) reads the selected environment, `?env=prod` fixes
  one; full references such as `vault://secret/myapp/dev/db#password` keep meaning exactly that secret. *New Environment…*
  writes nothing until the first secret is added. Empty (the default) keeps namespaces as environments, so existing
  connections and references are unchanged
- **Infisical: tags, secret rotations and reminders.** The tool window shows each secret's tags, a *rotated* badge on secrets a
  secret rotation manages (hover for its name, whether it runs automatically, and its last and next rotation) and the secret's
  reminder. *Edit Secret…* and *New Secret…* pick tags from the project's tags. *Rotate Now…* on a rotated secret runs its secret
  rotation (with confirmation and the production guard; not while *Read-only* is on; a missing *Rotate Secrets* permission is
  explained). The value and key of a rotated secret aren't changed from the IDE, since Infisical refuses that; its comment and
  tags can be
- **Vault/OpenBao: description and metadata of KV v2 secrets.** Hovering over a secret path or field shows its `custom_metadata`
  (the `description` key as the description, the rest as tags), read only then, never while listing. *Edit Secret…* edits it;
  when the token's policy doesn't allow writing metadata, the value is still saved and you are told why the metadata wasn't
- **1Password: item tags and *Only items tagged*.** Item tags are shown on items and their fields. The new connection option
  *Only items tagged* limits the tool window, whole-vault imports and completion to items with one of the given tags
- **AWS Secrets Manager: name layouts.** A connection's *Name layout* describes how its secrets are named, with `{project}` and
  `{env}`: `{env}/{project}` for `prod/myapp`, `acme/{env}/{project}`, `{project}-{env}`; the default `{project}/{env}` is the
  naming used so far, so existing connections and references are unchanged. Names that don't follow the layout aren't shown (the
  tool window says how many), fixed text at the start filters on the server, and new environments are named by the layout.
  References keep naming project and environment (`aws-sm://myapp/prod#KEY`), so they work under any layout
- **AWS Secrets Manager: fewer billed requests.** Secrets needed together (a launch's references, the environments read for
  completion) are read up to 20 per `BatchGetSecretValue` request, and values are kept in memory with their version, so browsing
  and completion only re-read secrets whose version changed since the last listing (launches still always read current values).
  Where `BatchGetSecretValue` isn't allowed, secrets are read one by one as before
- **AWS Secrets Manager: descriptions, tags and rotation.** A secret's description is the comment of its keys, and editing a
  key's comment changes the description (without making a new version). The tool window shows each environment's description,
  tags and rotation schedule, and *Rotate Now…* starts a rotation of secrets that have rotation turned on (with confirmation and
  the production guard; not while *Read-only* is on)
- **AWS Secrets Manager: IAM Identity Center sign-in in the IDE**, without the AWS CLI: enter the SSO start URL and region (and
  the account and role, unless there is only one), confirm the code in the browser, done. The session tokens are kept in the IDE's
  password storage and renewed automatically, the role credentials only in memory; sign-out logs out of the AWS access portal
- **Vault/OpenBao: KV version 1 mounts.** Reading, listing, injecting and writing (create, edit, rename, move, delete) now work on
  KV v1 secrets engines too, next to KV v2. Each mount's version is found the way the Vault CLI finds it, so nothing needs to be
  configured; moves also work between KV v1 and KV v2 mounts. KV v1 has no versions or check-and-set, so a change someone else
  makes to the same secret at the same moment can't be detected there (KV v2 writes keep refusing it), and removing a KV v1
  secret's last field deletes the secret for good
- **1Password: Open in Dashboard** for connections through the 1Password app or a service account: an item opens through its
  1Password private link, a vault in your account's web app (the account comes from `op whoami`). Connect servers have no link
- **AWS Secrets Manager** (unofficial integration), reading, injecting and writing. A connection is an account in one region; a
  secret named `<prefix>/<env>` holding key/value JSON is environment `<env>` of project `<prefix>`, and its fields are the keys
  (plain-string secrets are one key). References `aws-sm://myapp/dev#DB_PASSWORD`, `aws-sm://myapp/dev#*` for every key,
  `aws-sm://#KEY` for the bound secret (following the selected environment), `?connection=`. Sign in with an access key (kept in
  the IDE's password storage), an AWS profile (static keys from `~/.aws`; SSO, role and `credential_process` profiles through
  the AWS CLI v2) or the IDE's environment variables. Create, edit, rename, move, copy and delete keys and create environments,
  each write checked against concurrent changes (staging-label check-and-set); AWS secrets are never deleted. Requests are signed
  by the plugin (AWS Signature Version 4, tested against the AWS test suite), so no AWS SDK is bundled. Tested against a
  signature-checking mock and LocalStack
- **Secrets tool window, compact and keyboard-friendly.** The copy variants are in a *Copy* submenu; the toolbar keeps the everyday
  actions (sign-out stays in the settings and the connection's menu). Copy, cut and paste secrets between folders and environments
  (*Paste* copies or moves them, with confirmation). The IDE's standard shortcuts from your keymap work in the tree: Alt+Insert
  new secret, F4 edit, Shift+F6 rename, Ctrl+C / Ctrl+X / Ctrl+V, Delete, F6 move, Ctrl+Alt+Shift+C copy reference, Ctrl+Q reveal,
  Ctrl+Alt+Y refresh; the context menu shows them. *New Environment…* in a project's context menu, for providers that can create one
- **Masked secret hints:** a click selects the secret in the Secrets tool window; the right-click menu has *Preview Secret Value*
  (shown next to the caret until the next keystroke) and *Copy Secret Value*
- **Connection dialog: links to where credentials are made** under token-style sign-in methods: the Doppler dashboard and its
  token guide, the Infisical instance and its machine identity guide, the Vault UI and the AppRole guide, 1Password's service
  account and Connect guides
- **Renaming secrets** from *Edit Secret…*: the dialog has an editable *Key*. A new key renames the secret in the secret manager
  (Infisical: `newSecretName`; Vault/OpenBao: the field is renamed in one check-and-set write, keeping its place, its type and
  the other fields; 1Password: the field's label; Doppler: a change request naming the original, its note carried over), keeping
  the value unless you change it. A name already used in the folder is refused before anything is written. *Update references
  in this project* (on by default) rewrites run configurations, `.env` files and the terminal's references to the new name, as
  one command Edit › Undo puts back
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

- The background keys index (completion, hints, tooltips) re-read everything whenever it reloaded, because it reloads every
  10 minutes while cached listings expire after 5: a reload after a binding or settings change, or soon after the tool window
  read the same folder, now reuses what was read. It never refreshes more often than the cache time you set, and *Refresh* in the
  Secrets tool window now also reloads it, from the server
- Connection dialog: the redirect URIs of Vault OIDC and Infisical OAuth lost their `<port>`, and the password storage note its
  "&"; the dialog shrinks back after choosing a sign-in method with fewer fields
- Network failures read as words ("the connection was refused or the server can't be reached…") instead of exception class
  names like `ConnectException`
- Settings: a connection whose sign-in failed shows *can't sign in* instead of *signed in*; *Sign In* / *Sign Out*, *Check* and
  *Reload Projects* are off while there is no connection; the project file option names every provider's file
- Settings › This Project: with the connection on *Automatic*, changes to the project, environment, folder or production
  environments weren't saved (*Apply* stayed off)
- Editing only a secret's comment (or leaving its value as shown) no longer replaces an Infisical or Doppler value that holds
  `${…}` references with their expanded text
- Moving or renaming secrets no longer rewrites references that name no environment: those follow the selected environment, so
  rewriting them would break the other environments, where the secret didn't change. The notification counts them and offers
  *Update Those Too*. References are matched as launches resolve them (the connection they name, a run configuration's
  environment, a 1Password vault named instead of its ID)
- Edit › Undo of reference updates keeps run configuration and terminal edits made after them
- Copying secrets copies them as stored (Infisical duplicates them, Doppler copies raw values), so references in values keep
  working; a secret that already exists at the destination is left alone
- No *Undo Move* after a move that overwrote secrets at the destination (moving back couldn't restore them)
- Saving, moving and copying can't be cancelled halfway any more, and a rename that went through although a later step failed
  (Doppler's note) is finished and reported instead of shown as an error
- *New Terminal with Secrets*: when no terminal tab can be opened, the secrets are withdrawn instead of going to the next tab
- Settings: adding a connection could do nothing (seen in Rider): the provider chooser opened centred on the focus and could end up
  behind the Settings dialog; it now opens under the + button. An empty connection list has an *Add a connection…* link
- Secrets tool window without connections: the empty tree has an *Add a connection…* link that opens the settings and starts adding
- Settings opened without a project (Welcome screen) show only what applies to every project (connections, general options),
  instead of project sections that would only have changed the template for new projects
- Settings: a project bound to a connection removed since kept *Apply* enabled without any change
- Doppler: browser sign-in failed with *HTTP 400: Invalid CLI version*; it now tells Doppler a CLI release version, as the Doppler
  CLI does
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

- **Fewer requests in the background**, so editor features stay within secret managers' rate limits (a 1Password service
  account's hourly limit, Doppler's) even with several IDEs open. Completion, hints and tooltips now read:
  - **Vault/OpenBao**: only the bound secret path (*This Project › Folder*) and the secrets below it, a couple of requests,
    instead of walking the whole mount (up to ~500 requests every 10 minutes)
  - **1Password**: only the bound item, one `op item get` or Connect request, instead of every item of the vault (up to 101 calls).
    Bound to a whole vault, and for `op://vault/**` imports, only items whose version changed since they were last read are read
    again
  - **Doppler**: root configs and the bound config, no longer every branch and personal config (nor the bound config twice)

  With Vault and 1Password, keys of other secrets and items are no longer suggested in completion; references to them still
  resolve when you run. Leave the folder at `/` to index everything as before
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
