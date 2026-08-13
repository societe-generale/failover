---
icon: material/history
---

# Changelog

All notable changes are documented here. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

---

## [3.0.0-SNAPSHOT] — In Development

!!! info "Upgrading from 2.x?"
    See **[Migrating 2.x → 3.0](../getting-started/migration-3.0.md)** for the breaking changes that
    need action, ordered by likelihood of affecting you.

### Changed

- Upgraded to Spring Boot 4.x and Spring Cloud 2025.x
- Upgraded to Java 21 — virtual threads used for async store executor and scatter/gather executor
- Key generation now produces fixed-length MD5/UUID-based keys to prevent VARCHAR(256) overflow
- `FailoverScanner` SPI moved from `core.observable.scanner` to `core.scanner` — now a neutral shared
  component (consumed by both observability reporting and store deserialization safety)
- Split packages eliminated — store implementations moved into per-backend subpackages
  (`…store.inmemory`, `…store.caffeine`, `…store.jdbc.*`, `…store.async`) and the `failover-lookup`
  `BeanFactory*` beans into `…lookup`, so no two JARs share a package (audit A-1)
- `DefaultFailoverHandler` logging: lifecycle event stays at `INFO` (name only); the full
  `ReferentialPayload` body moved to `DEBUG` — no full-payload serialisation on the hot path (audit Q-4, ADR 48)
- `ScatterGatherFailoverHandler` refactored into a thin facade over package-private collaborators
  (`PayloadScatter`, `PayloadGather`, `SliceDispatcher`, `SplitterInvoker`) — behaviour unchanged (audit A-2, ADR 49)
- `ScatterGatherFailoverHandler` is now built via `ScatterGatherFailoverHandler.builder(...)` (optional
  `.executor` / `.contextPropagator` / `.timeout` / `.observablePublisher`) instead of overloaded
  constructors (audit A-2)
- `FailoverStoreAutoConfiguration` assembles the `failoverStore` bean in a single method instead of four
  `async × multitenant` `@ConditionalOnProperty` variants; behaviour unchanged (ADR 54)
- Failover metric construction moved to the `Metrics` helper — keys built by concatenation instead of
  `String.format` (typed `collect` overloads). ≈ 3.6× faster recover-bag build (JMH `744 → 204 ns/op`);
  profile-gated JMH harness added (audit A-3/Q-2, ADR 50)
- `FailoverHandler` SPI is now method-aware — `store` / `recover` / `recoverAll` carry the intercepted
  `@NonNull Method`. Handlers that don't need it extend the new `AbstractFailoverHandler`; the built-in
  chain and zero-config users are unaffected, only custom implementations migrate (ADR 52)
- JDBC DDL now mandates an `EXPIRE_ON` index — the expiry-cleanup delete (`EXPIRE_ON < ?`) was a full
  table scan without it. All `CREATE TABLE` snippets and test schemas gained the index (audit I-13)
- SPI Javadoc hardened with `@implSpec` contracts (`KeyGenerator`, `ExpiryPolicy`, `PayloadEnricher`,
  `RecoveredPayloadHandler`); `FailoverHandler.recoverAll` documented as an optional operation (audit I-09, I-11)
- Build: corrected the stale `<scm><tag>` in the parent POM (`failover_1.1.0` → `HEAD`) (audit I-14)
- Build: **Maven Central publishing migrated from OSSRH to the Central Portal.** The legacy
  `s01.oss.sonatype.org` hosts are decommissioned and answer `HTTP 402`, so the EOL
  `nexus-staging-maven-plugin` could no longer publish. Replaced with
  `central-publishing-maven-plugin` (`publishingServerId=central`, `autoPublish=true`,
  `waitUntil=published`); `<distributionManagement>` and the `maven-release-plugin`
  `<stagingRepository>` are gone — the plugin's `extensions=true` owns the deploy target. Maintainers
  must generate a Central Portal **user token** and store it as `CENTRAL_USERNAME` /
  `CENTRAL_PASSWORD`; the retired OSSRH credentials do not authenticate against the Portal. The
  GPG passphrase now reaches `maven-gpg-plugin` via `MAVEN_GPG_PASSPHRASE` (secret `GPG_PASSPHRASE`)
  instead of `-Dgpg.passphrase=…`, keeping it out of the process argument list. See
  [Release Management](../support/release-management.md)
- Deserialization allowlist moved to the JDBC namespace — `failover.store.allowed-payload-classes` is now
  `failover.store.jdbc.allowed-payload-classes` (it only ever applied to the serializing JDBC store)
- **`failover.store.jdbc.strict-allowlist` now defaults to `true`** (was `false`). An empty resolved
  allowlist — no `@Failover` payload types discovered *and* nothing configured — denies all
  deserialization instead of disabling the restriction and loading whatever class name a store row
  carries. A major version is the point to stop shipping the fail-open path as the default. Zero-config
  applications are unaffected (the allowlist is auto-derived from discovered payload packages and is
  never empty); applications driving `FailoverStore` **directly**, outside any `@Failover` method, have
  nothing to derive from and must now name their payload types in `allowed-payload-classes`. Setting the
  flag back to `false` restores 2.x behaviour (ADR 60)
- **Removed** `failover.dashboard.cluster.shared-store.jdbc.auto-ddl` — the dashboard's JDBC snapshot store
  (`failover-dashboard-snapshotstore-jdbc`) no longer creates or alters the `FAILOVER_DASHBOARD_SNAPSHOT` table;
  schema management is the consuming service's responsibility. See
  [Dashboard](../modules/dashboard.md#scenario-d-cluster-via-shared-store-jdbc-durable) for the per-dialect DDL to
  run beforehand.

### Added

- **Windowed dashboard health classification** (`failover.dashboard.health.sample-size`, default `100`) — the
  `HEALTHY`/`DEGRADED`/`UNHEALTHY` status (and the Upstream call health cards below) is now computed over only the
  most recent `sample-size` calls per failover point, not the lifetime-cumulative rate: a handful of errors from
  hours ago no longer keep a since-recovered endpoint stuck `DEGRADED`. Implemented by `RollingHealthWindow`,
  reconstructed server-side from counter deltas on every poll — no new instrumentation, local source only for now.
- **Upstream call health cards** (Health tab) — one card per `@Failover` point, scored on the upstream call alone
  (`failoverRate`), deliberately not masked by how well failover recovered — a card can read `FAILING` while every
  other view in the dashboard is green because recovery is still covering for it. Each card shows a
  `STABLE`/`WATCH`/`FAILING` severity, a composition bar (fresh / recovered / blocked over the current window), a
  trend sparkline, the top exception, and — when masked — the failover point's configured expiry with a prompt to
  notify the upstream owner before it ages out. New read-only endpoint `/api/health/upstream`
  (`MetricsSource.upstreamWindows()`, `UpstreamWindow` DTO); empty on `prometheus`/`shared-store` sources for now.
- Per-API table: **not-recovered** and **errors** are now separate sortable columns (previously a single `errors`
  column silently excluded `not_recovered` outcomes — a row could be `UNHEALTHY` while showing `errors=0`).
- Tooltips (`data-tip`) on every dashboard section header, chart, KPI, and table column.
- **Non-blocking metric publishing** — `AsyncObservablePublisher` (in `failover-core`) wraps the composite
  publisher so every `ObservablePublisher` (built-in **and** custom) runs off the caller thread; a bounded
  queue with drop-on-full (counted as `failover.metrics.dropped.total`) guarantees metric emission never
  blocks or slows a `@Failover` call. Toggle with `failover.observable.async.{enabled,queue-capacity}`
  (default on; set `enabled=false` for synchronous, deterministic tests). No new core dependencies.
- **Richer metric catalog** (all over existing events — still consumer-only): `failover.call.total{result}`,
  `failover.user.impact.total{impact=unblocked|blocked}`, `failover.upstream.duration{result}` (timer),
  percentile histograms on `failover.operation.duration` (p95/p99), and gauges `failover.api.health`,
  `failover.stale.served.ratio`, `failover.live.entries` (in-memory/Caffeine stores). Automatic `instance`
  tag (`failover.observable.instance.mode=auto` — tags push registries like OTLP/Elastic, skips Prometheus
  which adds it at scrape) and a cardinality guard (`failover.observable.cardinality.*`).
- **Distributed dashboard (multi-instance)** — pluggable `MetricsSource` read seam selected by
  `failover.dashboard.cluster.mode`: `local` (default, unchanged) · `prometheus` (cluster-wide PromQL incl.
  p95/p99 and per-instance) · `shared-store` (peers push KPI snapshots, aggregated in-app for small
  clusters with no Prometheus). `shared-store` ships an in-memory store plus an optional durable JDBC store
  (**`failover-dashboard-snapshotstore-jdbc`** module) with validated `table-prefix`, age+size retention,
  liveness windowing, and a reset-aware cluster trend.
- **`HeartbeatStoreJdbc`** (`failover-dashboard-snapshotstore-jdbc`, `store=jdbc`) — durable, shared
  counterpart to `HeartbeatStoreInmemory`. Needed for correct `LIVE`/`DOWN` status when the dashboard is
  embedded in *multiple* `@Failover` instances sharing one database: the in-memory heartbeat store is
  process-local, so each embedded dashboard would only ever see the heartbeat pushed to its own loopback
  and show every other peer stuck at `UNKNOWN`. New table `FAILOVER_DASHBOARD_HEARTBEAT`
  (`INSTANCE_ID` PK, `LAST_SEEN TIMESTAMP WITH TIME ZONE`), same `table-prefix` and no-auto-DDL convention
  as the snapshot table — see
  [Dashboard Scenario D](../modules/dashboard.md#scenario-d-cluster-via-shared-store-jdbc-durable) for the DDL.
  Both this table's `LAST_SEEN` and the snapshot table's `RECEIVED_AT` are time-zone-aware timestamps
  (`TIMESTAMP(9) WITH TIME ZONE` on Oracle/H2; capped at `(6)` on PostgreSQL/MySQL/MariaDB, which don't
  support 9-digit fractional precision, and MySQL/MariaDB have no `WITH TIME ZONE` syntax at all) — read and
  written as `OffsetDateTime` (UTC) via JDBC 4.2 `setObject`/`getObject`, converted to/from epoch-millis at
  that boundary so the rest of the codebase stays in epoch-millis
- **`failover.dashboard.cluster.shared-store.liveness.enabled`** (`DashboardProperties.Liveness`, default
  `false`) — dashboard-side toggle for heartbeat liveness tracking (ADR 66's original, previously-unimplemented
  design), independent of the peer-side `cluster.snapshot.heartbeat.enabled`. Until set `true`, no
  `HeartbeatStore` bean is created at all — neither `HeartbeatStoreInmemory` nor, under `store=jdbc`,
  `HeartbeatStoreJdbc` — so `/api/cluster/heartbeat` is unmapped, `SharedStoreMetricsSource` never queries
  liveness, and `FAILOVER_DASHBOARD_HEARTBEAT` is never required to exist. `SourceInfo` gained a
  `livenessTrackingEnabled` field so the UI's `instance live tracking` badge can distinguish `disabled`
  (dashboard-side toggle off), `waiting` (toggle on, no peer has pushed yet), and `on`
- **Reset-aware shared-store aggregate + bounded instance retirement** (ADR 67) — the instant cluster
  aggregate is now monotonic across peer restarts: on counter reset the store folds the pre-restart totals
  into a per-instance carried-forward baseline (`SnapshotBaseline`; persisted in the JDBC store's
  `BASELINE_JSON` column), so Overview cards and the trend graph finally agree. Instances not seen
  within `failover.dashboard.cluster.shared-store.instance-retention` (default `7d`, `0` = never) are
  retired from the Instances tab while their counts keep contributing via `SnapshotStore.retiredAggregate()`;
  beyond 100 retired entries the oldest compact into a tombstone aggregate — the in-memory store stays
  heap-bounded under Kubernetes pod churn. Summary-merge math extracted to a shared
  `MetricsSummaryAggregator` (`failover-observable-metrics`) so every consumer uses identical KPI formulas.
- **Dashboard Instances tab** — per-instance roll-up + table + drill-down (`/api/instances`,
  `MetricsSource.instances()`) for `shared-store` and `prometheus`; answers "one bad node vs all". Plus a
  cluster health roll-up on the Health tab and a metrics-provenance badge (this-instance vs cluster).
- **Standalone dashboard** — runs as its own app pointed at a backend with no `@Failover` library present
  (a no-op `FailoverScanner` keeps the config view empty while metrics/health work from the backend).
- Async executor back-pressure guard (audit R-2). The async store executor and the scatter/gather
  executor can now be bounded: `failover.store.async-executor.concurrency-limit` /
  `failover.scatter.concurrency-limit` cap concurrently in-flight tasks, with a
  `*.rejection-policy` (`DISCARD` default / `CALLER_RUNS` / `ABORT`) applied on overload. Bound via a
  `BoundedTaskExecutor` decorator that keeps accepted tasks on virtual threads — no thread-pool swap.
  Unbounded by default (`limit=0`), so existing behaviour is unchanged until configured
- **`failover-dashboard`** + **`failover-dashboard-spring-boot-starter`** — an opt-in, secure-by-default
  embedded observability dashboard. A read-only JSON API (`/failover-dashboard/api/config|metrics|health`,
  opt-in `/metrics/series`) and a self-contained Chart.js UI over the existing `FailoverScanner` config
  and `failover.*` meters — no new instrumentation. Shipped only via the dedicated starter; off until
  `failover.dashboard.enabled=true`; fail-closed access gate (Spring Security role, or `allow-insecure`
  with a loud WARN — `allow-insecure` refused outright under the `prod` profile), static-only CSP, and
  aggregate-only data exposure (ADR 55)
- Dashboard observability + UX enhancements (all over existing meters — still no new instrumentation):
  surfaced three previously-unshown signals — **async write failures** (`failover.store.async.failed`, as a
  KPI, a red per-API column and a loud banner), **store/recover latency** mean/max
  (`failover.operation.duration`) and **top failover-trigger exception types** (`failover.exception.total`);
  added a **Global configuration** panel (`/api/config/settings`) showing effective `failover.*` /
  `failover.dashboard.*`; a **Success / Full / Partial recovery** chart; a 3-up chart grid plus full-width
  timeline that **hydrates from `/api/metrics/series`** on load when history is enabled; selectable
  auto-refresh (`off`/`10s`/`30s`/`1m`/`10m`/`1h`, default `1m`) with a manual refresh + last-updated; and a
  documentation help link. Tab order Metrics / Health / Config
- `failover.store.caffeine.max-size` (default `10000`, same as `inmemory.max-entries`) — the Caffeine
  store can now cap its entry count and evict by Window TinyLFU once exceeded; `0` = unbounded (audit I-15)
- Scatter/gather: `PayloadSplitter<T, R>` for per-entity storage of collection-returning methods
- Scatter/gather: parallel slice dispatch via virtual threads (`failover.scatter.parallel`)
- Scatter/gather: `failover.scatter.timeout` (default 10s) — bounds parallel slice joins so a hung
  slice cannot block the caller; on timeout a recover slice yields no data, a store slice surfaces it
- Multi-tenant: `TABLE_PREFIX` and `SCHEMA` isolation strategies
- Multi-tenant: `TenantContextPropagator` for async context propagation
- Multi-tenant: `failover.store.multitenant.strict` — reject (or WARN once) tenants absent from the
  configured map instead of silently sharing the global table
- `ContextPropagator` SPI for carrying thread-local context into async executor threads
- `CompositeContextPropagator` for combining multiple propagators
- Micrometer tracing context propagator (`MicrometerContextPropagator`)
- Micrometer counter `failover.store.async.failed{name,operation,exception_type}` for async store failures
- Micrometer counter `failover.recovery.outcome.total{name,domain,method,outcome}` — per-intercepted-method
  failover / recovery / non-recovery rates (`outcome=recovered|not_recovered|error`); recorded once per
  method call, tagged by the actual method (`SimpleClass#method`) (ADR 51)
- Micrometer counter `failover.recovery.partial.total{name,method}` — scatter/gather recoveries where
  some (not all) slices were recovered, plus a `WARN` log; the merged collection may be incomplete and
  the `PayloadSplitter.merge` policy (keep positional nulls / compact / reject) decides the result (audit I-04)
- `failover.exception-policy` property (`RETHROW`, `NEVER_THROW`, `CUSTOM`)
- SpEL expression support for expiry (`expiryDurationExpression`, `expiryUnitExpression`)
- `Automatic-Module-Name` in every published JAR manifest (e.g. `com.societegenerale.failover.core`,
  `…store.jdbc`, `…lookup`) — stable JPMS module names ahead of full `module-info.java` (audit A-1)

### Fixed

- **`HeartbeatStoreInmemory` no longer grows without bound.** Every distinct instance id ever POSTed to
  the heartbeat endpoint stayed resident for the life of the JVM — ordinary pod churn left one dead
  entry per rolled instance forever. It now applies the same two bounds as `SnapshotStoreInmemory`:
  entries not refreshed within `cluster.shared-store.instance-retention` (default `7d`) are dropped,
  and a hard ceiling of 10 000 instances evicts the oldest heartbeat to admit a new one. Retention is
  deliberately the *same* window the snapshot store retires on, so nothing still reachable from
  `allInstances()` is ever discarded. The store now takes a `FailoverClock` rather than reading
  `System.currentTimeMillis()` directly, so a co-located deployment ages heartbeats on the same clock
  the rest of failover uses for expiry.
- Dashboard `openTab` escapes the tab name before building its `querySelector`. A crafted
  `#…` fragment produced an invalid selector, so `querySelector` threw and page initialisation stopped.
- JDBC INSERT/UPDATE fallback no longer silently drops a write when a concurrent expiry delete
  removes the row between the failed INSERT and the follow-up UPDATE. A **single bounded retry**
  re-INSERTs the now-absent row; if every attempt loses the race the write is abandoned at `warn`
  (regenerable cache). Native-merge dialects were never affected (audit A-4, ADR 47)
- A misbehaving `RecoveredPayloadHandler` no longer breaks the failover flow — `AdvancedFailoverHandler`
  guards the post-processing call, logs the failure at `ERROR`, and returns the raw recovered payload
  unchanged as the fallback (audit I-06)
- Scatter/gather no longer reports an all-missing recovery as *partial*. The `recover-partial` metric
  (audit I-04) now fires only for genuine partial recovery (`0 < missing < total`); when every slice is
  missing it is full non-recovery — logged as such, no partial metric, and surfaced upstream as
  `is-recovered=false`
- `DashboardProperties.SharedStore`/`.Jdbc` gained the same `@ConstructorBinding` on their canonical
  constructor that `Cluster`/`Snapshot`/`Security` already had. Without it, Spring Boot's binder treated
  the two-constructor record as ambiguous and silently skipped constructor binding, so
  `cluster.shared-store.store` and `.jdbc.table-prefix` always resolved to their hardcoded defaults
  regardless of configured YAML
- `DashboardAutoConfiguration`'s ingest endpoints (`/api/cluster/snapshot`, `/api/cluster/heartbeat`) could
  silently never map under `cluster.shared-store.store=jdbc` — `clusterSnapshotController`/
  `clusterHeartbeatController` are `@ConditionalOnBean(SnapshotStore/HeartbeatStore.class)`, but those beans
  come from the separately-conditioned `SnapshotStoreJdbcAutoConfiguration` in another module, and no
  ordering edge existed between the two `@AutoConfiguration` classes. `@ConditionalOnBean` across
  independently-conditioned auto-configurations is unreliable without one — confirmed both declaration
  orders left the controllers unregistered (bare `404`, not an auth denial). Fixed by adding
  `SnapshotStoreJdbcAutoConfiguration` to `DashboardAutoConfiguration`'s `@AutoConfiguration(afterName = ...)`
  list (referenced by name, no compile dependency — same pattern already used for `FailoverAutoConfiguration`)
  (ADR 70)

### Security

- **Dashboard UI now HTML-escapes every server-supplied string it renders.** Referential names and
  domains, store/policy bean ids, exception types, config values and — in `cluster.mode=shared-store` —
  peer-pushed instance ids were interpolated raw into `innerHTML`. The CSP (`script-src 'self'`, no
  `unsafe-inline`) already blocked script execution, but `style-src` permits inline styles, so injected
  markup could still deface or spoof an operator console, and `data-id="${instanceId}"` was one
  unescaped quote from an attribute breakout.
- **Peer-ingest endpoints validate the instance id.** `POST …/api/cluster/snapshot` and
  `…/api/cluster/heartbeat` took `@RequestBody` with no checks: a null id NPE'd into a `500`, and an
  arbitrary-length, arbitrary-charset id became a stored map key and rendered table content. Both now
  reject a missing, blank, over-long (>200 char) or non-conforming id with `400`, accepting only
  letters, digits and the separators `. _ - : @ /`. A snapshot with no `summary` is rejected too — it
  is dereferenced on every aggregation, so accepting it traded one `400` for a `500` on every
  subsequent read.
- Dashboard peer ingest now **fails fast when `failover.dashboard.cluster.snapshot.username` is set
  without a `password`**. Both properties default to `""` and only `username` gated the Basic-auth
  filter chain, so a password that silently resolved to empty (unmounted secret, unresolved
  placeholder, typo'd env var) built an in-memory user whose encoded password was exactly `{noop}` —
  `NoOpPasswordEncoder` matched an empty submitted password, so `Authorization: Basic base64("<user>:")`
  authenticated and any caller who guessed the username could push forged snapshots and heartbeats
  into the cluster view, while startup logged that ingest *was* secured. A blank `username` is
  rejected for the same reason (`@ConditionalOnProperty` matches on presence, not on a real value).
- Deserialization allowlist for stored payload classes — `JsonSerializer.toClass` rejects unknown
  classes (`FailoverStoreException`). Auto-populated from the packages of discovered `@Failover`
  payload types (secure by default); `failover.store.jdbc.allowed-payload-classes` is an additive override
- `failover.store.jdbc.table-prefix` validated against an identifier pattern at startup
- `Error` (e.g. `OutOfMemoryError`) now propagates unwrapped through the aspect — recovery never runs
  on a failing JVM

### Testing

- Dialect integration tests (Testcontainers) for PostgreSQL, MySQL and MariaDB — exercise the real
  native merge/upsert SQL. Profile-gated (`-Pdialect-its`, requires Docker) and excluded from the
  default build; Oracle remains string-asserted. See
  [Dialect Integration Tests](../quality/integration-tests.md)
- Concurrency tests for `MultiTenantFailoverStore` (`computeIfAbsent` one-store-per-tenant) and the
  `FailoverStoreAsync` executor path — part of the default build. See
  [Concurrency Tests](../quality/concurrency-tests.md)
- ArchUnit architecture tests: no `ThreadLocal` in the async decorator, `*Store` naming, acyclic
  slices, and split-package guards (no store in the bare `…store` package; `BeanFactory*` beans in
  `…lookup`). See [Architecture Tests](../quality/architecture-tests.md)
- PIT mutation testing over all of `failover-core` (`-Pmutation`), mandated at a **95% gate**
  (currently 96%, test strength 99%). See [Mutation Testing](../quality/mutation-testing.md)
- Overall JaCoCo coverage gate — `mvn verify` fails below **95% line / 95% branch** across all modules
  (cross-module `jacoco:check` in the `failover-test-report` module; currently ~99% line / ~97% branch). Audit T-1, ADR 53
- CI: advisory `dialect-its` job and **blocking** `mutation` job; the H2 build remains the required gate
- Circuit-breaker state-transition test (`ResilienceFailoverExecutionTest`) — recovery served from the
  store across CLOSED → OPEN (short-circuit) → HALF_OPEN (trial) (audit I-18)
- High-cardinality multi-tenant contention test — 200 distinct tenants interleaved across threads, each
  building exactly one isolated store via `computeIfAbsent` (audit I-07)
- `failover.store.inmemory.max-entries` (default `10000`) — the in-memory store is now size-capped and
  evicts the least-recently-accessed entry (LRU) past the cap, preventing unbounded heap growth from
  high-cardinality keys. Set `0` for the legacy unbounded behaviour (audit I-10)
- `ExpiryPolicyContractVerifier` — a dependency-free harness SPI implementors can drop into a unit test
  to check a custom `ExpiryPolicy` against the contract (non-null/future `computeExpiry`, `expireOn`-driven
  `isExpired`). See [Custom Expiry Policy](../how-to/custom-expiry-policy.md#testing-your-policy)
- Performance validation (default build): JDBC cleanup-under-write-load contention test (2400 upserts
  interleaved with 400 `cleanByExpiry` runs, no deadlock) and a virtual-thread scatter/gather scaling test
  (1000 blocking slices complete concurrently, no pool to size). See [Benchmarks](../quality/benchmarks.md#performance-validation)

---

## [2.x]

See [GitHub Releases](https://github.com/societe-generale/failover/releases) for 2.x history.

---

## [1.0.0] — Initial Release

- `@Failover` annotation for Spring AOP interception
- `FailoverHandler` with store/recover/clean lifecycle
- InMemory, Caffeine, JDBC store implementations
- `Referential` and `ReferentialAware` domain contracts
- `KeyGenerator` with default argument-based implementation
- `ExpiryPolicy` with duration + ChronoUnit
- `PayloadEnricher` for `upToDate`/`asOf` metadata
- `RecoveredPayloadHandler` SPI
- `FailoverReporter` with logger and Micrometer publishers
- Scheduler: expiry cleanup and report publishing
- Resilience4j circuit-breaker integration (`failover.type=resilience`)
