---
icon: material/format-list-bulleted
---

# Properties Reference

All properties are prefixed with `failover`. There are no mandatory properties — the framework starts with production-safe defaults.

---

## Root Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.enabled` | `boolean` | `true` | Enable or disable the entire failover framework. Set `false` to bypass all interception without removing annotations. |
| `failover.type` | `FailoverType` | `BASIC` | Execution strategy. `BASIC` uses try/catch; `RESILIENCE` wraps upstream calls in a Resilience4j circuit-breaker; `CUSTOM` for your own `FailoverExecution` bean. |
| `failover.exception-policy` | `ExceptionPolicy` | `RETHROW` | Behaviour when recovery finds nothing. `RETHROW` re-throws the original upstream exception; `NEVER_THROW` returns `null` (or the `RecoveredPayloadHandler` result); `CUSTOM` for your own `MethodExceptionPolicy` bean. |

---

## Store Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.type` | `StoreType` | `INMEMORY` | Backing store. `INMEMORY` (dev/test only — not persistent), `CAFFEINE`, `JDBC`, `CUSTOM`. |
| `failover.store.async` | `boolean` | `true` | Offload write operations (`store`, `delete`, `cleanByExpiry`) to a background virtual-thread executor. `find` is always synchronous. Set `false` when using the JDBC `SCHEMA` multi-tenant strategy. |
| `failover.store.async-executor.concurrency-limit` | `int` | `0` | Max concurrently in-flight async store writes. `0` (or negative) = unbounded (default). A positive value bounds the executor (back-pressure guard) while still running accepted tasks on virtual threads. |
| `failover.store.async-executor.rejection-policy` | `RejectionPolicy` | `DISCARD` | What happens when a write is submitted at the concurrency limit (only when limit > 0). `DISCARD` drops it with a `WARN` (non-blocking; data is regenerable cache); `CALLER_RUNS` runs it on the calling thread (back-pressure, not a virtual thread); `ABORT` throws `RejectedExecutionException`. |
| `failover.store.inmemory.max-entries` | `int` | `10000` | Max entries retained by the in-memory store; the least-recently-accessed entry is evicted (LRU) once exceeded. `0` (or negative) = unbounded. Caps heap growth from high-cardinality keys. |
| `failover.store.caffeine.max-size` | `long` | `10000` | Max entries for the Caffeine store; once exceeded Caffeine evicts by its size-based (Window TinyLFU) policy. Same default as `inmemory.max-entries`. `0` (or negative) = unbounded (limited only by per-entry expiry). |

### JDBC Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.jdbc.table-prefix` | `String` | `""` | Prefix prepended to `FAILOVER_STORE` to form the table name. `MYAPP_` → table `MYAPP_FAILOVER_STORE`. Validated to contain only letters, digits, underscores, and dot-separated qualifiers. |
| `failover.store.jdbc.allowed-payload-classes` | `List<String>` | `[]` | Deserialization allowlist for the JDBC store (other store types hold live objects and never deserialize). Exact class names or package prefixes. **Additive** to the secure-by-default auto-allowlist derived from discovered `@Failover` payload packages — set only for classes the scanner cannot infer. See [Security](../support/security.md). |
| `failover.store.jdbc.encryption.enabled` | `boolean` | `false` | Payload-at-rest encryption for the `PAYLOAD` column. Gates the **write** side only: new rows are written as `ENC(<cipher>:<ciphertext>)`. Reads always honour the `ENC(...)` marker, so toggling this leaves both existing encrypted rows and plaintext rows readable. JDBC-only. |
| `failover.store.jdbc.encryption.cipher` | `String` | `"b64"` | Id of the registered `PayloadCipher` used for new writes. Default `b64` is the built-in Base64 encoder — **encoding only, not real encryption**. Declare a `PayloadCipher` bean with a real algorithm and set this to its id for actual protection. |

### Multi-Tenant Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.multitenant.enabled` | `boolean` | `false` | Enable multi-tenant store routing. |
| `failover.store.multitenant.strategy` | `JdbcMultiTenantStrategy` | `TABLE_PREFIX` | `TABLE_PREFIX` — separate table per tenant. `SCHEMA` — separate schema per tenant (requires custom `TenantStoreFactory` bean). |
| `failover.store.multitenant.default-tenant` | `String` | `""` | Fallback tenant ID when `TenantResolver` returns `null`. Throws `FailoverStoreException` if blank and resolver returns `null`. |
| `failover.store.multitenant.strict` | `boolean` | `false` | In `TABLE_PREFIX` mode, reject a tenant that is not present in `tenants` (throws `FailoverStoreException`) instead of silently routing it to the shared global table. When `false`, such a tenant is allowed with a one-time `WARN`. The `default-tenant` is exempt. |
| `failover.store.multitenant.tenants` | `Map<String, TenantConfig>` | `{}` | Per-tenant configuration. Key = tenant ID. Each entry can override `table-prefix` (TABLE_PREFIX strategy) or `schema` (SCHEMA strategy). |

---

## Scheduler Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.scheduler.enabled` | `boolean` | `true` | Enable or disable both schedulers. |
| `failover.scheduler.report-cron` | `String` | `"0 0 0 * * *"` | Cron expression for the observable report publisher. Default: daily at midnight. |
| `failover.scheduler.cleanup-cron` | `String` | `"0 0 * * * *"` | Cron expression for the expiry-cleanup scheduler. Default: every hour. |

---

## Scatter Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.scatter.parallel` | `boolean` | `true` | Dispatch scatter/gather slices in parallel using virtual threads. Set `false` for sequential processing. |
| `failover.scatter.timeout` | `Duration` | `10s` | Per-slice timeout for the parallel path (ignored when `parallel=false`). On timeout a recover slice is treated as not recovered, and a store slice surfaces the timeout (isolated by the execution layer) — a hung slice never blocks the caller indefinitely. Empty/null = wait indefinitely. |
| `failover.scatter.concurrency-limit` | `int` | `0` | Max concurrently in-flight scatter slices across all parallel dispatches. `0` (or negative) = unbounded (default). A positive value bounds slice fan-out while still running accepted slices on virtual threads. |
| `failover.scatter.rejection-policy` | `RejectionPolicy` | `DISCARD` | What happens when a slice is submitted at the concurrency limit (only when limit > 0). `DISCARD` drops it with a `WARN` (a discarded recover slice yields no data, already tolerated by gather); `CALLER_RUNS` runs it on the calling thread; `ABORT` throws `RejectedExecutionException`. |

---

## Observable Properties

Control how failover metrics are published. See [Observability](../modules/observability.md).

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.observable.async.enabled` | `boolean` | `true` | Publish metrics off the caller thread via a bounded queue drained by a virtual-thread worker, so emitting metrics can never block or slow the `@Failover` call. Set `false` to publish synchronously (deterministic for tests). |
| `failover.observable.async.queue-capacity` | `int` | `10000` | Bounded queue size. A full queue **drops** the metric (counted as `failover.metrics.dropped.total`) rather than back-pressuring the caller. Raise for very high failover throughput. |
| `failover.observable.instance.mode` | `auto` \| `always` \| `never` | `auto` | `instance`-tag strategy. **`auto`** (default) tags every registry **except** a Prometheus one (Prometheus adds `instance` itself at scrape; push backends like OTLP/Elastic don't, so they get tagged — zero config). `always` tags every registry incl. Prometheus (surfaces as `exported_instance`). `never` disables the tag. |
| `failover.observable.instance.id` | `String` | `""` | Instance-tag value. Blank ⇒ resolved at startup from `spring.application.name` + host name. On k8s/Docker set to `${HOSTNAME}` (or the pod name via Downward API) for a reliable, readable identity. |
| `failover.observable.cardinality.enabled` | `boolean` | `true` | Cardinality guard: cap the number of distinct `name` tag values on `failover.*` meters so a misconfigured high-cardinality name can't explode the registry. |
| `failover.observable.cardinality.max-apis` | `int` | `1000` | Maximum distinct `name` values; new series are denied once the cap is hit. |

---

## Dashboard Properties

Only active with `failover-dashboard-spring-boot-starter` on the classpath (see [Dashboard](../modules/dashboard.md)). `enabled` is the only switch you need; everything else has a working default once enabled.

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.dashboard.enabled` | `boolean` | `false` | Master switch (secure-by-default). Must be explicitly `true` to map anything. |
| `failover.dashboard.base-path` | `String` | `/failover-dashboard` | Single dedicated namespace for the UI and API. Must start with `/`, must not be `/`, and must not end with `/` — a misconfigured value fails the context fast. |
| `failover.dashboard.exposure.ui` | `boolean` | `true` | Serve the static HTML/JS UI. Set `false` to narrow to API-only. |
| `failover.dashboard.exposure.api` | `boolean` | `true` | Serve the JSON API. Set `false` to narrow to UI-only. |
| `failover.dashboard.exposure.include` | `List<String>` | `[config, failover-health, metrics, health, cluster, instances]` | Which API endpoints are served. Trim to narrow; an endpoint not listed returns `404`. (`cluster` gates the shared-store snapshot ingest; `instances` gates the per-instance view.) |
| `failover.dashboard.security.role` | `String` | `FAILOVER_ADMIN` | Role required for `base-path/**` when Spring Security is present. |
| `failover.dashboard.security.allow-insecure` | `boolean` | `false` | When Spring Security is absent: `false` fails the context fast (fail-closed); `true` starts unsecured with a loud `WARN` (dev / trusted-network only). **Refused outright when the `prod` profile is active** — production must add Spring Security. |
| `failover.dashboard.history.enabled` | `boolean` | `false` | Enable the server-side ring-buffer sampler and `/api/metrics/series` for reload-surviving trends. |
| `failover.dashboard.history.samples` | `int` | `120` | Ring-buffer capacity (retained sample count). |
| `failover.dashboard.history.sample-interval-seconds` | `int` | `15` | Seconds between samples. |
| `failover.dashboard.health.degraded-threshold` | `double` | `0.99` | Healthy-rate floor for `HEALTHY`; below it (down to the unhealthy floor) is `DEGRADED`. |
| `failover.dashboard.health.unhealthy-threshold` | `double` | `0.90` | Healthy-rate floor for `DEGRADED`; below it is `UNHEALTHY`. |

### Cluster Properties

Where the dashboard reads metrics from in a multi-instance deployment (see [Distributed Deployment](../modules/dashboard.md#distributed-deployment-scenarios)). Default `local` reads this instance only.

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.dashboard.cluster.mode` | `String` | `local` | `local` (this instance) \| `prometheus` (PromQL across instances) \| `shared-store` (peers push snapshots, aggregated in-app). |
| `failover.dashboard.cluster.prometheus.base-url` | `String` | `""` | Prometheus base URL (e.g. `http://prometheus:9090`). Blank ⇒ falls back to `local`. Used when `mode=prometheus`. |
| `failover.dashboard.cluster.prometheus.token` | `String` | `""` | Optional bearer token sent as `Authorization: Bearer …`. |
| `failover.dashboard.cluster.prometheus.timeout-seconds` | `int` | `5` | Per-query connect/read timeout. |
| `failover.dashboard.cluster.shared-store.store` | `String` | `inmemory` | `inmemory` (default) \| `jdbc` (durable; needs the `failover-dashboard-snapshotstore-jdbc` module + a `DataSource`). Used when `mode=shared-store`. |
| `failover.dashboard.cluster.shared-store.liveness-seconds` | `int` | `45` | A peer whose latest snapshot is older than this is excluded from the aggregate (treated as silent). |
| `failover.dashboard.cluster.shared-store.max-instances` | `int` | `10` | Supported small-cluster ceiling; exceeding it logs a warning (graduate to `prometheus`). |
| `failover.dashboard.cluster.shared-store.sample-interval-seconds` | `int` | `30` | Cluster-trend sampling cadence. |
| `failover.dashboard.cluster.shared-store.retention.max-age` | `Duration` | `7d` | Trend-history age bound; older points evicted. |
| `failover.dashboard.cluster.shared-store.retention.max-entries` | `int` | `100000` | Trend-history size bound; oldest truncated first. |
| `failover.dashboard.cluster.shared-store.jdbc.table-prefix` | `String` | `""` | Prefix prepended to base `FAILOVER_DASHBOARD_SNAPSHOT` (validated). Used when `store=jdbc`. |
| `failover.dashboard.cluster.shared-store.jdbc.auto-ddl` | `boolean` | `true` | Create the snapshot table on startup if missing. |
| `failover.dashboard.cluster.snapshot.publish-url` | `String` | `""` | Peer-side: dashboard ingest URL each instance POSTs its snapshot to. Blank ⇒ this instance does not push. |
| `failover.dashboard.cluster.snapshot.interval-seconds` | `int` | `15` | Throttle interval: at most one push per this many seconds. Pushes are event-driven (triggered by metric events), not polled. |
| `failover.dashboard.cluster.snapshot.retry-interval-seconds` | `int` | `300` | Backoff duration after a push failure. Silent retry after this interval; logs INFO on recovery. |

---

## Full Example

```yaml title="application.yml"
failover:
  enabled: true
  type: basic                        # basic | resilience | custom
  exception-policy: rethrow          # rethrow | never_throw | custom

  store:
    type: jdbc                       # inmemory | caffeine | jdbc | custom
    async: true
    async-executor:                  # back-pressure guard for async writes (default unbounded)
      concurrency-limit: 0           # 0 = unbounded; >0 caps in-flight writes (still virtual threads)
      rejection-policy: discard      # discard | caller_runs | abort (only when limit > 0)
    jdbc:
      table-prefix: MYAPP_
      allowed-payload-classes: []    # additive; auto-derived from @Failover payload packages
      encryption:
        enabled: false             # encrypt new PAYLOAD writes as ENC(<cipher>:...); reads honour marker regardless
        cipher: b64                # registered PayloadCipher id; b64 = Base64 encode only (NOT real encryption)
    multitenant:
      enabled: false
      strategy: table_prefix
      default-tenant: ""
      strict: false                  # reject tenants absent from the tenants map
      tenants:
        acme:
          table-prefix: ACME_
        globex:
          table-prefix: GLOBEX_

  scheduler:
    enabled: true
    report-cron: "0 0 0 * * *"       # daily midnight
    cleanup-cron: "0 0 * * * *"      # every hour

  scatter:
    parallel: true
    timeout: 10s                     # per-slice timeout for the parallel path
    concurrency-limit: 0             # 0 = unbounded; >0 caps slice fan-out (still virtual threads)
    rejection-policy: discard        # discard | caller_runs | abort (only when limit > 0)

  dashboard:                         # needs failover-dashboard-spring-boot-starter on the classpath
    enabled: false                   # master switch (secure-by-default) — set true to map anything
    base-path: /failover-dashboard   # single dedicated namespace for UI + API
    exposure:                        # defaults expose everything; set flags only to NARROW
      ui: true
      api: true
      include: [config, failover-health, metrics, health]
    security:
      role: FAILOVER_ADMIN           # required role for base-path/** when Spring Security is present
      allow-insecure: false          # if true, start unsecured + loud WARN (dev/trusted-net ONLY)
                                     # refused outright when the 'prod' profile is active
    history:
      enabled: false                 # server-side ring-buffer sampler + /api/metrics/series
      samples: 120                   # ring-buffer capacity (retained sample count)
      sample-interval-seconds: 15
    health:
      degraded-threshold: 0.99       # healthy-rate floor for HEALTHY
      unhealthy-threshold: 0.90      # healthy-rate floor for DEGRADED
```

---

## Next Steps

- [Store Types](store-types.md) — choose the right backing store for your environment
- [Multi-Tenant](multi-tenant.md) — per-tenant store routing
- [Modules](../modules/index.md) — module responsibilities and dependencies
