# Design & Implementation Plan — `failover-dashboard`

> A zero-config, self-contained observability dashboard that ships ready-to-use the moment a consumer
> adds the dependency — exactly like `springdoc-openapi` / Swagger UI. Drop the jar on the classpath,
> start the app, open `/failover-dashboard`, and see every `@Failover` configuration plus live health
> metrics rendered as cards and charts.

---

## 1. Goals & Non-Goals

**Goals**
- **Zero configuration.** Adding the dependency is enough; sensible secure defaults, opt-out via one property.
- **Self-contained UI.** Static HTML/JS/CSS served from the jar (no external CDN, no build step for the consumer).
- **Two surfaces:**
  1. **Configuration view** — every `@Failover` point and its settings.
  2. **Metrics view** — KPI cards + charts derived from the metrics the framework *already* publishes.
- **No new instrumentation.** Read what exists: `FailoverScanner` (config) + Micrometer `MeterRegistry` (metrics). The dashboard is a *consumer* of existing signals, never a new source of truth.

**Non-Goals**
- Not a replacement for Grafana/Prometheus — it is an at-a-glance, embedded panel, not a long-term TSDB.
- No persistence of history beyond an optional small in-memory ring buffer (process-local, lost on restart).
- No alerting/notification engine (out of scope; the Micrometer health indicator already exists).

---

## 1a. Opt-In & Security Requirements (NON-NEGOTIABLE)

These override any convenience default elsewhere in this document. The dashboard is **strictly opt-in and
secure-by-default** — it must be impossible to expose any data by accident.

1. **The default failover setup must NOT ship the dashboard module, its UI, or its dependency.**
   A plain consumer of `failover-spring-boot-starter` gets **zero** dashboard code on the classpath. The
   default starter does **not** declare `failover-dashboard` even as an optional dependency.

2. **A separate starter is required to obtain the dashboard.** Consumers who want it add a dedicated
   artifact — **`failover-dashboard-spring-boot-starter`** (the "web/ui" starter). Only then does any dashboard class
   reach the classpath. Adding the jar makes the dashboard *available*, not *active*.

3. **By default nothing is exposed.** Even with `failover-dashboard-spring-boot-starter` on the classpath, the REST
   API and the UI are **disabled** and unmapped until the consumer explicitly turns them on. No endpoint,
   no static asset, no JSON is reachable out of the box. Default = `failover.dashboard.enabled=false`.

4. **The consumer must explicitly opt in via YAML** to expose the dashboard and/or its APIs — a deliberate
   configuration action, never a transitive side effect. Exposure is itself granular (UI vs API vs
   individual endpoints), mirroring Actuator's `management.endpoints.web.exposure.include` model.

5. **A mandatory security layer, Actuator-style, prevents data leakage.** When the consumer enables the
   dashboard, the framework must guide (and where possible enforce) an access-control gate so the endpoints
   are not anonymously reachable. The dashboard refuses to serve when enabled without a configured security
   posture (fail-closed), or — at minimum — emits a loud startup `WARN` and stays behind a required role.
   No payload data, credentials, or connection details are ever exposed; only aggregate counts and
   annotation metadata (see §9).

> **Design consequence:** the earlier "Swagger-like, on-by-default" framing is explicitly **rejected** in
> favour of "present only via a dedicated starter, off until enabled, gated when enabled". Sections 2, 9,
> 10 and 16 below are written to honour these five rules.

6. **The dashboard is fully self-contained — `failover-spring-boot-autoconfigure` is OFF-LIMITS.** All
   dashboard auto-configuration, beans, properties (`DashboardProperties`, `DashboardAutoConfiguration`,
   the services, the controllers) and **all dashboard tests (unit, slice, and integration)** live in the
   `failover-dashboard` module only. The default `failover-spring-boot-autoconfigure` module **must not**
   be touched for the dashboard: no dashboard bean, no `@ConditionalOnProperty`, no `AutoConfiguration.imports`
   entry, and no dashboard test may be added there. The dashboard also **does not depend on**
   `failover-spring-boot-autoconfigure` — it reads global failover settings (store type, execution type,
   exception policy, async) from the Spring `Environment` (`failover.*` keys), never from the
   `FailoverProperties` bean. This keeps the dashboard an isolated, optional add-on with no coupling to,
   and no footprint in, the core auto-configuration.

---

## 2. Naming & Module Placement

**Recommended module name: `failover-dashboard`** (not `failover-observable-dashboard`).

Rationale: we just renamed `failover-observable-scanner` → `failover-scanner` (ADR update, 2026-06-16) specifically to drop the misleading `observable` segment now that the scanner is a neutral shared component. A new `failover-observable-*` name would re-introduce the inconsistency. The dashboard is its own concern; `failover-dashboard` is short, neutral, and parallel to `failover-scheduler` / `failover-lookup`.

```
failover-dashboard/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/societegenerale/failover/dashboard/
    │   │   ├── DashboardController.java          # REST JSON API
    │   │   ├── DashboardConfigService.java       # config from FailoverScanner + FailoverProperties
    │   │   ├── DashboardMetricsService.java       # aggregation from MeterRegistry
    │   │   ├── dto/                                # ConfigEntry, MetricsSummary, ApiHealth, SeriesPoint...
    │   │   ├── DashboardProperties.java
    │   │   ├── DashboardAutoConfiguration.java
    │   │   └── package-info.java
    │   └── resources/
    │       ├── META-INF/spring/
    │       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       └── failover-dashboard/                # static UI (served from classpath)
    │           ├── index.html
    │           ├── app.js
    │           ├── styles.css
    │           └── vendor/chart.umd.min.js        # Chart.js vendored (offline-safe)
    └── test/java/...                              # slice tests + a @SpringBootTest IT
```

**Two artifacts**, to honour rules 1–2 in §1a:

- **`failover-dashboard`** — the implementation module (autoconfig, services, controller, static UI).
- **`failover-dashboard-spring-boot-starter`** — a thin POM-only starter that pulls in `failover-dashboard` plus the
  web/Micrometer transitive deps a dashboard needs. **This is the only thing a consumer adds.**

```
failover-dashboard-spring-boot-starter/        # the ONLY artifact a consumer adds for the dashboard
└── pom.xml                         # depends on: failover-dashboard, spring-boot-starter-web,
                                    #             micrometer-core (and optionally actuator)
```

**The default `failover-spring-boot-starter` does NOT depend on either of these** — a plain failover
consumer never receives a byte of dashboard code (rule 1). Register `failover-dashboard` **and**
`failover-dashboard-spring-boot-starter` in the parent `pom.xml` `<modules>`, and add `failover-dashboard` to
`failover-test-report` for the coverage gate.

---

## 3. Architecture — How It Plugs In

```
                 ┌─────────────────────────────────────────────┐
   Browser  ───► │  /failover-dashboard           (static UI)   │  index.html + app.js + Chart.js
                 │  /failover-dashboard/api/config (JSON)        │ ◄── DashboardConfigService ──► FailoverScanner
                 │  /failover-dashboard/api/metrics (JSON)       │ ◄── DashboardMetricsService ──► MeterRegistry
                 │  /failover-dashboard/api/health  (JSON)       │
                 └─────────────────────────────────────────────┘
```

- **Activation:** `@AutoConfiguration` gated on `@ConditionalOnClass(MeterRegistry)` + `@ConditionalOnWebApplication` + `@ConditionalOnProperty(failover.dashboard.enabled, matchIfMissing=true)`. Beans are `@ConditionalOnMissingBean` so consumers can override.
- **Data sources (both already in the context):**
  - `FailoverScanner` (from `failover-scanner`) → `findAllFailover()` gives every `@Failover` annotation.
  - `FailoverProperties` → global `store.type`, `type` (execution), `exception-policy`, `async`.
  - `MeterRegistry` (from `failover-observable-micrometer` / Boot actuator) → all `failover.*` counters and timers.
- **Static assets** served via a `WebMvcConfigurer` `addResourceHandlers` mapping `/failover-dashboard/**` → `classpath:/failover-dashboard/`, mirroring how `springdoc-openapi-ui` serves Swagger UI.

> The dashboard depends only on interfaces already present in a configured failover app. If `MeterRegistry`
> is absent (no Micrometer), the metrics view degrades gracefully to "metrics unavailable" while the config
> view still works (config needs only the scanner).

---

## 4. Backend Design

### 4.1 REST API

| Endpoint | Returns | Source |
|---|---|---|
| `GET /failover-dashboard/api/config` | `List<ConfigEntry>` — all `@Failover` points + global settings | `FailoverScanner` + `FailoverProperties` |
| `GET /failover-dashboard/api/metrics` | `MetricsSummary` — global + per-API KPIs and rates | `MeterRegistry` |
| `GET /failover-dashboard/api/metrics/series?windowSec=300` | `List<SeriesPoint>` — recent deltas for trend charts | optional in-memory ring buffer |
| `GET /failover-dashboard/api/health` | `List<ApiHealth>` — per-API health classification | derived |

All JSON, all read-only `GET`. No mutation endpoints (the dashboard never changes runtime state).

### 4.2 DTOs (sketch)

```java
record ConfigEntry(
    String name, String domain,
    long expiryDuration, String expiryUnit,
    boolean recoverAll, String payloadSplitter,
    String keyGenerator, String expiryPolicy,
    // global (same for all points, echoed for convenience):
    String storeType, String executionType, String exceptionPolicy, boolean asyncStore) {}

record Rates(double successRate, double failoverRate,
             double recoveryRate, double nonRecoveryRate, double healthyRate) {}

record ApiKpis(String name, String domain,
               long totalCalls, long upstreamSuccess, long failoverInvoked,
               long recovered, long notRecovered, long errors, long partial,
               Rates rates) {}

record MetricsSummary(ApiKpis overall, List<ApiKpis> perApi, long timestamp) {}

record ApiHealth(String name, String status /* HEALTHY | DEGRADED | UNHEALTHY */, double healthyRate) {}

record SeriesPoint(long timestamp, long calls, long failover, long recovered, long notRecovered,
                   long store, long recover) {}
```

### 4.3 Metric Aggregation — reads the *existing* counters

The framework already emits (see `MicrometerObservablePublisher`):

| Meter | Tags | Meaning |
|---|---|---|
| `failover.store.total` | `name`, `stored` | upstream succeeded → value stored (`stored=true`) |
| `failover.recover.total` | `name`, `recovered`, `recovery_failed` | a failover recover ran |
| `failover.recovery.outcome.total` | `name`, `domain`, `method`, `outcome` | **per-method outcome: `recovered` / `not_recovered` / `error`** |
| `failover.recovery.partial.total` | `name`, `method` | scatter/gather partial recovery |
| `failover.exception.total` | `name`, `exception` | upstream exception types |
| `failover.operation.duration` | `name`, `action` | store/recover latency (Timer) |
| `failover.store.async.failed` | `name`, `operation` | async write failure |

`DashboardMetricsService` walks `registry.find("failover.recovery.outcome.total").counters()` (and the others), groups by the `name` (and `domain`) tag, and sums `count()`.

### 4.4 Rate Derivations (the KPIs the user asked for)

Let, per API `name`:

- `S` = `failover.store.total{stored=true}` — **upstream-healthy calls** (result obtained and stored).
- `R_recovered` = `failover.recovery.outcome.total{outcome=recovered}`.
- `R_not` = `failover.recovery.outcome.total{outcome=not_recovered}`.
- `R_err` = `failover.recovery.outcome.total{outcome=error}`.
- `F` = `R_recovered + R_not + R_err` — **failover invocations** (every recover = an intercepted upstream failure).
- `Total` = `S + F` — **overall intercepted calls**.

Then:

| KPI | Formula | Meaning |
|---|---|---|
| **Overall calls** | `Total` | every intercepted `@Failover` call |
| **Success rate** | `S / Total` | upstream healthy → live value stored |
| **Failover rate** | `F / Total` | upstream threw → failover flow started |
| **Recovery rate** | `R_recovered / F` | failover served a stored, non-expired value |
| **Non-recovery rate** | `(R_not + R_err) / F` | failover found nothing usable (missing/expired/error) |
| **API health (healthy-served)** | `(S + R_recovered) / Total` | caller got a usable result (live or recovered) |
| **API failure** | `(R_not + R_err) / Total` | caller got no result (complete failure) |

> These map 1:1 onto counters that already exist — the dashboard adds **no** new metric. `outcome=error`
> is folded into non-recovery for the headline rate but shown separately in the breakdown so a
> store/serialization fault is never silently counted as a clean cache miss (it is already distinguished
> at the source, see ADR 51).

**Health classification** (`ApiHealth.status`), thresholds configurable:
- `HEALTHY` — `healthyRate ≥ 0.99`
- `DEGRADED` — `0.90 ≤ healthyRate < 0.99` (failover is working but firing often)
- `UNHEALTHY` — `healthyRate < 0.90` (failover frequently cannot recover)

### 4.5 Auto-Configuration

```java
@AutoConfiguration
@ConditionalOnWebApplication(type = SERVLET)
@ConditionalOnClass(MeterRegistry.class)
// Secure-by-default: absent property ⇒ OFF. The consumer must explicitly set
// failover.dashboard.enabled=true in YAML (rule 3 + rule 4 in §1a). No matchIfMissing.
@ConditionalOnProperty(prefix = "failover.dashboard", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DashboardProperties.class)
class DashboardAutoConfiguration implements WebMvcConfigurer {

    @Bean @ConditionalOnMissingBean
    DashboardConfigService dashboardConfigService(FailoverScanner scanner, FailoverProperties props) { ... }

    @Bean @ConditionalOnMissingBean
    DashboardMetricsService dashboardMetricsService(MeterRegistry registry, DashboardProperties props) { ... }

    @Bean @ConditionalOnMissingBean
    DashboardController dashboardController(DashboardConfigService cfg, DashboardMetricsService metrics) { ... }

    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(props.basePath() + "/**")
                .addResourceLocations("classpath:/failover-dashboard/");
    }
}
```

Registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

> **WebFlux:** ship the servlet variant first (the common case). A reactive variant (`@ConditionalOnWebApplication(REACTIVE)`
> with a `WebFluxConfigurer` + `RouterFunction`) is a fast-follow; the JSON services are framework-agnostic and reused as-is.

---

## 5. Configuration Dashboard (data)

`DashboardConfigService` produces one `ConfigEntry` per `scanner.findAllFailover()`, enriched with the
global `FailoverProperties` (store type, execution type, exception policy, async). Rendered as a sortable,
filterable table:

| Name | Domain | Expiry | Unit | Store | Execution | Recover-All | Splitter | Key Gen | Expiry Policy |
|------|--------|--------|------|-------|-----------|-------------|----------|---------|---------------|
| country-by-code | country | 24 | HOURS | JDBC | BASIC | false | — | default | default |
| countries-by-codes | country | 24 | HOURS | JDBC | BASIC | true | countrySplitter | default | default |

Empty per-annotation overrides (`keyGenerator=""`) render as `default` to signal "framework default".

---

## 6. Metrics Dashboard (UI layout)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  Failover Dashboard            [Config] [Metrics]            ⟳ auto-refresh 5s  │
├─────────────────────────────────────────────────────────────────────────────────┤
│  KPI CARDS (global, click a row in the table to scope to one API)               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ Overall  │ │ Success  │ │ Failover │ │ Recovery │ │ Non-Rec. │ │  Health  │  │
│  │  12,431  │ │  97.2 %  │ │   2.8 %  │ │  84.5 %  │ │  15.5 %  │ │ ● 99.6 % │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
├────────────────────────────────────┬────────────────────────────────────────────┤
│  Recovery vs Non-Recovery (donut)  │  Overall · Failover · Recovery · Non-Rec.  │
│        ◐  recovered 84.5%          │   (grouped/stacked bar, per API)           │
│           not-recovered 15.5%      │                                            │
├────────────────────────────────────┼────────────────────────────────────────────┤
│  Store vs Recover (bar)            │  Actions breakdown (store / recover /      │
│                                    │   clean / async-failed) — horizontal bar   │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Trend (line, last N samples): calls · failover · recovered · not-recovered     │
├─────────────────────────────────────────────────────────────────────────────────┤
│  Per-API health table — name · domain · calls · success% · failover% · recovery% · non-recovery% · healthy% · recovered · not-recovered · errors · health badge │
┤─────────────────────────────────────────────────────────────────────────────────┤
│  A timeline graph (or time series graph) to show for each api actual failures   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**Charts (Chart.js):**
- **Recovery vs Non-Recovery** — doughnut (`R_recovered` vs `R_not + R_err`).
- **Overall / Failover / Recovery / Non-Recovery** — grouped bar, one cluster per API.
- **Store vs Recover** — bar comparing `failover.store.total` vs `failover.recover.total`.
- **Actions** — horizontal bar: store / recover / clean / async-failed counts.
- **Trend** — multi-series line over the rolling sample buffer.
- **Per-API health** — colour-coded table (green/amber/red badges).

---

## 7. Frontend Design

- **Stack:** vanilla ES modules + **Chart.js vendored** into `vendor/` (offline-safe, no CDN, no npm for the consumer). Total payload target < 300 KB.
- **No build step.** Plain `index.html` + `app.js` + `styles.css`, served straight from the classpath.
- **Polling:** `app.js` fetches `/api/metrics` and `/api/config` on an interval (default 5 s, user-adjustable in the toolbar) and re-renders charts in place (`chart.data = ...; chart.update()`).
- **Dark/light** via CSS variables; responsive grid.
- **Resilient:** if `/api/metrics` 404s (no Micrometer), the Config tab still works and Metrics shows a friendly "Micrometer not on the classpath — metrics unavailable" banner.
- **No `eval` or remote script** (CSP-friendly, secure by design). Chart.js is vendored and statically imported; no dynamic code loading.
- **Accessibility:** semantic HTML, ARIA labels, keyboard navigation, colour-blind-friendly palette.
- **Extensible:** the API and UI are designed to allow new charts and KPIs without breaking changes (e.g. adding new fields to `MetricsSummary` or new endpoints).
- **Documentation:** the UI includes a "Help" section that explains each KPI, how it is calculated, and what it means in plain language.
- **Performance:** the dashboard is lightweight and efficient, with minimal CPU and memory overhead. The backend aggregation is optimized to read only the necessary metrics, and the frontend updates only the changed data points.
- **Testing:** the dashboard includes unit tests for the backend services and a `@SpringBootTest` integration test that verifies the full stack (controller, services, static assets) works together as expected.
- **Future-proofing:** the design allows for future enhancements, such as adding support for WebSockets for real-time updates, or integrating with external monitoring systems if desired (while keeping the core dashboard self-contained and zero-config).
- **UX**: the dashboard is designed to be intuitive and user-friendly, with clear visualizations and easy navigation between the configuration and metrics views. support dark mode / light mode for better accessibility and user preference.
- **KPI**: The KPI cards provide at-a-glance insights, while the charts allow for deeper analysis of trends and patterns. support for filtering and sorting the configuration table helps users quickly find specific failover points and understand their settings.
---

## 8. Time-Series / Trend Handling

Micrometer counters are **monotonic cumulative** — a raw read gives totals, not a rate over time. Three options:

| Option | How | Trade-off |
|---|---|---|
| **A. Client-side deltas** (default) | Browser polls totals every 5 s, computes `Δ = nowₜ − nowₜ₋₁`, plots the delta. | Zero server state; history only as long as the tab is open. |
| **B. Server ring buffer** | A `@Scheduled` sampler snapshots counters into a fixed-size in-memory ring (e.g. last 120 samples); `/api/metrics/series` returns it. | Small heap cost; survives page reloads; still process-local. |
| **C. Defer to Prometheus/Grafana** | Don't build trends; link out. | Least work; loses the "batteries-included" promise. |

**Recommendation:** ship **A** in v1 (no server state, instant value), add **B** behind `failover.dashboard.history.enabled=true` in v1.1 for reload-surviving trends. Document **C** as the path for real long-term analysis.

---

## 9. Security — Fail-Closed, Actuator-Style (mandatory, see §1a rule 5)

The dashboard surfaces internal operational data, so it is built **secure-by-default and fail-closed** —
it cannot leak anything unless the consumer takes two deliberate steps (add the starter, then enable +
secure it).

**Layered gates — all must pass for any byte to be served:**

1. **Classpath gate** — dashboard classes are present only if `failover-dashboard-spring-boot-starter` was added (rule 1–2).
2. **Activation gate** — `@ConditionalOnProperty(failover.dashboard.enabled=true)`; **absent ⇒ off** (rule 3–4). With the property unset, no controller, no resource handler, no JSON, no UI is mapped at all.
3. **Exposure gate (Actuator-style granularity, but ON by default once enabled)** — `enabled=true` is the
   single decision a consumer must make; it yields a **fully functional dashboard with sensible defaults**.
   Every exposure flag therefore **defaults to ON** when the dashboard is enabled — the UI, the JSON API,
   and all three endpoints (`config`, `metrics`, `health`) are served without any further configuration.
   The granular flags exist only to let a consumer **narrow** exposure (opt-out of individual surfaces),
   not as mandatory opt-ins:
   ```yaml
   failover:
     dashboard:
       enabled: true              # the ONLY switch needed — everything below is optional narrowing
       exposure:
         ui: true                 # serve the HTML/JS dashboard?      (default true)
         api: true                # serve the JSON endpoints?         (default true)
         include: [config, metrics, health]   # which API endpoints   (default: all three)
   ```
   To restrict, set a flag to `false` or trim `include`. The empty/unset state means "expose everything"
   (not "expose nothing") — so users decide *enabled or not*, not *which of N knobs to turn on*.
4. **Access-control gate (fail-closed)** — the dashboard must sit behind authentication. On startup the
   autoconfig **inspects whether Spring Security is on the classpath and a rule covers `base-path/**`**:
   - If Spring Security is present, the module contributes a `SecurityFilterChain` (lowest precedence,
     `@ConditionalOnMissingBean`) that requires an authenticated principal with a configurable role
     (`failover.dashboard.security.role`, default `FAILOVER_ADMIN`) for `base-path/**`.
   - If Spring Security is **absent** and the dashboard is enabled, the module **fails fast at startup**
     (or, if `failover.dashboard.security.allow-insecure=true` is explicitly set, starts with a loud
     repeated `WARN` — opt-in escape hatch for trusted-network/dev only). The `allow-insecure` escape
     hatch is **refused outright when the `prod` profile is active** (I-14): it must never silently
     disable the access gate in production — production must add Spring Security.

   Documented consumer override (same as Actuator):
   ```java
   http.authorizeHttpRequests(a -> a.requestMatchers("/failover-dashboard/**").hasRole("FAILOVER_ADMIN"));
   ```

   Optionally integrate with Actuator: if `spring-boot-actuator` is present, allow mounting under the
   management context/port so existing actuator security and network isolation apply uniformly.

**Data-minimisation (no leakage by construction):**
- Config view exposes only annotation attributes + store/execution **types** — never connection strings,
  credentials, schema/table names beyond the configured prefix, or payload data.
- Metrics are **aggregate counts and rates only** — never payload contents, keys, or argument values
  (consistent with the PII guidance in `security.md`).
- **Read-only:** no endpoint mutates state (no CSRF-write surface).
- **CSP-friendly:** static assets only, no `eval`, no remote script (Chart.js vendored).

---

## 10. Configuration Properties

```yaml
failover:
  dashboard:
    enabled: false                # MASTER SWITCH — DEFAULT false (secure-by-default, §1a rule 3).
                                  # Must be explicitly set true to activate anything.
    # Everything below is OPTIONAL — it all defaults to a working dashboard once enabled=true.
    base-path: /failover-dashboard  # default; single dedicated namespace for UI + API
    refresh-interval-seconds: 5    # default client poll cadence (UI can override per-session)
    exposure:                       # defaults to "expose everything"; set flags only to NARROW it
      ui: true                    # serve the HTML/JS dashboard (default true)
      api: true                   # serve the JSON API (default true)
      include: [config, metrics, health]  # which API endpoints (default: all three)
    security:
      role: FAILOVER_ADMIN        # required role for base-path/** when Spring Security is present
      allow-insecure: false       # if true, start without a security gate (dev/trusted-net ONLY) + WARN
                                  # refused outright when the 'prod' profile is active
      mount-under-actuator: false # optional: mount under the management context/port for actuator security
    history:
      enabled: false              # v1.1 — server-side ring buffer for reload-surviving trends
      samples: 120
      sample-interval-seconds: 15
    health:
      degraded-threshold: 0.99
      unhealthy-threshold: 0.90
```

Backed by a `DashboardProperties` `@ConfigurationProperties("failover.dashboard")` record. **`enabled` is
the single required switch; every other property has a working default** so the consumer decides only
*on or off*, not a matrix of flags. Once `enabled=true`, the UI and full API are served by default;
the granular flags exist only to **narrow** exposure. The one remaining deliberate consideration is the
access-control gate (§9 gate 4) — a protective, security-sensitive default that is intentionally **not**
relaxed by this convenience change. Documented in `properties-reference.md`.

---

## 11. Module Dependencies (pom)

```xml
<dependencies>
  <dependency><groupId>com.societegenerale.failover</groupId><artifactId>failover-core</artifactId></dependency>
  <dependency><groupId>com.societegenerale.failover</groupId><artifactId>failover-scanner</artifactId></dependency>
  <!-- Micrometer is the metrics source; provided so it activates only when the app already has it -->
  <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-core</artifactId><scope>provided</scope></dependency>
  <dependency><groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId><scope>provided</scope></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-autoconfigure</artifactId></dependency>
  <!-- test: spring-boot-starter-test, spring-boot-starter-web, an in-memory SimpleMeterRegistry -->
</dependencies>
```

Add `<module>failover-dashboard</module>` to the parent reactor, an **optional** dependency in
`failover-spring-boot-starter`, and a dependency in `failover-test-report` (coverage gate).

---

## 12. Implementation Phases

| Phase | Deliverable | Effort |
|---|---|---|
| **P0 — Skeleton** | `failover-dashboard` module + `failover-dashboard-spring-boot-starter`, poms, autoconfig stub (**default-off** `@ConditionalOnProperty`), `AutoConfiguration.imports`, empty static `index.html`; reactor wiring. **Default `failover-spring-boot-starter` untouched** | S |
| **P1 — Config API + view** | `FailoverScanner`→`ConfigEntry`, `/api/config`, config table UI (sort/filter) | S–M |
| **P2 — Metrics API** | `DashboardMetricsService` reads `MeterRegistry`, rate derivations, `/api/metrics` + `/api/health`; unit tests with `SimpleMeterRegistry` | M |
| **P3 — Metrics UI** | KPI cards, Chart.js donut/bars, per-API health table, client-side delta trend (option A) | M |
| **P4 — Security hardening (gates §1a rules 3–5)** | Granular `exposure.*` flags, fail-closed `SecurityFilterChain` + role, `allow-insecure` escape hatch + startup WARN, optional actuator mount, CSP, graceful no-Micrometer degradation, accessibility, dark/light | M |
| **P5 — History (opt-in)** | Server ring-buffer sampler + `/api/metrics/series` (option B) behind a property | M |
| **P6 — Docs & ADR** | `docs/modules/dashboard.md`, mkdocs nav, properties-reference rows, ADR "Embedded Failover Dashboard", changelog | S |

MVP = **P0–P3** (a useful, batteries-included dashboard). P4–P6 productionise it.

---

## 13. Testing Strategy

- **Unit (`*Test`)** — `DashboardMetricsService` against a hand-loaded `SimpleMeterRegistry`: assert every rate formula (success/failover/recovery/non-recovery/health) for crafted counter values, incl. the `outcome=error` folding and divide-by-zero (no calls yet → rates = 0, not NaN). `DashboardConfigService` against a stub `FailoverScanner`.
- **Slice (`@WebMvcTest`)** — controller returns expected JSON shape for config/metrics/health.
- **Integration (`*IT`, `@SpringBootTest`)** in the **`failover-dashboard`** module (NOT `failover-spring-boot-autoconfigure`, see §1a rule 6) — real H2 + a `@Failover` bean: drive a success and a forced failure, then `GET /failover-dashboard/api/metrics` and assert the KPIs move. Confirms end-to-end wiring (scanner + registry + autoconfig + resource handler).
- **AutoConfig tests** — `@ConditionalOnMissingBean` overrides honoured; absent `MeterRegistry` ⇒ config works, metrics degrade.
- **Secure-by-default tests (critical, §1a)** — with the property unset, **no** dashboard bean, controller, or resource handler is registered and every path 404s; `enabled=true` but empty `exposure.include` ⇒ APIs still unmapped; `enabled=true` with Spring Security absent and `allow-insecure=false` ⇒ **context fails to start** (fail-closed); with the security gate active, anonymous request ⇒ 401/403, authorised role ⇒ 200. Assert no payload/credential/key ever appears in any response body.
- **ArchUnit** — module obeys the split-package / naming rules.
- Coverage counts toward the 95 % aggregate gate (module added to `failover-test-report`).

---

## 14. Documentation & ADR

- **ADR** — "Embedded Failover Dashboard (`failover-dashboard`)": why batteries-included UI, why read existing meters rather than add new ones, why servlet-first, security stance.
- `docs/modules/dashboard.md` — what it is, screenshots, how to enable/secure, the KPI formula table.
- `docs/modules/index.md` — add the module to the diagram + table.
- `docs/configuration/properties-reference.md` — `failover.dashboard.*` rows.
- mkdocs nav entry; `changelog.md` "Added".
- `CLAUDE.md` module table row.

---

## 15. Risks & Trade-offs

- **Counter→rate semantics:** rates are lifetime cumulative unless trended client-side; the headline KPIs are "since process start". Clearly label them; the trend chart supplies the recent-window view.
- **Web-stack coupling:** servlet-first leaves WebFlux apps without the UI until the reactive variant lands (JSON services are reusable; the gap is the resource handler + router).
- **Security exposure:** resolved per §1a — **secure-by-default**: separate starter to obtain it, disabled until explicit YAML opt-in, fail-closed access gate when enabled. Convenience is traded for safety deliberately.
- **Asset size:** vendoring Chart.js adds ~200 KB to the jar; acceptable for an opt-in dashboard module, and keeps it offline/CDN-free.

---

## 16. Open Questions

1. ~~Default enabled vs disabled?~~ **RESOLVED (§1a): secure-by-default obtain, convenient-by-default use.** Separate `failover-dashboard-spring-boot-starter` to obtain it; `enabled=false` default. **But once `enabled=true`, the dashboard is fully functional with defaults** — exposure flags default ON (UI + full API), `base-path`/refresh/health all defaulted; the granular flags only *narrow* exposure. The consumer decides only *enabled or not*. The single protective exception is the fail-closed access-control gate (§9 gate 4), which is **not** relaxed.
2. **Servlet only for v1, or WebFlux in parallel?**  (Ans: Servlet only for v1, please suggest  ?)
3. **Bundle Chart.js, or allow a CDN override** for teams that prefer it?  (Ans: Bundle Chart.js, please suggest  ?)
4. **Per-method granularity** (the `method` tag exists on `recovery.outcome.total`) — expose a drill-down from API → method, or keep API-level for v1? (Ans: expose a drill-down from API if this is not very heavy), otherwise keep API-level for v1 and consider method-level in v1.1+)

---

### TL;DR

Build **`failover-dashboard`** + a dedicated **`failover-dashboard-spring-boot-starter`**: a **strictly opt-in,
secure-by-default** Spring Boot module that auto-configures a read-only REST API (`/failover-dashboard/api/*`)
over the **already-published** `FailoverScanner` config and Micrometer `failover.*` counters, and serves a
self-contained Chart.js UI from the classpath. **The default failover starter ships none of this** — a
consumer must add `failover-dashboard-spring-boot-starter`, then explicitly enable + secure it in YAML; nothing is
exposed otherwise (fail-closed, Actuator-style gating). It introduces **no new metrics**; every KPI
(success / failover / recovery / non-recovery / health) is derived from `failover.recovery.outcome.total`
+ `failover.store.total` + `failover.recover.total`. MVP is phases P0–P3; productionise security, opt-in
history, and docs/ADR in P4–P6.
