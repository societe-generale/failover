# Failover Dashboard — UX Design Reference

Input document for UI redesign. Covers every section, chart, table, metric definition, and the
complete API/JSON contract. Use this as the source of truth when rebuilding the frontend.

---

## 1. Purpose & Constraints

The dashboard is a **read-only, embedded observability UI** for the `failover` Spring Boot library.
It answers three questions at a glance:

1. **Are my failover points healthy?** (live vs recovered vs hard-fail rate)
2. **When upstream failed, did cached data save the caller?** (recovery effectiveness)
3. **What is configured, and is it running correctly?** (config + health)

**Design constraints that must survive any redesign:**

- No mutation — every endpoint is GET-only (POST `/cluster/snapshot` is internal peer push, not user-visible).
- No credential or payload data — config view shows types and flags only.
- Works offline — Chart.js is vendored; no CDN dependency.
- Degrades gracefully — config view works without Micrometer; metrics view hides when no `MeterRegistry` is present.
- Source provenance badge — never silently show single-instance numbers as cluster-wide.

---

## 2. Navigation Structure

Five tabs in a persistent top bar. Tab visibility rules:

| Tab | Always visible | Condition |
|---|---|---|
| **Overview** | Yes | — |
| **Per-API** | Yes | — |
| **Instances** | Conditional | hidden when `GET /api/instances` returns `[]` (local mode) |
| **Health** | Yes | — |
| **Config** | Yes | — |

Top-bar elements (left → right):

```
[F] FAILOVER dashboard   [src-badge]   [spacer]   [● status]   [tabs]   [last-updated] [live●] [interval▾] [⟳] [◐] [docs]
```

- **src-badge**: shows `local` / `prometheus` / `shared-store (N/M instances)` — warns when partial
- **status chip**: `UP` (green) / `DOWN` (red) / `—` (loading) derived from `/api/failover-health`
- **live pulse**: animated dot when auto-refresh is active
- **interval select**: off / 10s / 30s (default) / 1m / 10m / 1h
- **⟳**: force-refresh now
- **◐**: dark/light theme toggle

---

## 3. Section: Overview

**Purpose:** single-screen health pulse for an operator.

### 3.1 KPI Signal Strip

Full-width row of stat cards, one per top-level metric. Each card: large number + label + trend arrow.

| Card | Value | Source field |
|---|---|---|
| Total Calls | absolute | `overall.totalCalls` |
| Upstream Success | absolute | `overall.upstreamSuccess` |
| Failover Invoked | absolute | `overall.failoverInvoked` |
| Recovered | absolute | `overall.recovered` |
| Not Recovered | absolute | `overall.notRecovered` |
| Errors | absolute | `overall.errors` |
| Healthy Rate | `%` | `overall.rates.healthyRate × 100` |
| Recovery Rate | `%` | `overall.rates.recoveryRate × 100` |

### 3.2 Three Donut Charts (side by side)

#### Chart A — "Did the caller get a result?"
Segments: `upstreamSuccess` (green) · `recovered` (amber) · `notRecovered + errors` (red)

#### Chart B — "When failover fired, did it recover?"
Segments: `recovered` (green) · `partial` (amber) · `notRecovered` (orange) · `errors` (red)
Denominator: `failoverInvoked`

#### Chart C — "Stored vs Recovered"
Segments: `upstreamSuccess` (blue, stored live values) · `recovered` (amber, recovered from cache)

### 3.3 Timeline Trend Chart

Line chart over `GET /api/metrics/series`. X-axis: time. Y-axis (left): call volume. Y-axis (right): rates 0–100%.

Lines:
- Calls per tick (bars, secondary Y)
- Success rate %
- Failover rate %
- Recovery rate %
- Healthy rate %

Window controlled by `windowSec` query param (default 300s = last 5 minutes of retained ring).

### 3.4 Two Charts (side by side)

#### Chart D — "Why did upstream fail?" (horizontal bar)
X: count. Y: exception class name (short). Data: `topExceptions[*].type` + `.count`.
Top-N (e.g. 10) sorted descending.

#### Chart E — "Latency: store vs recover" (grouped bar)
X: `mean` / `p95` / `p99` / `max`. Two bar groups per x-tick: store (blue) and recover (amber).
Values from `overall.latency.*`. Skip p95/p99 bars when value is `0` (unavailable in shared-store mode).

---

## 4. Section: Per-API

**Purpose:** drill into individual `@Failover` points to find the unhealthy one.

### 4.1 Sortable Table

One row per `perApi[*]`. Clickable column headers sort ascending/descending.

| Column | Value | Sortable |
|---|---|---|
| Failover point | `name` | — |
| Calls | `totalCalls` | Yes |
| Healthy | badge chip (HEALTHY / DEGRADED / UNHEALTHY) from `/api/health` | Yes |
| Success % | `rates.successRate × 100` | Yes |
| Failover % | `rates.failoverRate × 100` | Yes |
| Recovery % | `rates.recoveryRate × 100` | Yes |
| Errors | `errors` | Yes |
| Failover trend | mini sparkline from `SeriesPoint.failoverByApi[name]` per tick | — |
| Status dot | ● green/amber/red | — |

### 4.2 Failover Trend — All APIs (multi-line)

One line per `@Failover` point. X: time ticks. Y: failover invocations per tick.
Data: `SeriesPoint.failoverByApi` — delta between consecutive points for each name.

### 4.3 Per-API Breakdown (grouped bar)

X: failover name. For each: 4 bars — totalCalls / upstreamSuccess / failoverInvoked / recovered.
Source: `perApi[*]`.

---

## 5. Section: Instances

**Purpose:** cluster health — "is it one bad node or all of them?"
Hidden when source mode is `local` (returns empty array).

### 5.1 Cluster Rollup Strip

Summary cards across all reporting instances:
- Reporting: `instancesReporting` / `instancesExpected`
- Partial flag: warning badge when `SourceInfo.partial = true`
- Data freshness: `SourceInfo.asOfEpochMs` displayed as relative time

### 5.2 Instance Table

One row per `InstanceMetrics`. Click a row to drill in.

| Column | Value |
|---|---|
| Instance | `instanceId` |
| Calls | `summary.overall.totalCalls` |
| Success | `summary.overall.upstreamSuccess` |
| Failover | `summary.overall.failoverInvoked` |
| Recovery % | `summary.overall.rates.recoveryRate × 100` |
| p95 recover | `summary.overall.latency.recoverP95Ms` ms (shows `—` when 0) |
| Last seen | relative time from `lastSeenEpochMs`; red when stale |
| Status | ● live (green) · ● silent (grey) based on liveness window |

### 5.3 Drill-Down KPI Panel

Shows the selected instance's own `summary.overall` KPIs as a stat strip — same layout as Overview §3.1
but labelled "this instance only". Header shows `instanceId`.

---

## 6. Section: Health

**Purpose:** actuator-style overall status + per-API health classifications.

### 6.1 Cluster Rollup (same as Instances §5.1)

### 6.2 Health Hero

Large status indicator:

```
  ●  Failover subsystem
     UP  (or DOWN)
     N failover points registered
```

Colour: green (UP) / red (DOWN). Source: `GET /api/failover-health`.

### 6.3 Active Configuration Grid

Key–value cards for the `FailoverHealth.details` map:
`enabled`, `type`, `store.type`, `store.async`, `exception-policy`, etc.
Labels are the map keys; values displayed as-is. Types and flags only — no connection strings.

### 6.4 Per-API Health List (from `/api/health`)

List of `ApiHealth` entries: name + status chip + healthy-rate bar.

| Name | Status | Healthy rate bar |
|---|---|---|
| country-service | HEALTHY | ████████████ 99.8% |
| exchange-rate | DEGRADED | ████████░░░░ 93.1% |
| ratings | UNHEALTHY | ████░░░░░░░░ 72.4% |

Thresholds from `DashboardProperties.Health`: `degradedThreshold=0.99`, `unhealthyThreshold=0.90`.

---

## 7. Section: Config

**Purpose:** show what `@Failover` methods are registered and how they are configured.

### 7.1 Filter Bar

Free-text search across name / domain / storeType. Client-side filter.

### 7.2 Configuration Table

One row per `ConfigEntry` (= one `@Failover` annotation).

| Column | Field |
|---|---|
| Name | `name` |
| Domain | `domain` |
| Expiry | `expiryDuration expiryUnit` (e.g. `3 DAYS`) |
| Store | `storeType` |
| Execution | `executionType` |
| Recover-all | `recoverAll` (Yes / No chip) |
| Splitter | `payloadSplitter` (`default` when blank) |
| Key gen | `keyGenerator` (`default` when blank) |
| Expiry policy | `expiryPolicy` (`default` when blank) |

Global fields (`storeType`, `executionType`, `exceptionPolicy`, `asyncStore`) are the same for every
row — they come from the framework config, not per-annotation. Display them once in the global
settings section below.

### 7.3 Global Settings Grid

Key–value cards from `GET /api/config/settings`. Grouped by section (failover.* / failover.dashboard.*).
Example groups: `Core`, `Store`, `Dashboard`, `Cluster`.

---

## 8. API Contract — Full JSON Samples

Base path default: `/failover-dashboard`. All responses are `application/json`.

### 8.1 `GET /api/metrics` → MetricsSummary

```json
{
  "overall": {
    "name": "__overall__",
    "domain": "__overall__",
    "totalCalls": 42000,
    "upstreamSuccess": 40800,
    "failoverInvoked": 1200,
    "recovered": 1140,
    "notRecovered": 48,
    "errors": 12,
    "partial": 30,
    "asyncFailed": 0,
    "latency": {
      "storeMeanMs": 0.82,
      "storeMaxMs": 12.4,
      "recoverMeanMs": 0.31,
      "recoverMaxMs": 4.7,
      "storeP95Ms": 1.9,
      "storeP99Ms": 3.8,
      "recoverP95Ms": 0.71,
      "recoverP99Ms": 1.2
    },
    "rates": {
      "successRate": 0.9714,
      "failoverRate": 0.0286,
      "recoveryRate": 0.9500,
      "nonRecoveryRate": 0.0500,
      "healthyRate": 0.9986
    }
  },
  "perApi": [
    {
      "name": "country-service",
      "domain": "country",
      "totalCalls": 20000,
      "upstreamSuccess": 19600,
      "failoverInvoked": 400,
      "recovered": 395,
      "notRecovered": 4,
      "errors": 1,
      "partial": 10,
      "asyncFailed": 0,
      "latency": {
        "storeMeanMs": 0.75,
        "storeMaxMs": 9.1,
        "recoverMeanMs": 0.28,
        "recoverMaxMs": 3.2,
        "storeP95Ms": 1.6,
        "storeP99Ms": 3.1,
        "recoverP95Ms": 0.60,
        "recoverP99Ms": 0.98
      },
      "rates": {
        "successRate": 0.9800,
        "failoverRate": 0.0200,
        "recoveryRate": 0.9875,
        "nonRecoveryRate": 0.0125,
        "healthyRate": 0.9998
      }
    },
    {
      "name": "exchange-rate",
      "domain": "exchange-rate",
      "totalCalls": 15000,
      "upstreamSuccess": 14000,
      "failoverInvoked": 1000,
      "recovered": 930,
      "notRecovered": 60,
      "errors": 10,
      "partial": 20,
      "asyncFailed": 0,
      "latency": {
        "storeMeanMs": 0.91,
        "storeMaxMs": 15.2,
        "recoverMeanMs": 0.35,
        "recoverMaxMs": 6.1,
        "storeP95Ms": 2.1,
        "storeP99Ms": 4.8,
        "recoverP95Ms": 0.82,
        "recoverP99Ms": 1.5
      },
      "rates": {
        "successRate": 0.9333,
        "failoverRate": 0.0667,
        "recoveryRate": 0.9300,
        "nonRecoveryRate": 0.0700,
        "healthyRate": 0.9953
      }
    }
  ],
  "topExceptions": [
    { "type": "java.net.SocketTimeoutException", "count": 850 },
    { "type": "org.springframework.web.client.ResourceAccessException", "count": 280 },
    { "type": "java.net.ConnectException", "count": 70 }
  ],
  "timestamp": 1751020800000
}
```

### 8.2 `GET /api/health` → List\<ApiHealth\>

```json
[
  { "name": "country-service",  "status": "HEALTHY",   "healthyRate": 0.9998 },
  { "name": "exchange-rate",    "status": "DEGRADED",  "healthyRate": 0.9953 },
  { "name": "ratings",          "status": "UNHEALTHY", "healthyRate": 0.7240 }
]
```

Status rules (configurable via `failover.dashboard.health`):
- `healthyRate >= degradedThreshold (0.99)` → `HEALTHY`
- `healthyRate >= unhealthyThreshold (0.90)` → `DEGRADED`
- `healthyRate < unhealthyThreshold` → `UNHEALTHY`

### 8.3 `GET /api/metrics/source` → SourceInfo

```json
{
  "mode": "shared-store",
  "instancesReporting": 3,
  "instancesExpected": 4,
  "asOfEpochMs": 1751020785000,
  "partial": true
}
```

Mode values: `local` | `prometheus` | `shared-store`

Local mode example:
```json
{
  "mode": "local",
  "instancesReporting": 1,
  "instancesExpected": -1,
  "asOfEpochMs": 1751020800000,
  "partial": false
}
```

### 8.4 `GET /api/metrics/series?windowSec=300` → List\<SeriesPoint\>

```json
[
  {
    "timestamp": 1751020500000,
    "calls": 41800,
    "failover": 1180,
    "recovered": 1122,
    "notRecovered": 46,
    "store": 40620,
    "recover": 1180,
    "failoverByApi": {
      "country-service": 390,
      "exchange-rate": 790
    }
  },
  {
    "timestamp": 1751020515000,
    "calls": 41830,
    "failover": 1185,
    "recovered": 1126,
    "notRecovered": 47,
    "store": 40645,
    "recover": 1185,
    "failoverByApi": {
      "country-service": 392,
      "exchange-rate": 793
    }
  },
  {
    "timestamp": 1751020530000,
    "calls": 41900,
    "failover": 1190,
    "recovered": 1131,
    "notRecovered": 47,
    "store": 40710,
    "recover": 1190,
    "failoverByApi": {
      "country-service": 396,
      "exchange-rate": 794
    }
  }
]
```

Note: values are **cumulative totals**. The UI computes deltas between consecutive ticks to get
"per-tick" rates for the trend chart (e.g. `tick[n].failover - tick[n-1].failover`).

### 8.5 `GET /api/instances` → List\<InstanceMetrics\>

Empty list when `mode=local`. Returns one entry per live cluster peer in `prometheus` / `shared-store` mode.

```json
[
  {
    "instanceId": "orders-svc:host-1",
    "lastSeenEpochMs": 1751020795000,
    "summary": {
      "overall": {
        "name": "__overall__",
        "domain": "__overall__",
        "totalCalls": 14000,
        "upstreamSuccess": 13600,
        "failoverInvoked": 400,
        "recovered": 380,
        "notRecovered": 16,
        "errors": 4,
        "partial": 10,
        "asyncFailed": 0,
        "latency": {
          "storeMeanMs": 0.78,
          "storeMaxMs": 10.2,
          "recoverMeanMs": 0.29,
          "recoverMaxMs": 3.9,
          "storeP95Ms": 0,
          "storeP99Ms": 0,
          "recoverP95Ms": 0,
          "recoverP99Ms": 0
        },
        "rates": {
          "successRate": 0.9714,
          "failoverRate": 0.0286,
          "recoveryRate": 0.9500,
          "nonRecoveryRate": 0.0500,
          "healthyRate": 0.9986
        }
      },
      "perApi": [ ],
      "topExceptions": [
        { "type": "java.net.SocketTimeoutException", "count": 290 }
      ],
      "timestamp": 1751020795000
    }
  },
  {
    "instanceId": "orders-svc:host-2",
    "lastSeenEpochMs": 1751020790000,
    "summary": {
      "overall": {
        "name": "__overall__",
        "domain": "__overall__",
        "totalCalls": 14200,
        "upstreamSuccess": 13800,
        "failoverInvoked": 400,
        "recovered": 382,
        "notRecovered": 14,
        "errors": 4,
        "partial": 10,
        "asyncFailed": 0,
        "latency": {
          "storeMeanMs": 0.83,
          "storeMaxMs": 11.8,
          "recoverMeanMs": 0.33,
          "recoverMaxMs": 4.1,
          "storeP95Ms": 0,
          "storeP99Ms": 0,
          "recoverP95Ms": 0,
          "recoverP99Ms": 0
        },
        "rates": {
          "successRate": 0.9718,
          "failoverRate": 0.0282,
          "recoveryRate": 0.9550,
          "nonRecoveryRate": 0.0450,
          "healthyRate": 0.9987
        }
      },
      "perApi": [ ],
      "topExceptions": [ ],
      "timestamp": 1751020790000
    }
  }
]
```

Note: percentile fields (`storeP95Ms` etc.) are `0` in `shared-store` mode — per-instance percentiles
cannot be meaningfully merged. The UI should hide or grey-out those cells when `0`.

### 8.6 `GET /api/config` → List\<ConfigEntry\>

```json
[
  {
    "name": "country-service",
    "domain": "country",
    "expiryDuration": 3,
    "expiryUnit": "DAYS",
    "recoverAll": false,
    "payloadSplitter": "default",
    "keyGenerator": "default",
    "expiryPolicy": "default",
    "storeType": "jdbc",
    "executionType": "basic",
    "exceptionPolicy": "rethrow",
    "asyncStore": true
  },
  {
    "name": "exchange-rate",
    "domain": "exchange-rate",
    "expiryDuration": 1,
    "expiryUnit": "HOURS",
    "recoverAll": true,
    "payloadSplitter": "currencyPairSplitter",
    "keyGenerator": "default",
    "expiryPolicy": "default",
    "storeType": "jdbc",
    "executionType": "basic",
    "exceptionPolicy": "rethrow",
    "asyncStore": true
  },
  {
    "name": "ratings",
    "domain": "ratings",
    "expiryDuration": 7,
    "expiryUnit": "DAYS",
    "recoverAll": false,
    "payloadSplitter": "default",
    "keyGenerator": "ratingKeyGenerator",
    "expiryPolicy": "default",
    "storeType": "jdbc",
    "executionType": "resilience",
    "exceptionPolicy": "never_throw",
    "asyncStore": true
  }
]
```

### 8.7 `GET /api/config/settings` → Map\<String, Map\<String, String\>\>

```json
{
  "Core": {
    "failover.enabled": "true",
    "failover.type": "basic",
    "failover.exception-policy": "rethrow"
  },
  "Store": {
    "failover.store.type": "jdbc",
    "failover.store.async": "true",
    "failover.store.jdbc.table-prefix": ""
  },
  "Dashboard": {
    "failover.dashboard.enabled": "true",
    "failover.dashboard.base-path": "/failover-dashboard",
    "failover.dashboard.history.enabled": "true",
    "failover.dashboard.history.samples": "120",
    "failover.dashboard.health.degraded-threshold": "0.99",
    "failover.dashboard.health.unhealthy-threshold": "0.90"
  },
  "Cluster": {
    "failover.dashboard.cluster.mode": "shared-store",
    "failover.dashboard.cluster.shared-store.liveness-seconds": "45",
    "failover.dashboard.cluster.shared-store.max-instances": "10"
  }
}
```

### 8.8 `GET /api/failover-health` → FailoverHealth

```json
{
  "status": "UP",
  "details": {
    "enabled": "true",
    "type": "basic",
    "store.type": "jdbc",
    "store.async": "true",
    "exception-policy": "rethrow",
    "registeredFailoverPoints": "3"
  }
}
```

Status is `DOWN` when `FailoverScanner` finds zero registered `@Failover` points (misconfiguration signal).

### 8.9 `POST /api/cluster/snapshot` (internal peer push, shared-store mode only)

Body is a `ClusterSnapshot` pushed by each peer on schedule (default every 15s). Not user-facing.
The dashboard UI never calls this endpoint — it is the ingest side for shared-store aggregation.

---

## 9. Metric Definitions & Formulas

All counters come from Micrometer `failover.*` meters. No payload, key, or argument data is ever
included.

| Metric | Micrometer counter | Notes |
|---|---|---|
| `upstreamSuccess` | `failover.store.total{stored=true}` | upstream live; value stored |
| `failoverInvoked` | sum of `recovered + notRecovered + errors` | upstream failed; failover path entered |
| `recovered` | `failover.recovery.outcome.total{outcome=recovered}` | full recovery; caller got cached value |
| `notRecovered` | `failover.recovery.outcome.total{outcome=not_recovered}` | cache miss or expired |
| `errors` | `failover.recovery.outcome.total{outcome=error}` | exception during recovery path |
| `partial` | `failover.recovery.partial.total` | scatter/gather: partial slice recovery |
| `asyncFailed` | `failover.store.async.failed` | async write dropped in executor |
| `latency.*` | `failover.operation.duration` timer | split by `operation=store\|recover` tag |
| `topExceptions` | `failover.exception.total{exception_type=…}` | exception class tag |

### Derived Rates

All rates are fractions in `[0.0, 1.0]`. Return `0.0` (never `NaN`) when denominator is zero.

```
totalCalls     = upstreamSuccess + failoverInvoked

successRate    = upstreamSuccess / totalCalls
failoverRate   = failoverInvoked / totalCalls
recoveryRate   = recovered / failoverInvoked
nonRecoveryRate = (notRecovered + errors) / failoverInvoked
healthyRate    = (upstreamSuccess + recovered) / totalCalls
```

### Health Classification

Applied per `@Failover` point using `healthyRate`:

```
rate >= degradedThreshold  (default 0.99)  → HEALTHY
rate >= unhealthyThreshold (default 0.90)  → DEGRADED
rate <  unhealthyThreshold                 → UNHEALTHY
```

---

## 10. Cluster / Source Modes

| Mode | Description | `instancesExpected` | Instances tab |
|---|---|---|---|
| `local` | reads this JVM's in-process `MeterRegistry` only | `-1` (unknown) | hidden |
| `prometheus` | queries Prometheus HTTP API; aggregates all `instance` label values | populated | shown |
| `shared-store` | peers push `MetricsSummary` snapshots every 15s; dashboard aggregates in memory | populated | shown |

**Source badge UI guidance:**
- `local` → show `"this instance only"` warning chip
- `shared-store`, `partial=true` → show `"N/M instances"` amber chip
- `shared-store`, `partial=false` → show `"N instances"` green chip

---

## 11. Layout Sketch

```
┌──────────────────────────────────────────────────────────────────────┐
│ [F] FAILOVER dashboard  [local]           [● UP]  Overview Per-API Health Config │
│                                                   [10:32:01] ●live [30s▾][⟳][◐] │
├──────────────────────────────────────────────────────────────────────┤
│ SIGNALS                                          since process start  │
│ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐  │
│ │ 42,000 │ │ 40,800 │ │  1,200 │ │  1,140 │ │   99.9%│ │   95.0%│  │
│ │ Calls  │ │Success │ │Failover│ │Recover │ │Healthy │ │Rec.Rate│  │
│ └────────┘ └────────┘ └────────┘ └────────┘ └────────┘ └────────┘  │
│                                                                       │
│ ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐       │
│ │  Did caller get  │ │  Recovery depth  │ │  Store vs Recall │       │
│ │  a result? 🍩    │ │  when fired? 🍩  │ │  balance 🍩      │       │
│ └──────────────────┘ └──────────────────┘ └──────────────────┘       │
│                                                                       │
│ TREND                                                                 │
│ ┌─────────────────────────────────────────────────────────────────┐  │
│ │  ▁▂▃▄▄▃▂▁▂▃▄▃   timeline: calls + rate lines                  │  │
│ └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│ ┌──────────────────────────┐  ┌──────────────────────────┐          │
│ │  Why upstream failed?    │  │  Store vs Recover latency │          │
│ │  ━━ SocketTimeout  850   │  │  mean/p95/p99/max (bars) │          │
│ │  ━ ResourceAccess  280   │  │                           │          │
│ └──────────────────────────┘  └──────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 12. Chart Library

Current implementation uses **Chart.js** (vendored UMD build at `vendor/chart.umd.min.js`).
The redesign may swap this for any library that:
- works offline (no CDN required)
- supports canvas or SVG
- handles dark/light theme via CSS variables

---

## 13. Theme System

Two themes: `dark` (default) and `light`, toggled by the `◐` button. Applied via `data-theme="dark|light"`
on `<html>`. All colours should use CSS custom properties so charts and components respond to the toggle
without JavaScript re-render.

Suggested CSS variable names for the redesign:

```css
--bg          /* page background */
--surface     /* card background */
--border      /* card border */
--text        /* primary text */
--text-muted  /* secondary/hint text */
--accent      /* primary accent (blue) */
--success     /* green — upstream success, HEALTHY */
--warn        /* amber — recovered / DEGRADED */
--danger      /* red — not recovered / UNHEALTHY / DOWN */
--neutral     /* grey — partial, n/a */
```

---

## 14. Auto-Refresh Behaviour

- Default interval: 30s
- On each tick: `GET /api/metrics`, `/api/health`, `/api/metrics/series`, `/api/instances` (if tab visible)
- Config and failover-health data (`/api/config`, `/api/failover-health`) load once on page load; re-fetch only on manual refresh.
- `last-updated` timestamp updates on every successful batch.
- Error state: show banner with error message; do not clear existing data.

---

## 15. Key Design Signals for Redesign

- **Healthy rate is the top-line number** — operators need it above the fold.
- **Recovery rate is the second signal** — when failover fires, does it actually help?
- **Not-recovered is the danger signal** — callers got an error or null.
- **Exception breakdown tells operators what to fix** — timeout? connection? auth?
- **Latency context** — store path in success flow (write after upstream); recover path in failure flow (read-only lookup). Both should be sub-millisecond at steady state.
- **Cluster view** answers "is the whole fleet degraded or one bad pod?" — critical for on-call.
- **Config view** is for developers confirming annotation wiring — expiry, splitter, key generator.
