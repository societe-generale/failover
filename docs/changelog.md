# Changelog

All notable changes are documented here. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

---

## [3.0.0-SNAPSHOT] — In Development

### Changed
- Upgraded to Spring Boot 4.x and Spring Cloud 2025.x
- Upgraded to Java 21 — virtual threads used for async store executor and scatter/gather executor
- Key generation now produces fixed-length MD5/UUID-based keys to prevent VARCHAR(256) overflow

### Added
- Scatter/gather: `PayloadSplitter<T, R>` for per-entity storage of collection-returning methods
- Scatter/gather: parallel slice dispatch via virtual threads (`failover.scatter.parallel`)
- Multi-tenant: `TABLE_PREFIX` and `SCHEMA` isolation strategies
- Multi-tenant: `TenantContextPropagator` for async context propagation
- `ContextPropagator` SPI for carrying thread-local context into async executor threads
- `CompositeContextPropagator` for combining multiple propagators
- Micrometer tracing context propagator (`MicrometerContextPropagator`)
- `failover.exception-policy` property (`RETHROW`, `NEVER_THROW`, `CUSTOM`)
- SpEL expression support for expiry (`expiryDurationExpression`, `expiryUnitExpression`)

---

## [2.x]

See [GitHub Releases](https://github.com/societegenerale/failover/releases) for 2.x history.

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
