---
icon: material/server-network
status: implemented
---

# Design Proposal — Distributed (Multi-Instance) Failover Dashboard

> **Status: IMPLEMENTED (P0–P2 shipped, P3 docs mostly done).** All three cluster modes are live —
> `local` (default), `prometheus` (Option A), and `shared-store` (Option C, in-memory **and** JDBC). The
> per-instance drill-down (open question #4) shipped as the **Instances tab**. Option B (in-app
> scatter-gather) remains intentionally **not built**. See the per-phase status in §8 and the pending list
> in §11. Implementation notes where the build diverged from this proposal are flagged inline as
> **[as-built]**.

---

## 1. Problem

Today every dashboard **metric** signal is derived from **in-process state**, so it reflects only the
single JVM that happened to serve the HTTP request.

| Signal | Source (today) | Scope |
|---|---|---|
| KPIs / rates (`/api/metrics`) | `DashboardMetricsService` → local Micrometer `MeterRegistry` | this instance, since its process start |
| Trend history (`/api/metrics/series`) | `DashboardHistoryService` in-memory ring buffer | this instance |
| Per-API health (`/api/health`) | derived from the local registry | this instance |
| Config (`/api/config`, `/api/config/settings`) | `FailoverScanner` + `Environment` | this instance — but **identical** on every node |
| Failover health (`/api/failover-health`) | `FailoverScanner` | this instance — **identical** on every node |

**Consequence behind a load balancer:**

- `GET /api/metrics` lands on a **random** instance → headline numbers and rates **flip between refreshes**.
- The trend is **partial** (one instance's slice of traffic).
- Counters **reset on restart / scale-in / scale-out**, so totals are not stable cluster-wide.

**Not affected:** the **Config** and **Failover-health** views are annotation/`Environment`-derived and are the
**same on every instance**, so any node is representative. Only the **metrics + history** surfaces need
cluster aggregation.

```
                 ┌────────── load balancer ──────────┐
   browser ─────►│  /failover-dashboard/api/metrics   │
                 └───────┬─────────────┬──────────────┘
                         ▼             ▼
                   instance A     instance B      ← each has its OWN MeterRegistry + history ring
                  (40% traffic)  (60% traffic)    ← /api/metrics shows only the one that answered
```

---

## 2. Goals & Non-Goals

**Goals**

- A **cluster-wide aggregated** metrics + trend view, stable regardless of which instance answers.
- **Zero behaviour change for single-instance / dev** — the current in-process path stays the default.
- **No silent misreading**: if aggregation is not configured, the UI must clearly say "this instance only".
- Reuse what the app already has (Micrometer export, the JDBC datasource) before adding new infrastructure.

**Non-Goals**

- Not a general-purpose TSDB or APM. For long-term, high-cardinality, cross-service analysis the answer
  remains Prometheus/Grafana over the existing `failover.*` meters.
- No new instrumentation — still a pure **consumer** of the existing `failover.*` counters.
- No payload/PII exposure — aggregate counts only, unchanged from the single-instance design (§9).

---

## 3. Core design — the `MetricsSource` seam

Decouple the controllers from "read the local registry". One interface, selectable implementations:

```java
interface MetricsSource {
    MetricsSummary summary();                 // cluster (or local) aggregate
    List<ApiHealth> health();
    List<SeriesPoint> series(long windowSec);  // distributed trend, when supported
    SourceInfo info();                        // mode, instances seen, freshness, partial?
}

record SourceInfo(
    String mode,            // "local" | "prometheus" | "shared-store"
    int instancesReporting, // how many instances contributed
    int instancesExpected,  // best-known total (or -1 if unknown)
    long asOfEpochMs,       // freshness of the underlying data
    boolean partial) {}     // some instances missing / stale
```

`DashboardMetricsController` and `DashboardHistoryController` call `MetricsSource`, never the registry
directly. The implementation is chosen by one property:

```yaml
failover:
  dashboard:
    cluster:
      mode: local        # local | prometheus | shared-store  (default: local)
```

`SourceInfo` is returned to the UI so it can render a **badge** — `THIS INSTANCE ONLY` (local + clustered
deployment) or `CLUSTER · M/N instances · as of HH:MM:SS` — and a "partial data" warning. **Honesty by
construction:** cluster numbers can never be silently misread as single-instance, or vice-versa.

---

## 4. Option A — delegate to the metrics backend (Prometheus/TSDB) — **recommended**

The `failover.*` meters are already exported per instance (each carrying an `instance` label). For a true
cluster view, aggregate **where the data already lands** — the TSDB — instead of rebuilding aggregation
in-app.

`PrometheusMetricsSource` queries the Prometheus HTTP API with PromQL:

```promql
# per-API outcome totals across all instances
sum by (name, outcome) (increase(failover_recovery_outcome_total[$window]))
# upstream successes (stored)
sum by (name)          (increase(failover_store_total{stored="true"}[$window]))
```

- `/api/metrics/series` is backed by `query_range` → **real cross-instance, retention-surviving trend for free**.
- `increase()` / `rate()` **handle counter resets on restart natively** — no double-count, no dip logic to build.

```yaml
failover:
  dashboard:
    cluster:
      mode: prometheus
      prometheus:
        base-url: http://prometheus:9090
        # auth via standard bearer/secret header; timeout, step configurable
```

| Pros | Cons |
|---|---|
| Accurate; scales to N instances | Needs a Prometheus-compatible backend |
| History + retention for free | "batteries-included" promise now has one dependency for cluster mode |
| Counter-reset handling built in (`increase`) | PromQL/labels must match the exported meter names |
| No new write path or storage in the app | |

Aligns with the dashboard's stated non-goal ("not a replacement for Grafana/Prometheus") — for a cluster,
the **source of truth is external**, and the embedded UI becomes a convenient, secured lens over it.

---

## 5. Option B — in-app scatter-gather across peers

The serving instance discovers its peers and fans out to their internal `/api/metrics`, then sums.

- **Discovery:** k8s headless-service DNS, Spring Cloud (Eureka/Consul), or a static peer list.
- **Aggregation:** sum counters by `name`; merge `topExceptions`; count-weight latency.

| Pros | Cons |
|---|---|
| No external TSDB; near real-time | **N×N fan-out** on every refresh |
| Self-contained-ish | Needs discovery + inter-instance authn/z |
| | Partial-failure handling (a peer down ⇒ partial totals) |
| | **History still per-instance** — each peer's ring is local, so the trend is *not* solved |
| | Counters still reset on restart |

Most moving parts, least incremental payoff (history unsolved). **Not recommended** as the primary path;
keep as a fallback only for environments that can run neither a TSDB nor a shared store.

---

## 6. Option C — shared snapshot store (built-in, no Prometheus)

For teams without a TSDB who still want a cluster view out of the box.

Each instance periodically **pushes its counter snapshot** — keyed by `instance_id` + `timestamp` — into a
shared store (**reuse the existing failover JDBC datasource**, or Redis). The dashboard reads the store and
**sums the latest snapshot of each _live_ instance** → cluster KPIs; the retained time-series rows give the
**distributed, restart-surviving history**.

```yaml
failover:
  dashboard:
    cluster:
      mode: shared-store
      shared-store:
        type: jdbc                 # reuse the failover JDBC datasource
        instance-id: ${HOSTNAME}   # stable per-instance identity
        sample-interval-seconds: 15
        retention-minutes: 60
        liveness-timeout-seconds: 60   # snapshots older than this ⇒ instance treated as dead
```

**Schema** — one row per instance per sample (per-API granularity):

```
failover_dashboard_samples
  instance_id   | ts        | name
  total_calls   | upstream_success | failover_invoked | recovered | not_recovered | errors | partial | async_failed
  store_ms_sum  | store_ms_cnt     | recover_ms_sum   | recover_ms_cnt
  PRIMARY KEY (instance_id, ts, name)
```

**Aggregation rules**

- **Cluster KPI** for a metric = `Σ (latest snapshot per _live_ instance)`. A snapshot older than
  `liveness-timeout-seconds` is excluded, so a crashed instance stops inflating totals.
- **Latency** = count-weighted mean: `Σ store_ms_sum / Σ store_ms_cnt` (exact — never an average of averages).
- **Trend** = bucket rows by `ts` and apply the same sum across instances per bucket.
- **Restart handling:** a restarted instance's cumulative counters reset to 0 → the sum dips for one
  interval. Mitigate by (a) a short grace window carrying its last-known snapshot, or (b) accept and
  document the one-interval dip.
- **Retention:** a scheduled purge deletes rows older than `retention-minutes`.

| Pros | Cons |
|---|---|
| Cluster view **and** restart/reload-surviving history with **no external infra** | New write path + table + retention/eviction to maintain |
| Reuses the JDBC datasource the app likely already has | Eventual consistency — one sample-interval lag |
| Works behind any load balancer | It is effectively a **mini-TSDB** — partial reinvention of Prometheus |

Keep scope deliberately small (short retention, low cardinality) so this stays a convenience, not a TSDB —
and point teams needing long-term analysis at Prometheus/Grafana (Option A).

---

## 7. Comparison & recommendation

| Criterion | A · Prometheus | B · Scatter-gather | C · Shared store |
|---|---|---|---|
| Cluster-correct KPIs | ✅ | ✅ (if all peers up) | ✅ |
| Distributed **history** | ✅ free | ❌ still per-instance | ✅ |
| Counter-reset safe | ✅ (`increase`) | ❌ | ⚠ one-interval dip |
| New infra required | TSDB (often already present) | discovery | a shared DB (often already present) |
| Per-refresh cost | 1 query | N×N fan-out | 1 read |
| Implementation effort | Medium | High | Medium-High |

**Recommendation**

1. **Build the `MetricsSource` seam first** (`mode: local` stays the default) — small, unlocks everything, zero behaviour change.
2. **Ship `prometheus` mode** as the recommended cluster path — correct, cheap, history for free.
3. **Ship `shared-store` (JDBC)** for the batteries-included crowd without a TSDB.
4. **Defer Option B** unless a customer can run neither a TSDB nor a shared store.
5. **Honesty guard always on:** surface `SourceInfo` in the UI — a `THIS INSTANCE ONLY` badge in `local`
   mode and a `CLUSTER · M/N instances · as of …` badge otherwise, plus a "partial data" warning.

---

## 8. Implementation plan

| Phase | Deliverable | Risk |
|---|---|---|
| **P0 — Seam** ✅ **done** | `MetricsSource` + `SourceInfo`; `LocalRegistryMetricsSource` (no behaviour change); `cluster.mode` property (default `local`, non-local warns + stays local); `GET /api/metrics/source`; UI "this instance only / cluster" badge. Unit + slice + autoconfig tests. | Low |
| **P1 — Prometheus** ✅ **done** | `PrometheusMetricsSource` + `PrometheusClient` (RestClient + PromQL). `summary` / `health` / `info` via `/api/v1/query` summed across instances (`sum by (name) (...)`), cluster instance count, count-weighted latency. **`series` via `/api/v1/query_range`** — cluster-wide, reload-surviving trend incl. per-API failover; routed through the seam (`MetricsSource.series`, served by `DashboardMetricsController`; `DashboardHistoryController` removed, local ring kept as the local-mode source). Bearer-token auth + timeout; graceful fallback to `local` on any failure. Reusable `DashboardKpis` math shared with the local source. Tests: `PrometheusClientTest` (MockRestServiceServer: vector + matrix), `PrometheusMetricsSourceTest` (mock client: summary/health/info/series + fallbacks), controller + autoconfig wiring. | Medium |
| **P2 — Shared store** ✅ **done** | `SnapshotStore` SPI + **`SnapshotStoreInmemory`** (default) and **`SnapshotStoreJdbc`** (optional module `failover-dashboard-snapshotstore-jdbc`); peer-push ingest (`ClusterSnapshotPublisher` → `POST /api/cluster/snapshot` → `ClusterSnapshotController`); `SharedStoreMetricsSource` aggregation (sum-latest-per-**live** instance, count-weighted latency); liveness window + `max-instances` guard; **reset-aware** cluster trend (`ClusterSeriesStore` + `ClusterSeriesSampler`) with age+size **`RetentionPolicy`**; **per-instance Instances tab** (`MetricsSource.instances()`). Tests on H2 (multiple instances, restart/reset, stale eviction, table-prefix). **[as-built]** see notes below. | Medium-High |
| **P3 — Docs & ADR** 🟢 **mostly done** | Module docs updated (`docs/modules/dashboard.md`: scenarios A–E, Instances/Health views, endpoints, screenshots); properties reference + security note covered. ⏳ A standalone ADR file is not yet split out (the two proposal docs + module docs serve that role). | Low |

**[as-built] divergences from the P2/Option-C sketch above**

- **Snapshot shape** — instead of the wide per-API `failover_dashboard_samples` table, each instance pushes its whole `MetricsSummary` snapshot; the store keeps **one latest row per instance** (`INSTANCE_ID PK, RECEIVED_AT, SUMMARY_JSON`). Simpler, same aggregation result; the cluster **trend** is a separate in-memory `ClusterSeriesStore` ring fed by a sampler (not DB time-series rows).
- **Naming** — `SnapshotStoreInmemory` / `SnapshotStoreJdbc` (mirrors `FailoverStoreInmemory`/`FailoverStoreJdbc`).
- **JDBC table** — `table-prefix` strategy (validated) + base `FAILOVER_DASHBOARD_SNAPSHOT`; per-dialect DDL documented. **No multi-tenancy** — snapshots are aggregate, non-sensitive metrics only, so one shared table suffices.
- **Restart/reset dip** — solved by a **reset-aware monotonic** sampler (raw < last ⇒ count the fresh value), so the cluster trend never dips on a peer restart — better than the "accept the one-interval dip" option floated in §6.
- **Push model** — peers POST snapshots to the dashboard (no shared-DB write contention assumption); JDBC store is for durability of the received snapshots.

**Cross-cutting concerns**

- **Security (§9):** Prometheus credentials, peer endpoints, and the shared store are all accessed
  **server-side** and gated by the existing dashboard access control — no new anonymous surface. Internal
  peer endpoints (Option B) must require the dashboard role.
- **Data-minimisation:** aggregate counts only; never payload, keys, or connection strings — unchanged.
- **Counter-reset semantics:** Prometheus `increase()` (A); sum-latest-per-live-instance with liveness
  eviction (C); inherent in fan-out (B).
- **Latency aggregation:** always count-weighted (`Σ sum / Σ cnt`), never an average of per-instance means.
- **Backward compatibility:** `mode: local` is the default; existing single-instance deployments are
  unaffected and need no configuration.

---

## 9. Open questions — resolved

1. ✅ `prometheus` mode takes an **explicit `base-url`** (+ optional bearer token, timeout); no assumption about scraping. Per-instance views use the scrape-time `instance` label.
2. ✅ Shared-store: **JDBC** shipped (optional module, reuses the failover datasource conventions); the `SnapshotStore` SPI is `@ConditionalOnMissingBean` so **Redis/Hazelcast** can be added later without touching the source or UI.
3. ✅ Restart/reset: solved with a **reset-aware monotonic** trend sampler — no dip, no grace-window hack needed.
4. ✅ **Per-instance drill-down shipped** — the **Instances tab** (roll-up + table + per-instance KPIs) for both `shared-store` and `prometheus`; hidden in `local`.

---

## 10. Pending work

- **Option B (in-app scatter-gather)** — intentionally **not built** (history unsolved, N×N fan-out). Kept only as a documented fallback; build only if a deployment can run neither a TSDB nor a shared store.
- **Redis/Hazelcast `SnapshotStore`** — not built; the SPI is ready for it (`@ConditionalOnMissingBean`).
- **Standalone ADR file** — not split out yet; this doc + `redesign-observability-dashboard-proposal.md` + `docs/modules/dashboard.md` cover the decisions.
- **Elasticsearch read source + log drill-down** — deferred (P4c in the redesign proposal); needs an ES client + live ES to test.
- **Per-instance Prometheus *trend*** — the Instances tab shows per-instance KPIs; a per-instance time-series chart is not yet added.

---

## 11. TL;DR

In-process state is correct for a **single instance** but misleading behind a load balancer. Introduce a
pluggable **`MetricsSource`** (default `local`, unchanged). For clusters, **aggregate from an external
source of truth**: **Prometheus** (recommended — accurate, history + reset-handling for free) or a built-in
**shared JDBC snapshot store** (batteries-included, no TSDB). Skip in-app scatter-gather unless neither is
available. Always surface a **`SourceInfo` badge** so single-instance and cluster numbers are never
confused.
