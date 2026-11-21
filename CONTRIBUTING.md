# Contributing to EnvBridge

Thanks for helping. Bug reports, fixes, new features, tests and documentation are all welcome. This guide covers how to get the
project running, how the code is organized, and what a pull request needs before it can be merged.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report security problems privately, as described
in [SECURITY.md](SECURITY.md), never in a public issue, and **never paste real tokens or secret values** into issues, logs or tests.

## Before you start

- **Small fixes** (a typo, an obvious bug with a test) can go straight to a pull request.
- **Bigger changes** (a new run configuration type, a new provider, a new setting): open an issue first and describe the problem
  and the approach. The plan and roadmap are in [docs/DESIGN.md](docs/DESIGN.md).
- **The Infisical API docs and CLI are the reference.** Behaviour follows the [Infisical API reference](https://infisical.com/docs/api-reference)
  and what the Infisical CLI does (`.infisical.yaml`, environment precedence, import merging). Link the part you followed.
- **No Infisical code or brand assets.** Reimplement behaviour from the documentation; don't copy code from Infisical's
  repositories, and don't add Infisical logos.

## Setting up

You need **JDK 21** and **IntelliJ IDEA** with the *Plugin DevKit* plugin.

```shell
git clone https://github.com/xprees/idea-infisical-plugin.git
cd idea-infisical-plugin
./gradlew build
```

Open the directory in IntelliJ IDEA as a Gradle project. The shared run configurations in `.run/` (*Run Plugin*, *Run Tests*,
*Run Verifications*) appear in the toolbar. The first build downloads the IntelliJ Platform (about 1.5 GB) into the Gradle cache.

## Project layout

```
src/main/kotlin/cz/xprees/secrets/
  core/            provider-agnostic code
    model/         connections, projects, environments, folders, secrets (SecretValue masks itself in toString)
    provider/      the SecretProvider extension point and SecretsClient interface
    auth/          CredentialVault (PasswordSafe), TokenSet, JWT claim reader
    reference/     the `<scheme>://` reference syntax
    resolve/       EnvResolution (pure launch logic), bindings, the memory-only cache, the launch resolver
    settings/      application settings (connections) and the project binding
  infisical/       the Infisical provider: REST client, sign-in flows, .infisical.yaml
  run/             SecretsProgramRunner (clone and delegate) and the EnvPatcher extension point; maven/ is an optional dependency
  ui/              tool window, settings, sign-in helper
src/main/resources/
  META-INF/plugin.xml               registrations; secrets-*.xml for optional dependencies
  messages/SecretsBundle.properties every user-visible string
src/test/kotlin/   unit tests and platform tests (MockInfisical is a fake instance)
```

### Adding a run configuration type

Implement `EnvPatcher` (read the configuration's variables; return a **copy** with new ones, never modify the original) and
register it in a `secrets-<plugin>.xml` loaded through an optional `<depends>`. Add a platform test proving that the original
configuration is unchanged after `copyWith`.

### Adding a provider

Implement `SecretProvider` and `SecretsClient` and register the provider. References, injection, the tool window, caching and
credential storage work for it without changes.

## Building and running

```shell
./gradlew runIde          # a sandbox IDE with the plugin (the oldest supported IDE)
./gradlew runIdeLatest    # the same in the newest IDE the verifier checks
./gradlew test            # all tests (without the integration tests)
./gradlew integrationTest # against real servers, after scripts/integration/start.sh
./gradlew buildPlugin     # build/distributions/secrets-for-infisical-<version>.zip
./gradlew verifyPlugin    # JetBrains Plugin Verifier against the IDEs in gradle.properties
```

## Tests

Every change in behaviour needs a test that fails without it.

- Pure logic (`core/model`, `core/reference`, `core/resolve/EnvResolution`, API parsing, project files) is tested with plain JUnit.
- Code that needs the platform extends `BasePlatformTestCase`. Network code is unit-tested against fakes (`MockInfisical`,
  `MockVault`, `MockDoppler`, `MockAwsSecretsManager`), never against a real instance.
- **Integration tests** (`src/test/kotlin/.../integration/`, classes ending in `IntegrationTest`) run the providers and the launch
  path against real servers in Docker: HashiCorp Vault, OpenBao, a self-hosted Infisical and LocalStack (AWS Secrets Manager),
  seeded with throwaway test data.
  Doppler can't be self-hosted, so it has mock-based tests only.
  `./gradlew test` leaves them out; CI runs them in the *Integration tests* job. Locally:

  ```shell
  scripts/integration/start.sh     # starts and seeds the servers (Docker), writes build/integration.env
  ./gradlew integrationTest
  scripts/integration/stop.sh
  ```

  A behaviour a fake can't prove (an endpoint's real shape, an error a real server sends) belongs in an integration test too.
- **Hosted secret managers** (Doppler, 1Password) can't run in Docker. Their integration tests (`DopplerIntegrationTest`,
  `OnePasswordIntegrationTest`) run against a real test account when its credentials are set, and skip otherwise. Use a
  dedicated test project / vault: the tests create and delete secrets there (and never touch anything else).

  | Variable (environment, or a repository secret / variable in CI) | Meaning |
  |---|---|
  | `ENVBRIDGE_IT_DOPPLER_TOKEN` (secret) | Doppler personal or service account token with read and write access to the test project |
  | `ENVBRIDGE_IT_DOPPLER_PROJECT`, `ENVBRIDGE_IT_DOPPLER_CONFIG` (variables) | Test project and config (default `envbridge-it` / `dev`) |
  | `ENVBRIDGE_IT_OP_SERVICE_ACCOUNT_TOKEN` (secret) | 1Password service account token with read and write access to the test vault (CI then installs the `op` CLI) |
  | `ENVBRIDGE_IT_OP_VAULT` (variable) | Test vault name (default `EnvBridge IT`); `ENVBRIDGE_IT_OP_PATH` points to `op` if it isn't on PATH |

  CI passes these to the *Integration tests* job only; pull requests from forks get no secrets, so those tests skip there.
- Security behaviour (callback hardening, URL checks, masking, run configuration isolation) has tests. Keep them.
- **Assert exact results**, and **don't skip, disable or loosen a failing test.** Find the cause.

## Coding guidelines

The code follows the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html), set up in `.editorconfig`.

- **Write KDoc for every non-private declaration.**
- **Secrets stay secret**: never log secret values or tokens, never put them in exception messages, settings or files; keep
  them in `SecretValue` or `TokenSet` (both mask `toString`). Credentials go through `CredentialVault` only.
- **Network and credential store access off the EDT.** Use background tasks or pooled threads.
- **User-visible strings go in `SecretsBundle.properties`**, in sentence case, following the
  [IntelliJ Platform UI guidelines](https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html).
- **No dead code or commented-out code.**

## IntelliJ Platform rules

The plugin supports every IDE from `platformVersion` (2026.1) on, with no upper build limit, and installs without a restart.

- **Use only public, stable APIs.** The Plugin Verifier fails the build on internal API use.
- **Compile against the oldest supported platform.**
- **Stay a dynamic plugin**: register everything in `plugin.xml`, use light services, give resources a parent `Disposable`.
- **Optional dependencies for other plugins' APIs**, so the plugin loads in every IDE.

CI verifies only the newest version of each IDE by default. Add the `full-verify` label to a pull request to verify the whole
matrix.

## Commits, changelog and pull requests

- Commit subjects in the imperative and sentence case, without a trailing period, under about 72 characters.
- Every user-visible change gets an entry under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) (*Added*, *Changed*, *Fixed*,
  *Removed*), written for users.
- Pull requests: branch from `master`, include tests and a changelog entry, run `./gradlew test` (and `verifyPlugin` when you touch
  platform APIs), fill in the template. CI must be green.
- AI-assisted contributions are welcome under the same rules: you are responsible for the code, have read it, and it has tests.

## Releases (maintainers)

Every merge to `master` that passes CI is tagged with the next version and gets a draft GitHub release with the built plugin.
Publishing the draft runs the *Release* workflow, which signs and publishes to the JetBrains Marketplace and opens a
`Changelog update - <version>` pull request; merge it before the next release.

## Licensing

The project is licensed under [Apache-2.0](LICENSE). By contributing, you agree that your contribution is licensed under the same
terms (Apache-2.0, section 5). Only contribute code you have the right to submit; third-party code needs a compatible license and
an entry in [NOTICE](NOTICE).
