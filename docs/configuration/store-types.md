---
icon: material/database
---

# Store Types

Four backing stores are available. Choose based on your deployment topology and persistence requirements.

---

## Comparison

| Store | Persistence | Shared across nodes | Production-ready | Dependency |
|---|---|---|---|---|
| InMemory | None | ❌ | ❌ | None |
| Caffeine | None | ❌ | Single-node | `caffeine` |
| JDBC | Durable | ✅ | ✅ | Any JDBC `DataSource` |
| Custom | Varies | Varies | Varies | Your implementation |

---

## InMemory

In-process map store. Zero dependencies. Data is lost on restart. Not suitable for production.

```yaml title="application.yml"
failover:
  store:
    type: inmemory      # default — no extra config needed
    inmemory:
      max-entries: 10000  # default — LRU eviction past this cap; 0 = unbounded
```

The store is **size-capped by default** (`max-entries: 10000`) and evicts the least-recently-accessed
entry once the cap is exceeded, so high-cardinality keys cannot grow the heap without bound. Set
`max-entries: 0` for the legacy unbounded behaviour.

!!! warning "Not for production"
    InMemory stores data only for the lifetime of the JVM process. Any restart loses all cached failover data, leaving the first few requests unprotected until new upstream calls succeed.

---

## Caffeine

In-process store backed by the Caffeine cache library. Suitable for single-node deployments where persistence is not required.

```yaml title="application.yml"
failover:
  store:
    type: caffeine
    caffeine:
      max-size: 10000  # default — same cap as inmemory.max-entries; set 0 for unbounded
```

```xml title="pom.xml"
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-caffeine</artifactId>
    <version>3.0.0</version>
</dependency>
```

Caffeine handles its own eviction using the `expireOn` field from `ReferentialPayload`. Entries are evicted at their configured TTL without needing the cleanup scheduler.

By default the cache is capped at `max-size: 10000` entries (the same default as the in-memory store's
`max-entries`) — Caffeine evicts by its size-based (Window TinyLFU) policy once the cap is reached. The
default comfortably holds typical referential datasets while bounding heap; set `max-size: 0` for an
unbounded cache limited only by per-entry expiry.

---

## JDBC {#jdbc}

Durable, shared-state store backed by any JDBC-compatible database. The recommended production store.

```yaml title="application.yml"
failover:
  store:
    type: jdbc
    jdbc:
      table-prefix: MYAPP_
```

```xml title="pom.xml"
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-jdbc</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Create the Table

```sql title="create_failover_store.sql"
CREATE TABLE MYAPP_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)                      NOT NULL,
    FAILOVER_KEY   VARCHAR(256)                     NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE      NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE      NOT NULL,
    PAYLOAD        VARCHAR(4000),   -- size to your largest serialised payload
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
);

-- Required: keeps the expiry-cleanup DELETE (`WHERE EXPIRE_ON < ?`) an index scan, not a full scan.
CREATE INDEX IDX_MYAPP_FAILOVER_STORE_EXPIRE_ON ON MYAPP_FAILOVER_STORE (EXPIRE_ON);
```

The `PAYLOAD` column stores JSON. Adjust its size to accommodate your largest serialised payload. For very large payloads, use `CLOB` / `TEXT` instead of `VARCHAR`.

### Supported Databases

| Database | Upsert dialect |
|---|---|
| H2 | `MERGE INTO` |
| PostgreSQL | `INSERT ... ON CONFLICT DO UPDATE` |
| MySQL / MariaDB | `INSERT ... ON DUPLICATE KEY UPDATE` |
| Oracle | `MERGE INTO ... USING DUAL` |
| SQL Server | `MERGE INTO ... USING (VALUES ...) AS src` |

Dialect detection is automatic via `DatabaseResolver`. See [Database Resolver How-to](../how-to/database-resolver.md) for custom configurations.

!!! tip "Async writes reduce latency"
    With `failover.store.async=true` (default), write operations run on a virtual-thread executor so they never block the request thread.

---

## Custom

Implement `FailoverStore<T>` and register it as a Spring `@Bean`. Auto-configuration detects it via `@ConditionalOnMissingBean`:

```java title="RedisFailoverStore.java"
@Component
public class RedisFailoverStore<T> implements FailoverStore<T> {

    @Override
    public void store(ReferentialPayload<T> payload) {
        // write to Redis
    }

    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        // read from Redis — must return a defensive copy
        return Optional.ofNullable(/* ... */);
    }

    @Override
    public void delete(ReferentialPayload<T> payload) {
        // delete from Redis
    }

    @Override
    public void cleanByExpiry(Instant expiry) {
        // remove all entries where expireOn < expiry
    }
}
```

!!! warning "Defensive copy in `find()`"
    `find()` must return a copy of the stored entry, not a live reference. Callers mutate `upToDate` and `asOf` on the returned object. See [ADR 10](../adr/adr.md) for the rationale.

---

## Next Steps

- [Multi-Tenant](multi-tenant.md) — per-tenant table or schema routing
- [Async Store](../modules/store-async.md) — how non-blocking writes work
- [Payload Column Resolver](../how-to/payload-column-resolver.md) — customise JDBC serialization
