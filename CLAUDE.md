# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- ALWAYS query the knowledge graph first.
- Only read raw files if explicitly asked.
- Use graphify-out/wiki/index.md as your entry point.

## Build and test commands

```bash
# Full build with unit + integration tests
mvn clean verify

# Unit tests only (Surefire, *Test.java)
mvn test

# Single unit test
mvn test -pl failover-core -Dtest=DefaultFailoverHandlerTest

# Single integration test (Failsafe, *IT.java)
mvn verify -pl failover-spring-boot-autoconfigure -Dit.test=FailoverAppTestIT

# Skip all tests
mvn install -DskipTests

# Aggregate coverage report (written to report/target/site/jacoco-aggregate/)
mvn verify -pl report
```

Integration tests live only in `failover-spring-boot-autoconfigure/src/test` and run against a real H2 database. They use `failover.store.async=false` so writes are synchronous and assertions are deterministic.

## Architecture

Failover is a Spring Boot library that transparently saves referential service responses and replays the last known-good value when the upstream fails. The entry point is `@Failover` on a concrete class method (not an interface — CGLIB proxy requires the annotation on the implementation).

### Handler chain (assembled in `FailoverAutoConfiguration.failoverHandler`)

```
AdvancedFailoverHandler          ← observable reporting, RecoveredPayloadHandler, exception policy
  └── ScatterGatherFailoverHandler  ← scatter/gather: splits composite result into per-entity slices
        └── DefaultFailoverHandler    ← core store/recover logic, expiry check
```

`DefaultFailoverHandler` calls `FailoverStore.store` on success and `FailoverStore.find` + expiry check on failure. `FailoverStore.find` **must return a defensive copy** — callers mutate the returned payload (setting `upToDate`, `asOf`) without affecting the stored data.

### Store assembly chain

```
AsyncFailoverStore (when failover.store.async=true, default)
  └── MultiTenantFailoverStore (when failover.store.multitenant.enabled=true)
        └── base store: InMemory | Caffeine | JDBC | custom
```

### Key interfaces and where to find them

| Interface | Location | Purpose |
|---|---|---|
| `FailoverStore<T>` | `failover-core/.../store/FailoverStore.java` | Persistence contract: store/find/delete/cleanByExpiry |
| `KeyGenerator` | `failover-core/.../key/KeyGenerator.java` | Derives the store key from method args |
| `ExpiryPolicy<T>` | `failover-core/.../expiry/ExpiryPolicy.java` | Computes and checks expiry |
| `PayloadEnricher<T>` | `failover-core/.../payload/PayloadEnricher.java` | Enriches payloads on store/recover |
| `PayloadSplitter<T,R>` | `failover-core/.../payload/splitter/PayloadSplitter.java` | Scatter/gather: splits result into slices, merges on recover |
| `RecoveredPayloadHandler` | `failover-core/.../payload/RecoveredPayloadHandler.java` | Handles null recovery (e.g. return empty instead of null) |
| `FailoverHandler<T>` | `failover-core/.../FailoverHandler.java` | Orchestrates store + recover + clean |

### Extension points

Every core bean uses `@ConditionalOnMissingBean`. Declare your own bean to replace any default:

- **Custom key generator**: declare a bean, reference it in `@Failover(keyGenerator = "myBeanName")`
- **Custom expiry policy**: declare a bean, reference it in `@Failover(expiryPolicy = "myBeanName")`
- **Scatter/gather**: declare a `PayloadSplitter` bean, reference it in `@Failover(payloadSplitter = "myBeanName")`
- **Domain grouping**: `@Failover(domain = "country")` — two annotations sharing a domain share store entries; mismatched expiry within a domain is warned at startup

### Module responsibilities

| Module | Responsibility |
|---|---|
| `failover-domain` | `@Failover` annotation, `Referential`, `ReferentialAware`, `Metadata` |
| `failover-core` | All interfaces + default implementations: handler, store contract, key/expiry/payload |
| `failover-aspect` | Spring AOP `@Around` interceptor (`FailoverAspect`) |
| `failover-store-inmemory` | `ConcurrentHashMap` — dev/test only |
| `failover-store-caffeine` | Caffeine in-process store |
| `failover-store-jdbc` | JDBC store (H2, PostgreSQL, MySQL, MariaDB, Oracle) |
| `failover-store-async` | Non-blocking write decorator using virtual-thread executor |
| `failover-store-multitenant` | TABLE_PREFIX / SCHEMA per-tenant routing |
| `failover-lookup` | Spring `BeanFactory`-based lookups for named `KeyGenerator` / `ExpiryPolicy` / `PayloadSplitter` beans |
| `failover-execution-resilience` | Resilience4j circuit-breaker wrapping upstream calls |
| `failover-observable-scanner` | Startup scanner: walks Spring context for all `@Failover` methods |
| `failover-observable-micrometer` | Micrometer counters + health indicator |
| `failover-scheduler` | `ExpiryCleanupScheduler` (hourly) + `ObservableScheduler` (daily report) |
| `failover-spring-boot-autoconfigure` | Zero-config auto-configuration; contains all integration tests |
| `failover-spring-boot-starter` | Single POM dependency consumers add |
| `report` | JaCoCo aggregate coverage report |

## Key configuration properties

```yaml
failover:
  enabled: true
  type: basic                   # basic | resilience
  exception-policy: rethrow     # rethrow | never_throw
  store:
    type: inmemory              # inmemory | caffeine | jdbc (prod)
    async: true                 # false in integration tests for deterministic assertions
    jdbc:
      table-prefix: ""
    multitenant:
      enabled: false
  scatter:
    parallel: true              # virtual-thread parallel slice dispatch
  scheduler:
    enabled: true
    report-cron: "0 0 0 * * *"
    cleanup-cron: "0 0 * * * *"
```

## Testing conventions

- Unit tests (`*Test.java`): pure Mockito + JUnit 5, no Spring context
- Integration tests (`*IT.java`): `@SpringBootTest` with real H2, all in `failover-spring-boot-autoconfigure`
- Use AssertJ: `assertThat(x).isInstanceOf(...)`, never `Assert.isInstanceOf(...)`
- When adding a new auto-configuration bean, add a corresponding `@ConditionalOnMissingBean` test in `FailoverAutoConfigurationTest` or the relevant `*AutoConfigurationTest` class