# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 3.x     | ✅        |
| 2.x     | ✅ (critical fixes only) |
| < 2.0   | ❌        |

## Reporting a Vulnerability

Please **do not open a public GitHub issue** for security vulnerabilities.

Report vulnerabilities privately via
[GitHub Security Advisories](https://github.com/societe-generale/failover/security/advisories/new)
("Report a vulnerability" on the repository's Security tab).

Include:

- A description of the issue and its impact
- Steps or a proof of concept to reproduce it
- Affected version(s) and configuration (store type, multi-tenant mode, etc.)

We will acknowledge the report within 5 business days and keep you informed of progress.
Once a fix is released, we will credit reporters who wish to be named in the advisory.

## Security Notes for Operators

- The JDBC store deserializes payloads using the class name stored in the `PAYLOAD_CLASS`
  column. Loading is restricted by an allowlist that is **secure by default**: the framework
  auto-allows the packages of every discovered `@Failover` payload type, so only your own
  referential classes are ever materialized. Add `failover.store.allowed-payload-classes`
  (exact class names or package prefixes) only for payload classes the scanner cannot infer
  (e.g. a scatter slice type in a different package than its composite). The restriction is
  disabled (allow-all) only when no payload types are discovered and the property is empty.
- `failover.store.jdbc.table-prefix` (and per-tenant prefixes) are validated to contain only
  letters, digits, underscores, and dot-separated qualifiers; they are concatenated into SQL
  identifiers.
- In multi-tenant `TABLE_PREFIX` mode, set `failover.store.multitenant.strict=true` to reject
  tenants that are not in the configured tenants map, preventing an unconfigured tenant from
  silently sharing the global store table.
