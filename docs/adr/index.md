---
icon: material/book-open-page-variant
---

# Architecture Decision Records

An architectural decision record (ADR) documents an important architectural choice along with its context and consequences.

!!! info "Immutable history"
    ADRs are append-only. Once accepted, content is never changed.
    Superseded decisions are marked **Deprecated** with a cross-reference.
    New decisions always get the next sequential number.

Full decision text → **[adr.md](adr.md)**

---

## Decision Index

| # | Decision | Date | Status |
|---|---|---|---|
| [ADR 1 — Build a failover lib](adr.md#adr-1-build-a-failover-lib) | Build a reusable annotation-driven failover library | 10-NOV-2021 | Accepted |
| [ADR 2 — @Failover Annotations](adr.md#adr-2-failover-annotations) | Dedicated `@Failover` annotation instead of reusing `@FeignClient` | 10-NOV-2021 | Accepted |
| [ADR 3 — Metadata for referential : As Of , Up To Date ?](adr.md#adr-3-metadata-for-referential-as-of-up-to-date) | `Referential` / `ReferentialAware` carry `upToDate` and `asOf` | 15-NOV-2021 | Accepted |
| [ADR 4 — Recovered Payload Handler](adr.md#adr-4-recovered-payload-handler) | `RecoveredPayloadHandler` SPI for null/default handling | 15-NOV-2021 | Accepted |
| [ADR 5 — Failover Store](adr.md#adr-5-failover-store) | `FailoverStore` abstraction with InMemory, Caffeine, JDBC impls | 16-NOV-2021 | Accepted |
| [ADR 6 — Failover Execution](adr.md#adr-6-failover-execution) | `FailoverExecution` SPI; BASIC (try/catch) and RESILIENCE variants | 17-NOV-2021 | Accepted |
| [ADR 7 — Auto Cleanup](adr.md#adr-7-auto-cleanup) | Scheduled expiry cleanup via `ExpiryCleanupScheduler` | 17-NOV-2021 | Accepted |
| [ADR 8 — Monitoring](adr.md#adr-8-monitoring) | `FailoverReporter` with logger and Micrometer publishers | 17-NOV-2021 | Accepted |
| [ADR 9 — Key Generator](adr.md#adr-9-key-generator) | `KeyGenerator` SPI; default derives key from method args | 30-DEC-2021 | Accepted |
| [ADR 10 — DefaultFailoverStore — Defensive Copy for Immutability](adr.md#adr-10-defaultfailoverstore-defensive-copy-for-immutability) | Store clones `ReferentialPayload` to prevent caller mutation | 25-MAY-2026 | Accepted |
| [ADR 11 — FailoverStoreBeanPostProcessor — Uniform Store Wrapping via BeanPostProcessor](adr.md#adr-11-failoverstorebeanpostprocessor-uniform-store-wrapping-via-beanpostprocessor) | BeanPostProcessor wraps stores uniformly at startup | 25-MAY-2026 | **Deprecated** — superseded by ADR 16, ADR 18, ADR 19 |
| [ADR 12 — MethodExceptionPolicy — Pluggable Exception Handling Strategy](adr.md#adr-12-methodexceptionpolicy-pluggable-exception-handling-strategy) | `ExceptionPolicy` enum: RETHROW, NEVER_THROW, CUSTOM | 26-MAY-2026 | Accepted |
| [ADR 13 — JDBC Native Merge/Upsert — Dialect Detection and Runtime Fallback](adr.md#adr-13-jdbc-native-mergeupsert-dialect-detection-and-runtime-fallback) | Dialect-specific upsert with ANSI fallback | 26-MAY-2026 | Accepted |
| [ADR 14 — DatabaseResolver — Strategy Interface for Database Product Detection](adr.md#adr-14-databaseresolver-strategy-interface-for-database-product-detection) | `DatabaseResolver` SPI detects DB product at runtime | 26-MAY-2026 | Accepted |
| [ADR 15 — FailoverStoreQueryResolver — Single-Responsibility Co-location of All JDBC Query Concerns](adr.md#adr-15-failoverstorequeryresolver-single-responsibility-co-location-of-all-jdbc-query-concerns) | All JDBC query building delegated to `FailoverStoreQueryResolver` | 26-MAY-2026 | Accepted |
| [ADR 16 — Removal of BeanPostProcessor-based Store Wrapping (Supersedes ADR 11)](adr.md#adr-16-removal-of-beanpostprocessor-based-store-wrapping-supersedes-adr-11) | BeanPostProcessor removed; auto-config assembles store chain explicitly | 02-JUN-2026 | Accepted — supersedes ADR 11 |
| [ADR 17 — TenantStoreFactory SPI — Abstracting Store Creation from Store Assembly](adr.md#adr-17-tenantstorefactory-spi-abstracting-store-creation-from-store-assembly) | `TenantStoreFactory` decouples per-tenant store creation | 02-JUN-2026 | Accepted |
| [ADR 18 — FailoverStoreAutoConfiguration — Central Assembler](adr.md#adr-18-failoverstoreautoconfiguration-central-assembler) | Single auto-config class assembles the complete store chain | 02-JUN-2026 | Accepted |
| [ADR 19 — FailoverStoreAsync — Explicit TaskExecutor Replacing @Async](adr.md#adr-19-failoverstoreasync-explicit-taskexecutor-replacing-async) | `AsyncFailoverStore` wraps delegate with explicit executor; drops `@Async` | 02-JUN-2026 | Accepted |
| [ADR 20 — MultiTenantFailoverStore — Outermost Per-Tenant Routing Decorator](adr.md#adr-20-multitenantfailoverstore-outermost-per-tenant-routing-decorator) | Multi-tenant routing sits outside async decorator | 02-JUN-2026 | Accepted |
| [ADR 21 — FailoverStoreMultiTenantAutoConfiguration — Multi-Tenant Auto-Configuration and TenantResolver SPI](adr.md#adr-21-failoverstoremultitenantautoconfiguration-multi-tenant-auto-configuration-and-tenantresolver-spi) | Separate auto-config for multi-tenant; `TenantResolver` SPI | 02-JUN-2026 | Accepted |
| [ADR 22 — FailoverKeyGenerator — UUID-Based Key Normalisation for Fixed-Width Store Keys](adr.md#adr-22-failoverkeygenerator-uuid-based-key-normalisation-for-fixed-width-store-keys) | MD5/UUID key hash prevents VARCHAR(256) overflow | 03-JUN-2026 | Accepted |
| [ADR 23 — PayloadSplitter — Scatter/Gather Storage for Composite-Key Failover](adr.md#adr-23-payloadsplitter-scattergather-storage-for-composite-key-failover) | `PayloadSplitter<T,R>` splits collection results into per-entity store entries | 04-JUN-2026 | Accepted |
| [ADR 24 — Parallel Scatter/Gather — CompletableFuture with Injected Executor](adr.md#adr-24-parallel-scattergather-completablefuture-with-injected-executor) | Scatter slices dispatched concurrently via injected `Executor` | 04-JUN-2026 | Accepted |
| [ADR 25 — ContextPropagator SPI — Thread-Local Context Propagation for Parallel Scatter](adr.md#adr-25-contextpropagator-spi-thread-local-context-propagation-for-parallel-scatter) | `ContextPropagator` captures and restores thread-local context on executor threads | 04-JUN-2026 | Accepted |
| [ADR 26 — Replace `LocalDateTime` with `Instant` for Timezone-Aware Expiry Timestamps](adr.md#adr-26-replace-localdatetime-with-instant-for-timezone-aware-expiry-timestamps) | `Instant` eliminates timezone ambiguity in expiry across multi-node/multi-timezone deployments | 06-JUN-2026 | Accepted |
| [ADR 27 — Migrate Deprecated `JdbcTemplate` Overloads in `FailoverStoreJdbc`](adr.md#adr-27-migrate-deprecated-jdbctemplate-overloads-in-failoverstorejdbc) | Varargs overloads replace deprecated `Object[]` + `int[]` forms; removes `java.sql.Types` usage | 06-JUN-2026 | Accepted |
| [ADR 28 — `domain` Attribute — Shared Store Partitioning Across `@Failover` Annotations](adr.md#adr-28-domain-attribute-shared-store-partitioning-across-failover-annotations) | `domain` enables scatter/gather slices and single-entity endpoints to share a store partition | 07-JUN-2026 | Accepted |
| [ADR 29 — Observability Layer — Observer, Publisher SPI and MDC Logger Refactor](adr.md#adr-29-observability-layer-observer-publisher-spi-and-mdc-logger-refactor) | Rename reporter stack to observer; MDC-safe publish via ObservablePublisher SPI; composite publisher | 07-JUN-2026 | Accepted |
| [ADR 30 — SpringContextFailoverScanner — Replacing Reflections-Based Classpath Scanning](adr.md#adr-30-springcontextfailoverscanner-replacing-reflections-based-classpath-scanning) | Spring bean enumeration replaces Reflections; removes package-to-scan config and Guava dep | 07-JUN-2026 | Accepted |
| [ADR 31 — failover-observable-micrometer — Micrometer Extension as an Optional Module](adr.md#adr-31-failover-observable-micrometer-micrometer-extension-as-an-optional-module) | Micrometer meters and Actuator health indicator extracted to an optional opt-in module | 07-JUN-2026 | Accepted |
| [ADR 32 — PayloadSplitterExecutionException — Wrapping User-Splitter Failures with Diagnostic Context](adr.md#adr-32-payloadsplitterexecutionexception-wrapping-user-splitter-failures-with-diagnostic-context) | All `PayloadSplitter` call failures wrapped in `PayloadSplitterExecutionException` with splitter name and operation context | 10-JUN-2026 | Accepted |
| [ADR 33 — doRecoverAll All-Slices Iteration — User-Controlled Slice Count](adr.md#adr-33-dorecoverall-all-slices-iteration-user-controlled-slice-count) | `doRecoverAll` iterates over all slices returned by `splitOnRecover`; slice count is user-controlled via `PayloadSplitter` | 10-JUN-2026 | Accepted |
| [ADR 34 — ScatterGatherFailoverHandler.recoverAll() Override — Clear Error for Scatter Case](adr.md#adr-34-scattergatherfailoverhandlerrecoverall-override-clear-error-for-scatter-case) | `ScatterGatherFailoverHandler.recoverAll()` overrides default with `UnsupportedOperationException` to prevent silent wrong-path execution | 10-JUN-2026 | Accepted |
| [ADR 35 — Empty splitOnRecover Guard — Null Return Instead of merge([])](adr.md#adr-35-empty-splitonrecover-guard-null-return-instead-of-merge) | Guard against empty `splitOnRecover` result returns null rather than merging an empty list | 10-JUN-2026 | Accepted |
| [ADR 36 — splitOnRecover RecoverAll Contract — Single Placeholder for DefaultFailoverHandler](adr.md#adr-36-splitonrecover-recoverall-contract-single-placeholder-for-defaultfailoverhandler) | `splitOnRecover` must return exactly one placeholder context when delegating to `DefaultFailoverHandler.recoverAll` | 10-JUN-2026 | Accepted |
| [ADR 37 — Payload Deserialization Allowlist — Secure-by-Default Class Loading](adr.md#adr-37-payload-deserialization-allowlist-secure-by-default-class-loading) | `JsonSerializer.toClass` restricted to an allowlist auto-derived from `@Failover` payload packages plus `failover.store.allowed-payload-classes` | 14-JUN-2026 | Accepted |
| [ADR 38 — Scatter/Gather Per-Slice Timeout — Bounded Parallel Join](adr.md#adr-38-scattergather-per-slice-timeout-bounded-parallel-join) | `failover.scatter.timeout` bounds parallel slice joins; timed-out recover slice = not recovered, store slice surfaces | 14-JUN-2026 | Accepted |
| [ADR 39 — Error Propagation — Never Recover on a Failing JVM](adr.md#adr-39-error-propagation-never-recover-on-a-failing-jvm) | `Error` rethrown unwrapped by the aspect; recovery never runs on a dying JVM | 14-JUN-2026 | Accepted |
| [ADR 40 — Multi-Tenant Strict Mode — Reject Unconfigured Tenants](adr.md#adr-40-multi-tenant-strict-mode-reject-unconfigured-tenants) | `failover.store.multitenant.strict` rejects (or WARNs on) tenants absent from the configured map | 14-JUN-2026 | Accepted |
| [ADR 41 — Async Store Failure Metric — Visibility for a Silently-Degraded Layer](adr.md#adr-41-async-store-failure-metric-visibility-for-a-silently-degraded-layer) | `FailoverStoreAsync` publishes `failover.store.async.failed` on executor-side failures | 14-JUN-2026 | Accepted |
| [ADR 42 — FailoverScanner Relocation to a Neutral Core Package](adr.md#adr-42-failoverscanner-relocation-to-a-neutral-core-package) | `FailoverScanner` SPI moved `core.observable.scanner` → `core.scanner`; shared by observability and store security | 14-JUN-2026 | Accepted |
| [ADR 43 — Dialect Integration Tests via Testcontainers](adr.md#adr-43-dialect-integration-tests-via-testcontainers) | Real PostgreSQL/MySQL/MariaDB merge ITs, profile-gated (`dialect-its`) and excluded from the default build; Oracle stays string-asserted | 15-JUN-2026 | Accepted |
| [ADR 44 — Concurrency Test Coverage for Multi-Tenant Routing and the Async Store](adr.md#adr-44-concurrency-test-coverage-for-multi-tenant-routing-and-the-async-store) | Contention tests for `computeIfAbsent` one-store-per-tenant and the `FailoverStoreAsync` executor path | 15-JUN-2026 | Accepted |
| [ADR 45 — ArchUnit Architecture Tests](adr.md#adr-45-archunit-architecture-tests) | Enforce no-`ThreadLocal`-in-async, `*Store` naming, and acyclic slices; split-package rule deferred to Phase 4 | 15-JUN-2026 | Accepted |
| [ADR 46 — PIT Mutation Testing on Expiry and Key Logic](adr.md#adr-46-pit-mutation-testing-on-expiry-and-key-logic) | Profile-gated (`mutation`) PIT over all of `failover-core`; mandated 95% gate (blocking), currently 96% / 99% test strength | 15-JUN-2026 | Accepted |
| [ADR 47 — JDBC insert→update Race — Bounded Retry over Silent Drop](adr.md#adr-47-jdbc-insertupdate-race-bounded-retry-over-silent-drop) | INSERT/UPDATE fallback re-INSERTs once when a concurrent expiry delete drops the UPDATE; bounded to 2 attempts, abandons at `warn` | 15-JUN-2026 | Accepted |
| [ADR 48 — Failover Lifecycle Logging — INFO Event, DEBUG Payload Body](adr.md#adr-48-failover-lifecycle-logging-info-event-debug-payload-body) | Store/recover lifecycle stays at `INFO` (name only); full `ReferentialPayload` body moved to `DEBUG` | 15-JUN-2026 | Accepted |
| [ADR 49 — ScatterGatherFailoverHandler — Extract Scatter/Gather Collaborators](adr.md#adr-49-scattergatherfailoverhandler-extract-scattergather-collaborators) | Thin facade + `PayloadScatter` / `PayloadGather` / `SliceDispatcher` / `SplitterInvoker`; public API and behaviour unchanged | 15-JUN-2026 | Accepted |
| [ADR 50 — Metrics Builder Helper — Cheaper Metric Construction on the Recover Path](adr.md#adr-50-metrics-builder-helper-cheaper-metric-construction-on-the-recover-path) | `Metrics` concatenates keys (no `String.format`) + typed `collect` overloads; ~3.6× faster recover-bag build (JMH 744→204 ns/op) | 15-JUN-2026 | Accepted |
| [ADR 51 — Per-Method Failover Outcome Metric + Method-Identity Threading](adr.md#adr-51-per-method-failover-outcome-metric-method-identity-threading) | `failover.recovery.outcome.total{name,domain,method,outcome}` for failover/recovery/non-recovery rates | 15-JUN-2026 | Accepted — threading mechanism refined by ADR 52 |
| [ADR 52 — FailoverHandler Method-Aware Contract + AbstractFailoverHandler](adr.md#adr-52-failoverhandler-method-aware-contract-abstractfailoverhandler-refines-adr-51-threading) | Single method-aware `FailoverHandler` contract (`@NonNull Method`); `AbstractFailoverHandler` bridges method-agnostic handlers; method threaded through scatter to slices. Breaking SPI | 15-JUN-2026 | Accepted |
| [ADR 53 — Overall JaCoCo Coverage Gate](adr.md#adr-53-overall-jacoco-coverage-gate) | Cross-module `jacoco:check` in the `report` module (unpack classes + merge all exec) fails `verify` below 95% line / 95% branch | 15-JUN-2026 | Accepted |
