---
icon: material/view-dashboard-outline
---

# Dashboard

`failover-dashboard` is a self-contained, opt-in, secure-by-default observability dashboard. Drop the dedicated starter on the classpath, enable it in YAML, and open `/failover-dashboard` to see every `@Failover` configuration plus live health metrics — rendered as cards and charts, served straight from the jar (no CDN, no build step).

It introduces **no new instrumentation**: it is a pure consumer of signals the framework already publishes — `FailoverScanner` for configuration and the Micrometer `failover.*` meters for metrics.

---

## Obtaining the Dashboard

The default `failover-spring-boot-starter` ships **none** of this. Add the dedicated starter:

```xml title="pom.xml"
<dependency>
  <groupId>com.societegenerale.failover</groupId>
  <artifactId>failover-dashboard-spring-boot-starter</artifactId>
</dependency>
```

Adding the jar makes the dashboard *available*, not *active*. Nothing is mapped until you enable it.

---

## Enabling It

`enabled` is the **only** switch you must set — everything else has a working default:

```yaml title="application.yml"
failover:
  dashboard:
    enabled: true        # default false (secure-by-default)
```

Once enabled, the UI and the full JSON API are served. The granular flags below exist only to **narrow** exposure, never to opt in to it.

| URL | Serves |
|---|---|
| `/failover-dashboard` | the UI (bare path forwards to `index.html`) |
| `/failover-dashboard/api/config` | every `@Failover` point + global settings |
| `/failover-dashboard/api/config/settings` | effective global `failover.*` / `failover.dashboard.*` config, grouped |
| `/failover-dashboard/api/failover-health` | actuator-style overall status + active configuration |
| `/failover-dashboard/api/metrics` | global + per-API KPIs and rates |
| `/failover-dashboard/api/metrics/source` | metrics provenance (mode, instances reporting, freshness) for the UI source badge |
| `/failover-dashboard/api/health` | per-API health classification |
| `/failover-dashboard/api/metrics/series` | trend samples — local: the in-memory ring (empty unless history is enabled); `mode=prometheus`: cluster-wide via `query_range` |
| `/failover-dashboard/api/instances` | per-instance metrics for the Instances tab — empty in `local` (single JVM); populated in `shared-store` / `prometheus` |
| `/failover-dashboard/api/cluster/snapshot` | *(shared-store only, POST)* peer snapshot ingest |

`base-path` is a single dedicated, non-root namespace covering both the UI and the API; override it to relocate the whole dashboard. `server.servlet.context-path` still prepends as usual.

---

## Configuration

Every `failover.dashboard.*` property in one place. **`enabled` is the only one you need** — the rest have working defaults and exist to narrow exposure, secure the gate, or turn on trend history.

```yaml title="application.yml — full dashboard configuration (all defaults shown)"
failover:
  dashboard:
    enabled: false                   # master switch (secure-by-default) — set true to map anything
    base-path: /failover-dashboard   # single dedicated namespace for the UI + API
    exposure:                        # defaults expose everything; set flags only to NARROW
      ui: true                       # serve the static HTML/JS UI
      api: true                      # serve the JSON API
      include: [config, failover-health, metrics, health]   # which API endpoints are served
    security:
      role: FAILOVER_ADMIN           # role required for base-path/** when Spring Security is present
      allow-insecure: false          # start unsecured + loud WARN when Spring Security is absent
                                     #   (dev / trusted-network only; REFUSED under the 'prod' profile)
    history:                         # opt-in server-side trend ring buffer (see Trend History below)
      enabled: false                 # enable the sampler + /api/metrics/series endpoint
      samples: 120                   # ring-buffer capacity (retained sample count)
      sample-interval-seconds: 15    # seconds between samples
    health:                          # healthyRate thresholds for the per-API status badge
      degraded-threshold: 0.99       # >= ⇒ HEALTHY; below (down to unhealthy floor) ⇒ DEGRADED
      unhealthy-threshold: 0.90      # >= ⇒ DEGRADED; below ⇒ UNHEALTHY
    cluster:                         # where metrics are read from across instances
      mode: local                    # local (default) | prometheus | shared-store — see Distributed below
      prometheus:                    # used when mode=prometheus (aggregates failover.* across instances)
        base-url: ""                 # e.g. http://prometheus:9090 (blank ⇒ falls back to local)
        token: ""                    # optional bearer token (blank ⇒ none)
        timeout-seconds: 5           # per-query connect/read timeout
```

| Property | Default | Purpose |
|---|---|---|
| `enabled` | `false` | Master switch. Nothing is mapped until `true`. |
| `base-path` | `/failover-dashboard` | Dedicated namespace for UI + API. Must start with `/`, not be `/`, no trailing `/` — else the context fails fast. |
| `exposure.ui` | `true` | Serve the static UI. `false` = API-only. |
| `exposure.api` | `true` | Serve the JSON API. `false` = UI-only. |
| `exposure.include` | all of them | API endpoints served: `config`, `failover-health`, `metrics`, `health`, `cluster`, `instances`. Trim to narrow; an omitted endpoint returns `404`. `/api/metrics/series` is gated with `metrics`; `/api/cluster/snapshot` (shared-store ingest) with `cluster`; `/api/instances` with `instances`. |
| `security.role` | `FAILOVER_ADMIN` | Role required for `base-path/**` when Spring Security is present. |
| `security.allow-insecure` | `false` | When Spring Security is absent: `false` fails fast (fail-closed); `true` starts unsecured with a loud WARN. **Refused under the `prod` profile.** |
| `history.enabled` | `false` | Turn on the server-side trend ring buffer + `/api/metrics/series`. |
| `history.samples` | `120` | Retained sample count (ring-buffer capacity). |
| `history.sample-interval-seconds` | `15` | Seconds between samples. |
| `health.degraded-threshold` | `0.99` | Healthy-rate floor for `HEALTHY`. |
| `health.unhealthy-threshold` | `0.90` | Healthy-rate floor for `DEGRADED`; below is `UNHEALTHY`. |
| `cluster.mode` | `local` | Where metrics are read from. `local` = this instance's registry (default). `prometheus` aggregates the `failover.*` meters across all instances via the Prometheus HTTP API. `shared-store` is a later phase. See the distributed-dashboard design document. |
| `cluster.prometheus.base-url` | `""` | Prometheus base URL for `mode=prometheus` (e.g. `http://prometheus:9090`). Blank, or unreachable at runtime, falls back to the local registry with a warning. |
| `cluster.prometheus.token` | `""` | Optional bearer token for Prometheus. |
| `cluster.prometheus.timeout-seconds` | `5` | Per-query connect/read timeout. |

See the [Properties Reference](../configuration/properties-reference.md#dashboard-properties) for the canonical table.

---

## The Toolbar

Every view shares the top bar:

- **Status chip** — overall live status (`Healthy` / `Degraded` / `Unhealthy`), derived from the worst per-API health.
- **Tabs** — `Overview`, `Per-API`, `Health`, `Config` (Overview is the default; the open tab is kept in the URL hash, e.g. `#per-api`).
- **Auto-refresh** — selectable cadence: `off`, `10s`, `30s`, `1m`, `10m`, `1h` (default `30s`).
- **Refresh now** (`⟳`) — reload immediately, independent of the cadence.
- **Last-updated** — timestamp of the last successful load.
- **Theme toggle** (`◐`) — switch dark / light (or force it with `?theme=dark` / `?theme=light`).
- **Docs** — the failover icon opens this documentation in a new tab.

All controls carry hover tooltips. Dark is the default "control-room" theme; both themes are shown for each view below.

---

## The Views

### Overview

The at-a-glance health and KPI surface.

- **Health banner** — a closable, colour-coded summary shown on each load: *all APIs healthy* (green), *N need attention* (amber, names listed), or *N unhealthy — action needed* (red).
- **Signals — row 1 (health):** a large **Overall API Health** gauge (the `(success + recovered) / total` healthy-served rate) beside a grid of **per-API health cards**, sorted **worst-first** so a struggling API surfaces top-left. Each card shows its health %, status, calls and failover %.
- **Signals — row 2 (metrics):** one card per KPI — **Overall calls**, **Success rate**, **Failover rate**, **Recovery rate**, **Non-recovery rate** (each rate shows the underlying count too), **Persistence failures** (async store writes that were lost — alert on any non-zero), and **Recover latency** (mean recover-path ms).
- **Charts:** *Did the caller get a result?* (live value / recovered / hard failure), *When failover fired, did it recover?* (full / partial / nothing usable — partial = scatter-gather slices), *Successful vs Recovered*, a full-width **Trend** (calls per tick + success / failover / recovery rate over time), *Why did upstream fail?* (top exception types), and *Latency — store vs recover* per API.

### Per-API

Drill-down per failover point.

- **Per-API health table** — sortable (click any header): calls, healthy-rate bar, success / failover / recovery %, errors, an inline **failover-trend sparkline**, and a `HEALTHY` / `DEGRADED` / `UNHEALTHY` badge.
- **Failover trend — all APIs** — one line per failover point, failover invocations per tick.
- **Per-API breakdown** — grouped bars: overall vs failover vs recovered vs not-recovered.
- **Latency** (on Overview) shows store/recover **mean** plus **p95/p99** when available (`local` + `prometheus`); `shared-store` shows mean/max only (percentiles can't be merged from per-instance snapshots).

### Instances

*Cluster only* — shown when the dashboard reads from a multi-instance source (`shared-store` or `prometheus`); hidden for `local`. Answers the first incident question: **one bad node, or all of them?**

- **Roll-up cards** — Total · Reporting · Silent · Healthy · Degraded · Unhealthy. *Silent* = expected-but-not-reporting (beyond the liveness window in `shared-store`).
- **Per-instance table** — a row per instance: id, live/silent dot, calls, success / failover / recovery %, p95 recover, last-seen, and a status badge. Click a row to drill in.
- **Drill-down** — the selected instance's *own* KPIs (calls, success, failover, users-unblocked, p95) — not the cluster aggregate.

Data comes from `MetricsSource.instances()`: in `shared-store` from the per-instance snapshots the store already holds; in `prometheus` from `sum by (name, instance) (failover_*)` queries.

### Health

Actuator-style subsystem health, mirroring the `/actuator/health/failover` contributor.

- **Cluster roll-up** — healthy / degraded / unhealthy API counts + instances reporting, with a provenance line (local vs cluster aggregate). Cluster-wide via `MetricsSource.health()` across whichever tier is active.
- **Status hero** — `UP` (at least one `@Failover` registered) or `DOWN` (none discovered — a misconfiguration signal).
- **Active configuration** — the global config rendered as small stat cards (registered failovers, type, store type, async, exception policy, scheduler…). Types and flags only — never credentials or connection strings (§9).

### Config

- **Failover configuration** — a sortable, filterable table of every `@Failover` point: name, domain, expiry, store, execution, recover-all, splitter, key generator, expiry policy. Empty per-annotation overrides render as `default`.
- **Global settings** — the effective `failover.*` / `failover.dashboard.*` settings grouped into panels (Core / Store / Scatter / Scheduler / Dashboard). Types, flags, crons, thresholds and paths only.

=== "Dark mode"

    === "Overview"

        ![Failover dashboard — overview, dark theme](../web/assets/images/dashboard-overview.png)

    === "Per-API"

        ![Failover dashboard — per-API view, dark theme](../web/assets/images/dashboard-perapi.png)

    === "Instances"

        ![Failover dashboard — instances view, dark theme](../web/assets/images/dashboard-instances.png)

    === "Health"

        ![Failover dashboard — health view, dark theme](../web/assets/images/dashboard-health.png)

    === "Config"

        ![Failover dashboard — config view, dark theme](../web/assets/images/dashboard-config.png)

=== "Light mode"

    === "Overview"

        ![Failover dashboard — overview, light theme](../web/assets/images/dashboard-overview-light.png)

    === "Per-API"

        ![Failover dashboard — per-API view, light theme](../web/assets/images/dashboard-perapi-light.png)

    === "Instances"

        ![Failover dashboard — instances view, light theme](../web/assets/images/dashboard-instances-light.png)

    === "Health"

        ![Failover dashboard — health view, light theme](../web/assets/images/dashboard-health-light.png)

    === "Config"

        ![Failover dashboard — config view, light theme](../web/assets/images/dashboard-config-light.png)

---

## KPIs — Derived, Not Measured

Every KPI is derived from counters that already exist (`failover.store.total`, `failover.recovery.outcome.total`, `failover.recover.total`). Per API, let `S` = stored upstream successes and `F` = recovered + not-recovered + error:

| KPI | Formula | Meaning |
|---|---|---|
| Success rate | `S / (S+F)` | upstream healthy → live value stored |
| Failover rate | `F / (S+F)` | upstream failed → failover flow started |
| Recovery rate | `recovered / F` | failover served a stored, non-expired value |
| Non-recovery rate | `(not_recovered + error) / F` | failover found nothing usable |
| Health (healthy-served) | `(S + recovered) / (S+F)` | caller got a usable result (live or recovered) |

Zero denominators yield `0`, never `NaN`. Health is classified `HEALTHY` / `DEGRADED` / `UNHEALTHY` against configurable thresholds.

Three further operational signals are surfaced from existing meters (still no new instrumentation):

| Signal | Source meter | Why it matters |
|---|---|---|
| **Async write failures** | `failover.store.async.failed` | Async store writes that threw inside the executor — failover data was **not persisted**. Shown as a KPI, a red per-API table column, and a loud banner when non-zero. Alert on any increase. |
| **Latency (mean / max)** | `failover.operation.duration` (timer) | Wall time of the store and recover paths, per API. Mean + max only — the timer has no percentile histogram, so p95/p99 are intentionally absent. |
| **Top exception types** | `failover.exception.total` | Which upstream exception types trigger failover most — quick root-cause triage. |

---

## Security — Fail-Closed (§9)

The dashboard surfaces internal operational data, so the access gate is **not** relaxed by the convenience defaults:

- **Spring Security present** (bundled by the starter): the module contributes a `SecurityFilterChain` scoped to `base-path/**` requiring role `FAILOVER_ADMIN` (configurable). Override it with your own `dashboardSecurityFilterChain` bean.
- **Spring Security absent**: the context **fails fast** at startup — unless `failover.dashboard.security.allow-insecure=true`, which starts unsecured with a loud repeated `WARN` (trusted-network / dev only). The `allow-insecure` escape hatch is **refused outright when the `prod` profile is active**: it can never silently disable the access gate in production.

A strict, static-only `Content-Security-Policy` is applied to every dashboard response (no remote or inline scripts; Chart.js is vendored). The API is read-only — no endpoint mutates state. Only annotation metadata and aggregate counts are exposed — never payload data, keys, credentials, or connection strings.

```java title="Consumer override (same as Actuator)"
http.authorizeHttpRequests(a -> a.requestMatchers("/failover-dashboard/**").hasRole("FAILOVER_ADMIN"));
```

---

## Trend History (opt-in)

By default the trend charts (the call/rate timeline and per-API failures) are buffered **client-side**, so they live only as long as the tab is open — a browser reload clears them and they rebuild from the next poll. This is by design and harmless: the cumulative KPIs, per-API counts and health table are re-derived from the server-side `failover.*` counters on every load, so **none of those numbers are lost** on reload — only the in-tab trend lines reset.

For reload-surviving trends, enable the server-side ring-buffer sampler:

```yaml title="application.yml"
failover:
  dashboard:
    history:
      enabled: true            # default false — registers the sampler + /api/metrics/series
      samples: 120             # ring-buffer capacity (retained sample count)
      sample-interval-seconds: 15   # seconds between samples
```

**How it works.** A scheduled sampler snapshots the global cumulative `failover.*` counters every `sample-interval-seconds` into a bounded in-memory ring of `samples` entries (oldest evicted when full). The retained window is therefore:

```
window ≈ samples × sample-interval-seconds
       = 120 × 15s = 1800s (30 minutes) with the defaults
```

Size it for the span you want visible: e.g. `samples: 240, sample-interval-seconds: 15` ≈ 1 hour; `samples: 120, sample-interval-seconds: 60` ≈ 2 hours at coarser resolution. The buffer is a fixed memory cost (`samples` small records), independent of traffic.

**The `/api/metrics/series` endpoint.** Returns the retained samples (global cumulative totals per timestamp) in chronological order. It accepts an optional `windowSec` query param — only samples within that many seconds of now are returned; `windowSec=0` returns all retained (the UI uses `0` on load). The endpoint is registered **only** when `history.enabled=true`, and is gated by the `metrics` exposure flag (`exposure.include`) and the same access gate as the rest of the dashboard.

**UI behaviour.** When enabled, the Overview **hydrates the call/rate timeline from `/api/metrics/series` on load**, so a browser reload keeps its trend instead of starting blank; live polling then continues seamlessly from the last sample. The chart deltas consecutive cumulative samples (calls per interval) and derives the failover / recovery / non-recovery rates. (The per-API failures chart remains live-only — `/series` carries global totals, not per-API.) With history disabled the endpoint is absent and the UI silently falls back to the client-side buffer.

It is process-local and lost on restart — deliberately **not** a TSDB. For long-term, cross-restart analysis, point Prometheus/Grafana at the existing `failover.*` meters.

---

## Graceful Degradation

If Micrometer is not on the classpath, the **Config and Health views still work**; the Overview / Per-API views show a friendly "metrics unavailable" notice. If the Chart.js asset is missing, KPI cards and tables still render and a notice replaces the charts.

---

## Distributed Deployment — Scenarios

The dashboard reads the `failover.*` **meters**; `cluster.mode` chooses *where it reads them from*. Everything else (UI, KPIs, health, security) is identical across modes.

| Mode | Reads from | Infra | When |
|---|---|---|---|
| `local` (default) | this instance's in-process registry | none | single JVM, dev |
| `shared-store` | peers push snapshots → in-memory or JDBC | none, or a small DB table | small cluster (≤ ~10), no Prometheus |
| `prometheus` | Prometheus HTTP API across all instances | Prometheus/TSDB | large cluster |

Each scenario below is a complete, copy-pasteable example.

### Scenario A — Single JVM (default)

Nothing to configure beyond enabling the dashboard; `cluster.mode` defaults to `local`.

```yaml title="application.yml — the app that has @Failover methods"
failover:
  dashboard:
    enabled: true            # secure-by-default: off unless set
```

Open `http://<app>:<port>/failover-dashboard`. Behind a load balancer this shows only the node that answered (a "this instance only" badge makes that explicit) — use one of the cluster modes below for a true aggregate.

### Scenario B — Cluster via Prometheus (large clusters)

Each instance exposes `/actuator/prometheus`; Prometheus scrapes them; the dashboard aggregates with PromQL (`sum`, `rate`, `histogram_quantile` → cluster-wide p95/p99). Falls back to `local` if Prometheus is unreachable, so it never goes dark.

```yaml title="every app instance"
management:
  endpoints.web.exposure.include: prometheus,health
failover:
  dashboard:
    enabled: true
    cluster:
      mode: prometheus
      prometheus:
        base-url: http://prometheus:9090
        # token: <bearer>      # optional
        # timeout-seconds: 5
```

```yaml title="prometheus.yml (scrape config)"
scrape_configs:
  - job_name: my-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['app-1:8080', 'app-2:8080', 'app-3:8080']
```

Prometheus adds the `instance` label at scrape time, so the dashboard's per-instance grouping works automatically — you do **not** need the `failover.observable.instance` tag here (that tag is for push backends; see [Observability](observability.md)). The **Instances tab** then breaks the cluster down per node (`sum by (name, instance)`).

### Scenario C — Cluster via shared-store, in-memory (small clusters, no Prometheus)

Each instance **pushes** its local KPI snapshot to the dashboard; the dashboard aggregates them in memory with the same KPI math. Production-supported for ≤ ~10 instances. **Consistency over durability**: one (latest) snapshot per instance, stale peers excluded by a liveness window, reset-aware monotonic trend, age + size retention.

```yaml title="the dashboard host (aggregator + UI)"
failover:
  dashboard:
    enabled: true
    cluster:
      mode: shared-store
      shared-store:
        store: inmemory          # default
        liveness-seconds: 45     # a peer silent longer than this drops out of the aggregate
        max-instances: 10        # supported ceiling (warning beyond)
        sample-interval-seconds: 30   # cluster trend sampling cadence
        retention:
          max-age: 7d            # trend history age bound
          max-entries: 100000    # trend history size bound (oldest truncated)
```

```yaml title="every peer (including non-UI instances)"
failover:
  dashboard:
    enabled: true
    cluster:
      snapshot:
        publish-url: http://dashboard-host:8080/failover-dashboard/api/cluster/snapshot
        interval-seconds: 15
```

The push endpoint sits behind the dashboard's access gate, so peers authenticate with the configured role (basic auth) — see **Security** below. Lost on dashboard restart (it's in memory); use Scenario D for restart-survival.

Each pushed snapshot is retained per instance, so the **Instances tab** lists every reporting node (and flags silent ones past `liveness-seconds`); the **Health tab** shows the cluster roll-up.

### Scenario D — Cluster via shared-store, JDBC durable

Same as C, but snapshots persist to a database so the aggregate survives a dashboard restart. Add the optional module and flip one property.

```xml title="dashboard host pom.xml"
<dependency>
  <groupId>com.societegenerale.failover</groupId>
  <artifactId>failover-dashboard-snapshotstore-jdbc</artifactId>
</dependency>
```

```yaml title="dashboard host"
failover:
  dashboard:
    enabled: true
    cluster:
      mode: shared-store
      shared-store:
        store: jdbc
        liveness-seconds: 45
        max-instances: 10
        jdbc:
          table-prefix: ""       # prepended to the base table name; "" ⇒ FAILOVER_DASHBOARD_SNAPSHOT
          auto-ddl: true         # create the table on startup if missing
```

Requires a `DataSource` in the dashboard app (the usual `spring.datasource.*`). Peers are configured exactly as in Scenario C (`cluster.snapshot.publish-url`).

!!! question "Is multi-tenancy required for the snapshot store?"
    **No.** The snapshot store holds only **aggregate, non-sensitive failover metrics** (counts, rates, latency means/percentiles) — never business data, payloads, keys or PII. So the per-tenant data-isolation / compliance reasons that drive the *failover store's* multi-tenancy (`failover.store.multitenant`) **do not apply here**. One shared table is correct and simplest.

    If you need to **namespace** the table — e.g. several environments or several independent dashboards sharing one database — use `table-prefix` (validated: letters/digits/underscore only). If you genuinely want per-tenant *dashboards*, run separate dashboard instances each with its own `table-prefix` (or its own schema); the snapshot store itself stays single-table and tenant-agnostic by design.

**Table name.** `table-prefix` + the base `FAILOVER_DASHBOARD_SNAPSHOT` (e.g. prefix `DEMO_` → `DEMO_FAILOVER_DASHBOARD_SNAPSHOT`). The prefix is validated as a safe SQL identifier fragment (no injection).

**DDL.** With `auto-ddl: true` the table is created automatically. To manage the schema yourself (`auto-ddl: false`), create it with the dialect-appropriate type for the JSON column:

```sql title="PostgreSQL"
CREATE TABLE FAILOVER_DASHBOARD_SNAPSHOT (
    INSTANCE_ID  VARCHAR(255) PRIMARY KEY,
    RECEIVED_AT  BIGINT       NOT NULL,
    SUMMARY_JSON TEXT         NOT NULL          -- or JSONB
);
```

```sql title="MySQL / MariaDB"
CREATE TABLE FAILOVER_DASHBOARD_SNAPSHOT (
    INSTANCE_ID  VARCHAR(255) PRIMARY KEY,
    RECEIVED_AT  BIGINT       NOT NULL,
    SUMMARY_JSON LONGTEXT     NOT NULL
);
```

```sql title="Oracle"
CREATE TABLE FAILOVER_DASHBOARD_SNAPSHOT (
    INSTANCE_ID  VARCHAR2(255) PRIMARY KEY,
    RECEIVED_AT  NUMBER(19)    NOT NULL,
    SUMMARY_JSON CLOB          NOT NULL
);
```

```sql title="H2 / generic"
CREATE TABLE IF NOT EXISTS FAILOVER_DASHBOARD_SNAPSHOT (
    INSTANCE_ID  VARCHAR(255) PRIMARY KEY,
    RECEIVED_AT  BIGINT       NOT NULL,
    SUMMARY_JSON CLOB         NOT NULL
);
```

Prepend your `table-prefix` to the table name if you set one. One row per instance (upserted on each push); the dashboard reads only rows within the liveness window.

### Scenario E — Standalone dashboard (its own app)

Run the dashboard as its own small Spring Boot app pointed at a backend, so a cluster has **one** dashboard rather than one embedded per instance. The `@Failover` library is **not** on its classpath.

```yaml title="standalone dashboard app"
spring:
  application.name: failover-dashboard
failover:
  dashboard:
    enabled: true
    cluster:
      mode: prometheus          # or shared-store (then peers push to this app)
      prometheus:
        base-url: http://prometheus:9090
```

The app needs `spring-boot-starter-web`, `spring-boot-starter-security`, a `MeterRegistry`, and the `failover-dashboard` (or its starter) dependency. With no failover library there are no `@Failover` methods to discover, so the **Config view is empty** while all metrics/health/trend views work from the backend — a no-op `FailoverScanner` is supplied automatically. (For `shared-store` mode, also add `failover-dashboard-snapshotstore-jdbc` if you want durability.)

## Exporting Metrics Elsewhere (OTLP / Elastic)

The dashboard reads `failover.*` **meters**; how those meters leave each instance is a plain Micrometer concern — **no failover module is required**. Add the matching Micrometer registry to the application and the `failover.*` meters flow with everything else:

- **OTLP** (vendor-neutral → Prometheus / Elastic / Datadog / …): add `micrometer-registry-otlp`.
- **Elastic**: add `micrometer-registry-elastic`.

For these push-based backends, enable the `instance` tag so figures stay attributable (`failover.observable.instance.enabled=true`; see [Observability](observability.md)) — unlike a Prometheus scrape, there is no scrape-time `instance` label.

For a metrics **read** source over Elasticsearch (or a log drill-down view), implement the `MetricsSource` SPI in an optional `failover-dashboard-source-elastic` module gated by `@ConditionalOnClass`/`@ConditionalOnProperty` — the same extension pattern as the Prometheus and shared-store sources.

## Next Steps

- [Observability](observability.md) — the meters the dashboard consumes
- [Properties Reference](../configuration/properties-reference.md) — `failover.dashboard.*`
- [Security](../support/security.md) — data-minimisation and the access gate
