---
icon: material/database-outline
---

# JDBC Store

Durable, shared-state failover store backed by any JDBC-compatible database. Recommended for all production deployments.

---

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-jdbc</artifactId>
    <version>3.0.0</version>
</dependency>
```

Requires a `DataSource` bean in the Spring context. Spring Boot's auto-configured `DataSource` is used automatically.

---

## Configuration

```yaml title="application.yml"
failover:
  store:
    type: jdbc
    allowed-payload-classes: []   # deserialization allowlist (additive; see Serialisation)
    jdbc:
      table-prefix: MYAPP_        # → table MYAPP_FAILOVER_STORE
```

`table-prefix` is validated at startup to contain only letters, digits, underscores, and
dot-separated qualifiers (e.g. `SCHEMA.MYAPP_`); an invalid value fails fast with an
`IllegalArgumentException` rather than producing a SQL grammar error later.

---

## Table DDL

```sql title="create_table.sql"
CREATE TABLE MYAPP_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)                      NOT NULL,
    FAILOVER_KEY   VARCHAR(256)                     NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE      NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE      NOT NULL,
    PAYLOAD        VARCHAR(4000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
);

-- Required: the cleanup scheduler runs `DELETE ... WHERE EXPIRE_ON < ?`; without this index
-- every cleanup pass is a full table scan that worsens as the table grows.
CREATE INDEX IDX_MYAPP_FAILOVER_STORE_EXPIRE_ON ON MYAPP_FAILOVER_STORE (EXPIRE_ON);
```

Adjust `PAYLOAD` size to your largest serialised payload. Use `CLOB` / `TEXT` for payloads exceeding `VARCHAR` limits.

!!! warning "Mandatory `EXPIRE_ON` index"
    The expiry-cleanup scheduler (`failover.scheduler.cleanup-cron`) deletes by `EXPIRE_ON < ?`. The
    `EXPIRE_ON` index keeps that delete an index range scan instead of a full table scan — create it on
    every dialect. Name the index per your naming convention; only the indexed column matters.

---

## Supported Databases

| Database | Upsert strategy |
|---|---|
| H2 | `MERGE INTO ... KEY (...)` |
| PostgreSQL | `INSERT ... ON CONFLICT DO UPDATE SET ...` |
| MySQL / MariaDB | `INSERT ... ON DUPLICATE KEY UPDATE ...` |
| Oracle | `MERGE INTO ... USING DUAL ON ...` |
| SQL Server | `MERGE INTO ... USING (VALUES ...) AS src ON ...` |

Dialect is detected automatically at startup via `DatabaseResolver`. See [Database Resolver](../how-to/database-resolver.md) for overrides.

---

## Write Semantics

A store first attempts the database's native merge/upsert (table above) — a single atomic statement.
If the dialect is unknown (or the merge SQL fails once with a `BadSqlGrammarException`), the store
falls back permanently to an **INSERT → UPDATE-on-duplicate** pattern.

On the fallback path a concurrent expiry delete can remove the row between the failed INSERT and the
follow-up UPDATE (so the UPDATE affects 0 rows). The store applies a **single bounded retry**: the
row is now absent, so it re-INSERTs once and succeeds. If every attempt loses the race the write is
abandoned and logged at `warn` — the value is a regenerable cache and is re-stored on the next
successful upstream call. Native-merge dialects avoid this window entirely. See ADR 47.

---

## Serialisation

Payloads are serialised to JSON using Jackson's `ObjectMapper`. The class name is stored in `PAYLOAD_CLASS` for deserialisation. Override with a custom `PayloadColumnResolver` bean — see [Payload Column Resolver](../how-to/payload-column-resolver.md).

### Deserialization Allowlist

On recovery the store reconstructs the payload type from the `PAYLOAD_CLASS` value via
`Class.forName`. To prevent a poisoned class name (shared/compromised schema, SQL injection
elsewhere) from instantiating arbitrary classes, loading is restricted to an allowlist that is
**secure by default**:

- Auto-derived from the packages of every discovered `@Failover` payload type (return types and
  collection/array element types) — no configuration needed.
- Extend with `failover.store.allowed-payload-classes` (exact class names or package prefixes) only
  for classes the scanner cannot infer, e.g. a scatter slice type in a different package than its
  composite.

A class name outside the allowlist is rejected with `FailoverStoreException`. The restriction is
disabled (allow-all) only when no payload types are discovered and the property is empty. See
[Security](../support/security.md).

---

## Custom Queries

Override SQL statements with a `FailoverStoreQueryResolver` bean — useful for schema-qualified table names or custom hints. See [Failover Store Query Resolver](../how-to/failover-store-query-resolver.md).

---

## Next Steps

- [Async Store](store-async.md) — make JDBC writes non-blocking
- [Multi-Tenant Store](store-multitenant.md) — per-tenant table/schema routing
- [Store Types](../configuration/store-types.md) — choose the right store
