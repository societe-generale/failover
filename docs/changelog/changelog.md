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
- `FailoverScanner` SPI moved from `core.observable.scanner` to `core.scanner` — it is now a neutral
  shared component (consumed by both observability reporting and store deserialization safety)
- **Breaking — split packages eliminated (audit A-1).** Store implementations moved out of the shared
  `com.societegenerale.failover.store` package into per-backend subpackages:
  `FailoverStoreInmemory` → `…store.inmemory`, `FailoverStoreCaffeine` → `…store.caffeine`,
  `FailoverStoreJdbc` (and its `serializer`/`mapper`/`resolver` packages) → `…store.jdbc.*`,
  `FailoverStoreAsync` → `…store.async`. The `failover-lookup` `BeanFactory*` beans moved out of
  `failover-core`'s `key`/`expiry`/`payload.splitter` packages into `com.societegenerale.failover.lookup`.
  No two JARs share a package any more. Consumers referencing these classes by fully-qualified name
  (custom configuration, explicit imports) must update their imports; zero-config users are unaffected
- `DefaultFailoverHandler` store/recover logging: the lifecycle event stays at `INFO` (referential
  name only); the full `ReferentialPayload` body moved to `DEBUG` — no full-payload serialisation on
  the hot `INFO` path (audit Q-4, ADR 48)
- `ScatterGatherFailoverHandler` refactored into a thin facade over package-private collaborators
  (`PayloadScatter`, `PayloadGather`, `SliceDispatcher`, `SplitterInvoker`) — public API and behaviour
  unchanged (audit A-2, ADR 49)
- `FailoverStoreAutoConfiguration` now assembles the `failoverStore` bean in a single method instead of
  four `async × multitenant` `@ConditionalOnProperty` variants; behaviour unchanged, the per-tenant
  async wrapping is now explicit, and `@ConditionalOnMissingBean(FailoverStore)` override is retained
  (ADR 54)
- Performance: failover metric construction made the `Metrics` helper's responsibility — keys are
  built by concatenation instead of `String.format`, with typed `collect(String, long)` /
  `collect(String, boolean)` overloads replacing per-call `toString`/ternary noise in
  `AdvancedFailoverHandler`. ≈ 3.6× faster recover-bag build (JMH `744 → 204 ns/op`); behaviour
  unchanged (audit A-3/Q-2, ADR 50). Profile-gated JMH harness added — `mvn -pl failover-core
  -Pbenchmark test-compile exec:exec`

- **Breaking — `FailoverHandler` SPI is now method-aware.** `store` / `recover` / `recoverAll` carry
  the intercepted `@NonNull Method` (one method-aware operation each; the old method-less signatures
  are gone). Handlers that don't need the method extend the new `AbstractFailoverHandler`, which
  bridges to clean `protected` method-less operations; decorators that need it thread the non-null
  method through (used for the per-method outcome metric). Zero-config users and the built-in chain
  are unaffected; only custom `FailoverHandler` implementations must migrate (audit, ADR 52)

### Added

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

### Security

- Deserialization allowlist for stored payload classes — `JsonSerializer.toClass` rejects unknown
  classes (`FailoverStoreException`). Auto-populated from the packages of discovered `@Failover`
  payload types (secure by default); `failover.store.allowed-payload-classes` is an additive override
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
  (cross-module `jacoco:check` in the `report` module; currently ~99% line / ~97% branch). Audit T-1, ADR 53
- CI: advisory `dialect-its` job and **blocking** `mutation` job; the H2 build remains the required gate

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
