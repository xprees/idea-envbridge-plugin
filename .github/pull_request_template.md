## What and why

<!-- What does this change, and why? Link the issue it fixes: "Fixes #123". -->

## How it was tested

<!-- New or changed tests, and anything checked by hand in `./gradlew runIde` (which run configuration types, which sign-in
method and instance). For API behaviour: which part of the Infisical API reference or CLI behaviour you followed.
Never paste real tokens or secret values. -->

## Checklist

- [ ] Tests cover the change, and `./gradlew test` passes
- [ ] `./gradlew verifyPlugin` passes, if platform APIs were added or changed
- [ ] User-visible changes have an entry under `## [Unreleased]` in `CHANGELOG.md`
- [ ] User-visible strings are in `SecretsBundle.properties`
- [ ] No secret values or tokens can reach logs, settings, files or exception messages
- [ ] Third-party code or assets (if any) are listed in `NOTICE`
