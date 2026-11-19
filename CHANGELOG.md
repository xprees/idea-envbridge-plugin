# Changelog

## [Unreleased]

### Added

- The plugin is called **EnvBridge** (an unofficial Infisical integration); existing settings and sign-ins are kept

- Connections to Infisical Cloud (US, EU), dedicated and self-hosted instances, with three sign-in methods: browser sign-in (like
  the Infisical CLI), OAuth application (read-only, PKCE) and machine identity (Universal Auth). Credentials are stored only in
  the IDE's password storage
- Secret references in the environment variables of run configurations: `KEY=infisical://KEY`, `infisical://prod/backend/KEY`,
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
- Secrets tool window: browse projects, environments, folders and secrets with masked values, reveal with auto-hide, copy with
  clipboard auto-clear, copy reference, open in dashboard, run configuration preview
