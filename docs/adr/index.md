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
| [ADR 29 — Observability Layer — FailoverObserver / ObservablePublisher SPI and MdcLoggerObservablePublisher](adr.md#adr-29-observability-layer-failoverobserver--observablepublisher-spi-and-mdcloggerobservablepublisher) | Rename and refactor reporter stack; MDC-safe publish pattern; composite publisher SPI | 07-JUN-2026 | Accepted |
| [ADR 30 — SpringContextFailoverScanner — Replacing Reflections-Based Classpath Scanning](adr.md#adr-30-springcontextfailoverscanner-replacing-reflections-based-classpath-scanning) | Spring bean enumeration replaces Reflections; removes package-to-scan config and Guava dep | 07-JUN-2026 | Accepted |
| [ADR 31 — failover-observable-micrometer — Micrometer Extension as an Optional Module](adr.md#adr-31-failover-observable-micrometer-micrometer-extension-as-an-optional-module) | Micrometer meters and Actuator health indicator extracted to an optional opt-in module | 07-JUN-2026 | Accepted |
