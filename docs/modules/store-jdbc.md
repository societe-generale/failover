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
```

Adjust `PAYLOAD` size to your largest serialised payload. Use `CLOB` / `TEXT` for payloads exceeding `VARCHAR` limits.

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
