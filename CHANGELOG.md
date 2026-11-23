# Changelog

## [Unreleased]

## [2026.3.25] - 2026-11-23

### Added

- **AWS IAM Identity Center: choose the account and role.** When an SSO connection names no account ID or role name and you can
  use several, *Sign In* shows a searchable list of your accounts (name, ID and email, all pages of them) and then the roles of the
  one you chose, and remembers the choice in the connection's settings. A **Choose…** button next to the account ID in the
  connection's settings lists them too (reusing the stored sign-in, or signing in in the browser) and fills the fields; use it
  to switch later. Changing only the account or role keeps the sign-in. Tokens and role credentials stay in the IDE's password
  storage and memory, never in settings
- **Decorators in `.env` files.** Comments in the env-spec format of varlock's `.env.schema` work in every `.env` file a launch reads: `# @required` stops a launch whose value is empty or unset and names the variables (also the header's `@defaultRequired=true` / `infer`; `@optional` overrides), `# @sensitive` masks the value in the console like a secret, `# @internal` keeps a variable out of the process, and `# @type=port` (also `string(startsWith=…)`, `enum(…)`, `url`, `number`, `boolean`, `email`, `uuid`) reports values that don't fit when the launch starts. Projects whose `.env` files use none of these launch exactly as before; *Apply env-spec decorators of .env files at launch* in the settings turns it off. In the editor, decorator names are completed after `# @` and a new inspection reports malformed, unknown or misspelled decorators (with a fix), `@required` together with `@optional`, invalid types and plain values that don't match their type

- **Layered `.env` files** (setting, off by default): launches read `.env.schema`, `.env`, `.env.local`, `.env.[environment]` and `.env.[environment].local`, later ones winning, where the environment is the one the launch uses (status bar, `ENVBRIDGE_ENVIRONMENT`, *Run with environment…*)
- **varlock compatibility mode** (setting, off by default): launches resolve varlock's function values in `.env.schema` and the
  layered `.env` files themselves, through your signed-in EnvBridge connections and without Node.js or the varlock CLI:
  `infisical()`, `vaultSecret()` (also `raw=true`), `op()`, `doppler()`, `awsSecret()`, `ref()`, `${VAR}` interpolation (with
  `:-default`), `concat()`, `fallback()`, `forEnv()`, `eq`/`if`/`ifs`/`not`/`and`/`or`/`isEmpty`. `@initInfisical`,
  `@initHcpVault`, `@initDoppler` and `@initAws` give project, environment, paths and prefixes and pick the connection by URL or
  region (credentials in them are never read, inline ones are warned about); an instance ID maps to the connection of that name.
  `@currentEnv` picks the environment (after *Run with environment…*), `@import(…)` brings in other files of the project.
  `exec()`, `$(…)`, `keychain()`, `varlock()` and `generateOtp()` are refused and unknown functions unsupported: the launch stops
  before anything is fetched, naming the variable and the function and offering to open the file. Resolved values are masked
  like other secrets, and `@required` / `@sensitive` / `@internal` / `@type` apply to them. In the editor, hints, hover and the
  missing-secret inspection understand the functions, *Extract Value to Secret Manager…* writes `KEY=infisical()`, and renaming or
  moving a secret rewrites the functions that read it
- **varlock editor support** (with *varlock compatibility mode* on, in `.env.schema` files, the `.env` files next to one and `.env`
  files using decorators): highlighting of decorators, functions, `$KEY` references and types that works on top of a DotEnv plugin
  or plain text (colours under *Editor › Color Scheme › varlock (EnvBridge)*); completion of header and variable decorators by
  position, `@type` names and options, decorator values, functions (core and the 1Password, Infisical and Vault plugins'), enum
  values after `KEY=` (also from `.env.schema` when editing `.env`) and variable names of all layered files after `$` and in
  `ref(…)`; a *varlock rules in .env files* inspection for deprecated decorators, unknown functions, plugin functions without their
  plugin, references to undefined variables, `@currentEnv` pointing at a missing variable, `@import` of missing files and values
  that don't match a `@type` from another file; documentation on hover for decorators and functions; Ctrl+click from `$KEY` /
  `ref(KEY)` to the variable and from `@import(…)` to the file; Enter continues `#` comments. Nothing in these files is ever run

### Fixed

- Secrets tool window: the tooltip of a secret or folder whose details are read on demand stayed on "Loading details…" until
  the mouse moved; it now shows the details as soon as they arrive while the mouse is still over that row
- Completion inside `op://` and `vault://` references never offered anything: `op://` now completes the vault, then the item,
  then its fields (also those in sections); `vault://` the mount, path segments and, after `#`, the secret's fields, also in the
  short forms `vault://#KEY` and `vault://./db#KEY` of an environment layout. Names and keys only, never values
- Hover, inlay hints and the missing-secret inspection ignored `op://` references that name the vault by its name (only the
  vault ID that *Copy reference* writes matched): the vault now matches by ID or name (a name in other letter case too, when no
  other vault has it), the item and section in any letter case, and a field written without its section is found in the item
- 1Password field labels with spaces (`op://Private/Stripe/api key`) were cut at the space in the editor: they are now recognized
  when the reference is quoted or is the whole value of a `KEY=` line (a trailing ` # comment` is left out)

[Unreleased]: https://github.com/xprees/idea-infisical-plugin/compare/2026.3.25...HEAD
[2026.3.25]: https://github.com/xprees/idea-infisical-plugin/commits/2026.3.25
