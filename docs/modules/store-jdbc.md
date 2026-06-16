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

## Connection Pool Tuning

The JDBC store borrows a connection per operation from your application's `DataSource` pool
(HikariCP by default). Size the pool for the store's actual concurrency, not just business queries.

**How failover uses the pool**

- **Scatter/gather (parallel)** — a composite of *N* entities issues up to *N* concurrent slice
  writes/reads, each borrowing a connection at once. Peak demand ≈ `N × (concurrent failover calls)`.
  See [Scatter / Gather](../concepts/scatter-gather.md).
- **Async writes** (`failover.store.async=true`, default) — `store` / `delete` / `cleanByExpiry` run on
  the `failover-async` virtual-thread executor. Virtual threads are cheap and unbounded, so the pool —
  not the executor — is the real limit on concurrent writes.
- **Recover (`find`)** — synchronous on the business thread, and only during an upstream failure.
- **Cleanup scheduler** — one connection, periodically; short when the [`EXPIRE_ON` index](#table-ddl) exists.

**Recommendations**

```yaml title="application.yml"
spring:
  datasource:
    hikari:
      maximum-pool-size: 20          # ≥ peak concurrent slices; raise for wide scatter/gather composites
      connection-timeout: 8000       # ms — keep ≤ failover.scatter.timeout so pool waits fail within the slice budget
      leak-detection-threshold: 15000 # surface a slice that never returns a connection
```

- **Match the slice timeout.** Keep `connection-timeout` **at or below** `failover.scatter.timeout`
  (default `10s`). Otherwise a pool-exhaustion wait outlives the slice timeout: the slice times out
  (recover → not-recovered; store → surfaced) while a connection request is still blocked.
- **Size for the widest composite.** If a splitter fans out to *N* slices, a single call can borrow *N*
  connections simultaneously. Either raise `maximum-pool-size` to cover the peak, or accept that excess
  slices queue on the pool and may hit the slice timeout under load.
- **Isolate failover load if it competes with business queries.** Failover writes are best-effort cache
  updates — a separate `DataSource`/pool (or a dedicated `JdbcTemplate` via a custom store bean) stops a
  burst of slice writes from starving primary application traffic.
- **Keep cleanup cheap.** The hourly cleanup `DELETE … WHERE EXPIRE_ON < ?` holds a connection for the
  duration of the scan — the mandatory `EXPIRE_ON` index keeps it an index range scan, not a long full
  scan that pins a connection.

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
