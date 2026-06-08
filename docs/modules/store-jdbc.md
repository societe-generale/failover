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
    jdbc:
      table-prefix: MYAPP_     # → table MYAPP_FAILOVER_STORE
```

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

---

## Custom Queries

Override SQL statements with a `FailoverStoreQueryResolver` bean — useful for schema-qualified table names or custom hints. See [Failover Store Query Resolver](../how-to/failover-store-query-resolver.md).

---

## Next Steps

- [Async Store](store-async.md) — make JDBC writes non-blocking
- [Multi-Tenant Store](store-multitenant.md) — per-tenant table/schema routing
- [Store Types](../configuration/store-types.md) — choose the right store
