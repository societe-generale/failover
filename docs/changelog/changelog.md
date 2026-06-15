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
- `failover.exception-policy` property (`RETHROW`, `NEVER_THROW`, `CUSTOM`)
- SpEL expression support for expiry (`expiryDurationExpression`, `expiryUnitExpression`)

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
  slices. See [Architecture Tests](../quality/architecture-tests.md)
- PIT mutation testing on the expiry + key packages (`-Pmutation`). See
  [Mutation Testing](../quality/mutation-testing.md)
- CI: advisory (non-blocking) `dialect-its` and `mutation` jobs; the H2 build remains the required gate

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
