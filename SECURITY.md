# Security policy

This plugin handles credentials and secret values, so security reports are very welcome.

## Supported versions

Only the latest release on the JetBrains Marketplace gets security fixes. Update the plugin
(*Settings › Plugins › Installed › Secrets for Infisical › Update*) before you report a problem.

## Reporting a vulnerability

**Don't open a public issue for a security problem.** Report it privately through GitHub:

1. Open the repository's [Security tab](https://github.com/xprees/idea-infisical-plugin/security).
2. Choose **Report a vulnerability** and fill in the form.

Include what you can of the plugin version, the IDE (name, version, operating system), what an attacker can do and under which
conditions, and steps that reproduce it. **Never include real tokens or secret values** in a report.

You can expect an acknowledgement within a week. Once confirmed, a fix is released as soon as possible and the advisory published
with the release. You are credited unless you prefer not to be.

Vulnerabilities in Infisical itself belong to [Infisical](https://infisical.com/docs/internals/security), those in the IntelliJ
Platform to [JetBrains](https://www.jetbrains.com/privacy-security/issues-fixed/).

## Scope

In scope, for example:

- credentials (session or refresh tokens, client secrets) stored anywhere other than the IDE's password storage, or sent to a host
  other than the connection's instance;
- secret values written to disk (settings, `workspace.xml`, `.run` files, caches, logs) or kept after sign-out;
- forging or hijacking a sign-in: the loopback callback accepting a request that doesn't come from the instance, login CSRF, DNS
  rebinding, OAuth `state`/PKCE weaknesses;
- a repository (for example its `.infisical.yaml` or run configurations) making the plugin talk to another server or read secrets
  without the user's action;
- secrets shown or copied where the user didn't ask for them.

The design and the threat model are in [docs/DESIGN.md](docs/DESIGN.md#threat-model-summary).
