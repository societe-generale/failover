---
icon: material/shield-lock
---

# Security Policy

The canonical policy lives in [`SECURITY.md`](https://github.com/societe-generale/failover/blob/main/SECURITY.md)
at the repository root (where GitHub surfaces the **Report a vulnerability** action). This page mirrors it
for the documentation site.

---

## Supported Versions

| Version | Supported |
|---------|-----------|
| 3.x     | ✅        |
| 2.x     | ✅ (critical fixes only) |
| < 2.0   | ❌        |

---

## Reporting a Vulnerability

Please **do not open a public GitHub issue** for security vulnerabilities.

Report privately via
[GitHub Security Advisories](https://github.com/societe-generale/failover/security/advisories/new)
("Report a vulnerability" on the repository's Security tab).

Include:

- A description of the issue and its impact
- Steps or a proof of concept to reproduce it
- Affected version(s) and configuration (store type, multi-tenant mode, etc.)

We acknowledge reports within 5 business days and keep you informed of progress. Once a fix is
released, we credit reporters who wish to be named in the advisory.

---

## Security Notes for Operators

### Payload deserialization allowlist

The JDBC store deserializes payloads using the class name stored in the `PAYLOAD_CLASS` column.
Loading is restricted by an allowlist that is **secure by default**: the framework auto-allows the
packages of every discovered `@Failover` payload type (return types and collection/array element
types), so only your own referential classes are ever materialized.

```yaml
failover:
  store:
    # Additive override — only for payload classes the scanner cannot infer
    # (e.g. a scatter slice type in a different package than its composite).
    allowed-payload-classes:
      - com.acme.referential          # package prefix
      - com.acme.special.Currency     # exact class
```

The restriction is disabled (allow-all) only when no payload types are discovered **and** the
property is empty.

### SQL identifier validation

`failover.store.jdbc.table-prefix` (and per-tenant prefixes) are validated at startup to contain only
letters, digits, underscores, and dot-separated qualifiers (e.g. `SCHEMA.PREFIX_`); they are
concatenated into SQL identifiers.

### Multi-tenant isolation

In `TABLE_PREFIX` mode an unconfigured tenant would otherwise resolve to the shared global table.
Enable strict mode to fail fast instead:

```yaml
failover:
  store:
    multitenant:
      strict: true   # reject tenants absent from the configured tenants map
```

### JVM-fatal errors

`Error` (e.g. `OutOfMemoryError`, `StackOverflowError`) propagates unwrapped through the failover
aspect — the recovery path never runs on a failing JVM, so a dying process fails fast rather than
serving stale data.
