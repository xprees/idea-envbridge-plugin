# Changelog

## [Unreleased]

## [2026.3.19] - 2026-11-22

### Added

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
- **HashiCorp Vault and OpenBao** (KV v2, namespaces), reading and injecting: `vault://secret/app/db#password`, `#*` for every
  field of a secret and `#**` for every secret below a path, `?namespace=` and `?connection=`. Sign in with OIDC in the system
  browser (the Vault CLI's redirect URI), a token or the Vault CLI's `~/.vault-token`, AppRole, userpass or LDAP; tokens and
  passwords only in the IDE's password storage, renewable tokens renewed before they expire. Mounts are listed from the server or
  set on the connection. Creating, editing, moving and deleting fields, with check-and-set (a change made meanwhile is refused,
  other fields keep their values and types)
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

### Changed

- **Fewer requests in the background**, so editor features stay within secret managers' rate limits (a 1Password service
  account's hourly limit, Doppler's) even with several IDEs open. Completion, hints and tooltips now read:
  - **Vault/OpenBao**: only the bound secret path (*This Project › Folder*) and the secrets below it, a couple of requests,
    instead of walking the whole mount (up to ~500 requests every 10 minutes)
  - **1Password**: only the bound item, one `op item get` or Connect request, instead of every item of the vault (up to 101 calls).
    Bound to a whole vault, and for `op://vault/**` imports, only items whose version changed since they were last read are read
    again
  - **Doppler**: root configs and the bound config, no longer every branch and personal config (nor the bound config twice)
- The run configuration variable that picks the environment is `ENVBRIDGE_ENVIRONMENT` (for every secret manager);
  `INFISICAL_ENVIRONMENT` keeps working
- Intention and inspection descriptions no longer assume Infisical
- Actions and messages shared by every secret manager no longer say "Infisical": *Run with Secrets Environment…*, *Add Secrets
  to Run Configuration…*, *New Terminal Tab with Secrets*, *Load Secrets into This Terminal*, *Bind to Secret…*, *Extract Value
  to Secret Manager…*
- New plugin icon: a suspension bridge carrying a key (the Secrets tool window keeps its key icon)

[Unreleased]: https://github.com/xprees/idea-infisical-plugin/compare/2026.3.19...HEAD
[2026.3.19]: https://github.com/xprees/idea-infisical-plugin/commits/2026.3.19
