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

## Choosing a Store

Failover only protects callers if the last-known-good value still exists when an upstream fails.
A **non-durable** store (InMemory, Caffeine) holds that value only in the JVM heap of one instance —
it is lost on restart and is invisible to other instances. So the right choice is driven by your
**deployment topology** and your tolerance for losing cached data.

```text
                ┌─ Need recovery to survive a restart, or
                │  run across more than one instance?
                │
        ┌── YES ─┴───────────────► JDBC   (durable, shared)   ◄── recommended for production
        │
        └── NO (single node, loss on restart acceptable)
                │
                ├─ Want richer eviction / TTL handling?  ──► Caffeine
                └─ Want zero dependencies (dev/test)?    ──► InMemory   (default)

   Have your own backend (Redis, Mongo, …)?  ──► Custom (implement FailoverStore)
```

| Question | If **yes** |
|---|---|
| Production or any multi-instance deployment? | **JDBC** — the only built-in durable, shared store. |
| Single node, and losing cached data on restart is acceptable? | **Caffeine** (or InMemory). |
| Dev / test, want zero setup? | **InMemory** (default). |
| Already operate Redis / Mongo / another store? | **Custom** — implement `FailoverStore<T>`. |

!!! danger "Non-durable stores in production"
    InMemory and Caffeine are **per-instance and volatile**. After a restart — or for any request
    routed to a *different* instance — there is no stored value to recover, so the caller sees the raw
    upstream failure. The library logs a startup `WARN` naming the recommended alternative when a
    non-durable store is active. For production, use **JDBC** (or a durable Custom store).

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

### Capacity planning

The JDBC store takes **one upsert per successful protected call**, so write volume scales with success
throughput:

```
writes/sec  ≈  (success QPS per @Failover method)  ×  (number of instances)
row count   ≈  (distinct keys per failover)  ×  (failovers)   — bounded by expiry cleanup
```

Size the store accordingly, and use the controls the framework already provides:

- **Bound the write blast radius.** Under a failure storm every call enqueues an async write; cap
  in-flight writes with `failover.store.async-executor.concurrency-limit` (see
  [Async Store](../modules/store-async.md)). The store is a regenerable cache, so dropping a write
  under saturation is acceptable.
- **Keep TTL as short as the use case allows** (`@Failover(expiryDuration=…)`) and ensure the expiry
  cleanup scheduler runs (`failover.scheduler`) — together they bound row count. The `EXPIRE_ON` index
  (above) keeps cleanup an index scan.
- **Monitor table growth.** Enable the opt-in capacity gauge:
  ```yaml
  failover:
    store:
      jdbc:
        live-entries-gauge-enabled: true   # exposes failover.live.entries (SELECT COUNT(*) per scrape)
  ```
  Off by default because it issues a `COUNT(*)` per scrape per failover name. When on, the
  `failover.live.entries{name,domain}` gauge reports rows per failover so you can alert on growth. Not
  available in multi-tenant mode (the routing wrapper is not size-aware). See
  [Observability](../how-to/observability.md).

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

## Deployment Topologies & Modes

The store choice and these orthogonal modes together determine the behaviour of failover in your
deployment. They compose — e.g. a clustered, multi-tenant, async JDBC store is the typical production setup.

### Single-node

One instance; recovery only needs to survive within the running process (or a restart).

- **Loss-on-restart acceptable** → InMemory or Caffeine. Zero infrastructure.
- **Must survive restart** → JDBC (durable), even on a single node.

### Clustered / multi-instance

More than one instance behind a load balancer. A request that succeeded on instance A may fail on
instance B, so the last-known-good value must be **shared state**.

- **Use JDBC.** All instances point at the same database/table; any instance can recover a value
  stored by any other. This is what makes failover "clustered" — there is no special cluster mode for
  the store itself, just shared durable state.
- InMemory / Caffeine do **not** work across instances — each instance has its own isolated copy.
- See [Async Store](../modules/store-async.md): writes are offloaded to a virtual-thread executor by
  default, so the shared-DB write does not block the request thread.

!!! note "Store cluster vs. dashboard cluster"
    Making the *store* clustered is just "use JDBC". Separately, the **dashboard** has its own
    cross-instance aggregation modes (`failover.dashboard.cluster.mode` = `local` | `prometheus` |
    `shared-store`) for *viewing* metrics across instances. They are independent concerns — see the
    [Dashboard module](../modules/dashboard.md). A clustered failover store does not require the
    dashboard, and vice versa.

### Multi-tenant

One deployment serving multiple tenants whose data must be isolated. Routes each tenant to its own
table (`TABLE_PREFIX`) or schema (`SCHEMA`) on top of the JDBC store.

```yaml title="application.yml"
failover:
  store:
    type: jdbc
    multitenant:
      enabled: true
```

See [Multi-Tenant](multi-tenant.md) for routing strategies and the `TenantResolver` SPI.

### Async vs. synchronous writes

Orthogonal to store type. `failover.store.async=true` (default) offloads `store`/`delete`/`cleanByExpiry`
to a virtual-thread executor — the request thread is never blocked by store I/O. Set `async=false` for
deterministic, synchronous writes (required for the JDBC `SCHEMA` multi-tenant strategy, and used in
integration tests). `find` is always synchronous — the caller needs the recovered value immediately.

### Summary

| Topology | Store | Key settings |
|---|---|---|
| Dev / test | InMemory | defaults |
| Single node, volatile OK | Caffeine | `type: caffeine` |
| Single node, durable | JDBC | `type: jdbc` |
| Clustered / multi-instance | JDBC (shared DB) | `type: jdbc`, `async: true` |
| Multi-tenant | JDBC | `type: jdbc`, `multitenant.enabled: true` |
| Custom backend (Redis, …) | Custom | implement `FailoverStore<T>` |

---

## Next Steps

- [Multi-Tenant](multi-tenant.md) — per-tenant table or schema routing
- [Async Store](../modules/store-async.md) — how non-blocking writes work
- [Dashboard](../modules/dashboard.md) — cross-instance metrics aggregation modes
- [Payload Column Resolver](../how-to/payload-column-resolver.md) — customise JDBC serialization
