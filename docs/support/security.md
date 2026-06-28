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
    jdbc:
      # Additive override — only for payload classes the scanner cannot infer
      # (e.g. a scatter slice type in a different package than its composite).
      allowed-payload-classes:
        - com.acme.referential          # package prefix
        - com.acme.special.Currency     # exact class
      strict-allowlist: true            # recommended for production — see below
```

The restriction is disabled (allow-all) only when no payload types are discovered **and** the
property is empty.

!!! danger "Fail-open vs. fail-closed (`strict-allowlist`)"
    By default, an empty resolved allowlist **disables** the restriction (allow-all / fail-open) and
    logs a `WARN`. This preserves backward compatibility but means a misconfiguration — no `@Failover`
    types discovered and no configured entries — silently re-opens the deserialization-gadget surface.

    Set `failover.store.jdbc.strict-allowlist: true` to **fail closed**: an empty allowlist then
    **denies all** deserialization (logged at `ERROR`) rather than loading arbitrary classes named in
    store data. The normal secure-by-default path is unaffected — scanner-derived and configured
    entries are honoured exactly as before. **Recommended for production.**

**Derivation algorithm (and its limit).** The allowlist is built from the **packages** of the payload
types the scanner finds on `@Failover` methods (return type + collection/array element type), minus
JDK packages (`java.*`, `javax.*`, `jakarta.*`, which are never whitelisted). It is
**package-granular, not a deep type graph**: a payload whose *nested* field types live in a
**different** package is not auto-allowed and must be added via `failover.store.jdbc.allowed-payload-classes`. If recovery
throws a `FailoverStoreException` naming a class, add that class (or its package) to the property.
Keep entries as narrow as possible — a package prefix widens the deserialization surface.

### SQL identifier validation

`failover.store.jdbc.table-prefix` **and the per-tenant prefixes** (from
`failover.store.multitenant.tenants.<id>.table-prefix`) are validated against
`([A-Za-z0-9_]+\.)*[A-Za-z0-9_]*` when the store/query resolver is built — any value with spaces,
quotes, `;`, `--`, or other non-identifier characters is rejected with an `IllegalArgumentException`
before it reaches SQL. The tenant **identifier** itself is never concatenated into a table name; only
its operator-configured prefix is. So a hostile tenant id cannot inject SQL via the table name.

### Multi-tenant isolation

In `TABLE_PREFIX` mode an unconfigured tenant would otherwise resolve to the shared global table.
Enable strict mode to fail fast instead:

```yaml
failover:
  store:
    multitenant:
      strict: true   # reject tenants absent from the configured tenants map
```

### Sensitive data (PII) in failover stores

!!! warning "The failover store is a copy of upstream responses"
    The framework persists whatever the protected method returns, for the configured TTL. If a
    referential response contains **PII** (names, account numbers, addresses), that data is now copied
    into the failover store — a **secondary data repository** that may have different access controls,
    audit logging, encryption-at-rest, and retention than the system of record. The JDBC store writes
    it to a database; the in-memory/Caffeine stores hold it in process memory.

By default the library stores exactly what it recovers (no transformation). To handle sensitive
referentials, layer these controls:

- **Encrypt the JDBC payload at rest (built-in).** Enable the built-in **AES-GCM** cipher so the
  `PAYLOAD` column holds ciphertext, not readable JSON — no custom code, just a key:

  ```yaml
  failover:
    store:
      jdbc:
        encryption:
          enabled: true
          cipher: aesgcm
          key: ${FAILOVER_STORE_JDBC_ENCRYPTION_KEY}   # Base64 16/24/32 bytes, from a secret store
  ```

  See [Payload Encryption → AES-GCM](../how-to/payload-encryption.md#real-encryption-out-of-the-box-aes-gcm-recommended).
  Note this protects the JDBC store at rest only; in-memory/Caffeine payloads stay in process memory.
- **Mask fields at the boundary.** Use a `PayloadEnricher` to mask, tokenise, or drop sensitive fields
  on store and restore them on recover — applies to every store type and lets you keep non-sensitive
  fields readable. See
  [Custom Payload Enricher → encode/decode example](../how-to/custom-payload-enricher.md#example-encode-on-store-decode-on-recover).
- **Constrain the TTL.** Keep `expiryDuration` as short as the use case allows so PII does not linger;
  ensure expiry cleanup actually runs (`failover.scheduler`).
- **Protect the JDBC store** with the same access control and retention policy as any other PII datastore.
- **Prefer not failover-protecting** highly sensitive methods at all if a stale copy is unacceptable.

### JVM-fatal errors

`Error` (e.g. `OutOfMemoryError`, `StackOverflowError`) propagates unwrapped through the failover
aspect — the recovery path never runs on a failing JVM, so a dying process fails fast rather than
serving stale data.
