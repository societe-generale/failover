---
icon: material/history
---

# Changelog

All notable changes are documented here. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

---

## [3.0.0-SNAPSHOT] — In Development

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
- Deserialization allowlist moved to the JDBC namespace — `failover.store.allowed-payload-classes` is now
  `failover.store.jdbc.allowed-payload-classes` (it only ever applied to the serializing JDBC store)

### Added

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

### Security

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
