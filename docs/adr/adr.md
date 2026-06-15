---
icon: material/file-document-multiple-outline
---

# Architecture Decisions

!!! info "Immutable history"
    ADRs are append-only. Once accepted, content is never changed.
    Superseded decisions are marked **Deprecated** with a cross-reference.
    New decisions always get the next sequential number.

## ADR 1 — Build a failover lib

**Date : 10-NOV-2021**

### Status

Accepted

### Context

Most of our platforms are highly dependent on many referential systems (external api / internal api) for various business process.  
In such case any issues ( ex: unavailability ) on these referential systems will have a huge impact on our platform, both in prod and non prod.  
This can cause a huge impact on our platform resilience, where due to such issues on the referential systems or external systems (API) , our platform will become not usable.  

* Some of these referential won't change quite often or changes very rarely. Or some case business is ok for having slightly old data for their business continuity.
* If you have more such dependent services ( referential ) then the impact on your platforms wil be exponential

### Decision

Build a lib to handle the failover with very minimal changes in each project or services.  

* Keep a local store (persistence store) for storing the referential after every successful call.
* On failure , recover the referential information from the local store
* Keep an expiry policy for each referential, so that we don't serve the data once its expired
* Keep do the cleanup for old / expired data from the local store
* The expiry duration need to be decided with the business

### Consequences

* The expiry duration need to be decided with the business
* IT Team should not keep the long expiry for all referential without discussing with the business. If they do so, the platform can have very old data

**Challenges:**

* This may be needed for many services, so the lib need to be kept very simple.
  
___

## ADR 2 — @Failover Annotations

**Date : 10-NOV-2021**

### Status

Accepted

### Context

Use @Failover annotation for declaring the failover.
We could also leverage the FeignClient annotation, but having a dedicated annotation for @Failover will help the readability of the code.  
Each Failover must have a unique name and should also help the developers to configure the expiry.

### Decision

* **@Failover** annotation for declaring the failover. ex: ***@Failover(name = "client-all", expiryDuration = 1, expiryUnit = ChronoUnit.HOURS)***. The ***'name'*** must be unique, default value of expiry is 1 HOUR.

### Consequences

* This only works with spring AOP.
* The expiry need to be configured wisely, with the business acceptable expiry.

___

## ADR 3 — Metadata for referential : As Of , Up To Date ?

**Date : 15-NOV-2021**

### Status

Accepted

### Context

Most of the time, when we recover the referential from local store, it is important to keep below two information :

1. **As Of** : To mention how old is the local referential data on the local storage
2. **upToDate** : To mention whether the data is a live data or a recovered data

### Decision

* Build a lightweight module for domain. The domain module which has only 1 annotation, 1 interface, 1 class

1. Referential to extend Abstract **Referential** Class with asOf , upToDate fields

```java
@Data
public abstract class Referential implements Serializable {

    private Boolean upToDate;

    private Instant asOf;
}
```

1. Referential to implement **ReferentialAware** interface with setAsOf , setUpToDate methods

```java
public interface ReferentialAware {
    void setUpToDate(Boolean upToDate);
    void setAsOf(Instant asOf);
}
```

*
* If any of the above contract is applied , we will populate the information.
* These are optional, and if we did not apply any of these contract, the metadata information will be not applied  

### Consequences

* Failover Domain module dependency required for your service domain

___

## ADR 4 — Recovered Payload Handler

**Date : 15-NOV-2021**

### Status

Accepted

### Context

Some time, we won't be able to recover the referential , either we don't have the information in our local store , or the available information is too old and expired.
In this context, the framework return null and this may create an issue in the further processing of your code.  

Some case , if team want to return a default object instead of null.  

### Decision

* Provide a option to handle all recovered data.

```java
public interface RecoveredPayloadHandler {
    <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload);
}
```

* Each team can plug their own RecoveredPayloadHandler to handle all the recovered data ( in case of returning null or non-null data )
* By default, we provided PassThroughRecoveredPayloadHandler which does nothing, just pass through the same data.

```java
public class PassThroughRecoveredPayloadHandler implements RecoveredPayloadHandler {

    @Override
    public <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload) {
        return payload;
    }
}
```

### Consequences

* The custom RecoveredPayloadHandler implementation can impact the behaviour of your platform based on the implementation.

___

## ADR 5 — Failover Store

**Date : 16-NOV-2021**

### Status

Accepted

### Context

How do we store the data locally for recovery ? It will be better to provide some basic storage options as part of the lib.  

### Decision

* The lib provide below storage types :

1. **INMEMORY** : With basic ConcurrentHashMap implementation. We highly recommend to ***NOT USE*** this in ***Production***
2. **CAFFEINE** : With Caffeine cache implementation.
3. **JDBC** : With Jdbc implementation. This required ***JdbcTemplate*** and ***ObjectMapper*** beans
4. **CUSTOM** : Allows each service to provide a custom store.

* Both **store** & **delete** can be executed **asynchronously**

### Consequences

* The performance of the I/O operation on store and recover may impact the overall performance of your platform

___

## ADR 6 — Failover Execution

**Date : 17-NOV-2021**

### Status

Accepted

### Context

By default, provide a basic failover execution with a simple try catch.

### Decision

* We have provided below Failover Execution

1. BASIC : Basic failover execution with a simple try catch.
2. RESILIENCE : failover execution with resilience4j implementation. We highly recommend ***NOT TO CLUB*** this with other resilience or retry solutions.
3. CUSTOM : Allows each service to provide a custom Failover Execution.

* Make the failover execution as fault tolerant. Any exception on failover execution should not impact the actual business flow.

### Consequences

* Clubbing multiple resilience with RESILIENCE Failover Execution may impact the overall performance and behaviour of your platforms

___

## ADR 7 — Auto Cleanup

**Date : 17-NOV-2021**

### Status

Accepted

### Context

Provide a provision to auto cleanup the expired referential data from the referential store

### Decision

* A configurable scheduler to trigger a auto cleanup
* Default is 1 hour.
* This can configure from yml by providing a new cron expression
* **Auto cleanup** can be executed **asynchronously**

### Consequences

* Any custom expiry policy may not be applied on auto cleanup.
* After expiry cleanup, you may have no data to recover.

___

## ADR 8 — Monitoring

**Date : 17-NOV-2021**

### Status

Accepted

### Context

Provide useful metrics for monitoring  

### Decision

* Publish useful metrics for monitoring , which help us to create useful dashboard in Kibana

### Consequences

* NA

___

## ADR 9 — Key Generator

**Date : 30-DEC-2021**

### Status

Accepted

### Context

Provide an option to customize the key generator for a specific failover

### Decision

* Provide an option to declare the custom key generator bean name in @Failover
* Provide a KeyGenerator lookup features to get the Key Generator by a given name
* Provide a Failover composite key generator which select the proper Key Generator if mentioned, else to use the Default Key Generator.

### Consequences

* If the custom key generator (name) is missing, an exception may occur.

___

## ADR 10 — DefaultFailoverStore — Defensive Copy for Immutability

**Date : 25-MAY-2026**

### Status

Accepted

### Context

`FailoverStore` implementations hold `ReferentialPayload` instances in memory (ConcurrentHashMap, Caffeine cache).
If the same object reference is shared between the store and the caller, mutations on either side corrupt the stored state silently.
Additionally, data recovered from the failover store must always be distinguishable from a live response — a recovered payload must never appear as `upToDate=true`.

### Decision

Introduce `DefaultFailoverStore<T>` as a mandatory decorator around every concrete `FailoverStore`.

* **Before storing (`store`, `delete`)**: call `referentialPayload.copy().withUpToDate(false)` to write a defensive copy with `upToDate` forced to `false`. The caller retains their original object; the store holds its own independent copy.
* **Before returning (`find`)**: map the result through `copy().withUpToDate(false)` so the caller receives a fresh copy that cannot be used to mutate internal store state, and `upToDate` is always `false` for recovered data.
* **`copy()` contract** on `ReferentialPayload`: shallow copy of all fields. Payload reference is shared, but field-level mutations (name, key, upToDate, asOf, expireOn) on either side are isolated.
* `cleanByExpiry` delegates directly — no payload is produced, so no copy is needed.

```
store(payload)   →  delegate.store( payload.copy().withUpToDate(false) )
delete(payload)  →  delegate.delete( payload.copy().withUpToDate(false) )
find(name, key)  →  delegate.find(name, key).map( r -> r.copy().withUpToDate(false) )
cleanByExpiry    →  delegate.cleanByExpiry(expiry)   // passthrough
```

### Consequences

* Every store/find operation allocates one extra `ReferentialPayload` object — negligible overhead.
* `upToDate` is always `false` for data served from the failover store, regardless of what was stored. Callers can rely on this invariant unconditionally.
* Mutating the payload object received from `find()` has no effect on the store.
* `DefaultFailoverStore` is automatically applied to all `FailoverStore` beans via `FailoverStoreBeanPostProcessor` (see ADR 11) — no manual wiring required.

___

## ADR 11 — FailoverStoreBeanPostProcessor — Uniform Store Wrapping via BeanPostProcessor

**Date : 25-MAY-2026**

### Status

Deprecated — superseded by ADR 16, ADR 18, ADR 19

### Context

Every `FailoverStore` bean — whether auto-configured (INMEMORY, CAFFEINE, JDBC) or user-provided (CUSTOM) — must be consistently wrapped with:

1. `DefaultFailoverStore` — enforces `upToDate=false` and defensive copy (see ADR 10).
2. `FailoverStoreAsync` — makes `store`, `delete`, and `cleanByExpiry` asynchronous via Spring `@Async`.

Previously, each auto-configuration class manually constructed the wrapping chain, leading to duplication and risk of inconsistency for custom stores. User-defined `FailoverStore` beans received no wrapping at all.

### Decision

Implement `FailoverStoreBeanPostProcessor implements BeanPostProcessor` and register it as a `static @Bean` in `FailoverAutoConfiguration`.

**Wrapping rule** applied in `postProcessBeforeInitialization`:

```
if bean is FailoverStore
   AND NOT already FailoverStoreAsync
   AND NOT already DefaultFailoverStore
→ return new FailoverStoreAsync<>(new DefaultFailoverStore<>(bean))
otherwise
→ return bean unchanged
```

The guard prevents double-wrapping when the BPP encounters beans that are already part of the chain.

All auto-configuration `@Bean` methods (INMEMORY, CAFFEINE, JDBC) return the raw concrete store only. The BPP applies the wrapping uniformly for all.

**Why `postProcessBeforeInitialization` and not `postProcessAfterInitialization`:**

Spring's bean lifecycle runs in this order:

```
1. Bean instantiated
2. Dependencies injected
3. postProcessBeforeInitialization   ← BPP fires here, returns FailoverStoreAsync wrapper
4. @PostConstruct / afterPropertiesSet
5. postProcessAfterInitialization    ← Spring AOP (AsyncAnnotationBeanPostProcessor) runs here
6. Bean ready
```

By returning `FailoverStoreAsync` in step 3, Spring's `AsyncAnnotationBeanPostProcessor` (step 5) sees the wrapper as the bean and creates an AOP proxy around it, enabling `@Async` on its methods.
If wrapping happened in step 5 (`postProcessAfterInitialization`), the returned `FailoverStoreAsync` would be registered after AOP infrastructure has already run, and `@Async` would be silently skipped — `store`, `delete`, and `cleanByExpiry` would execute synchronously.

**Why `static @Bean`:**
`BeanPostProcessor` beans are instantiated very early in the Spring context lifecycle, before regular `@Configuration` class instances are created. Declaring the bean `static` avoids eager instantiation of the `@Configuration` class and prevents proxy-related issues.

### Consequences

* All `FailoverStore` beans — auto-configured or user-defined — get the same `FailoverStoreAsync → DefaultFailoverStore → concrete store` chain automatically.
* Custom store authors define only the raw `FailoverStore` implementation; wrapping is transparent. **CustomStore should not use any bean post construct or bean init**
* `FailoverStoreAsync` and `DefaultFailoverStore` themselves are excluded from re-wrapping by the guard.
* BPP ordering relative to `AsyncAnnotationBeanPostProcessor` is safe because `postProcessBeforeInitialization` always precedes AOP proxy creation.

___

## ADR 12 — MethodExceptionPolicy — Pluggable Exception Handling Strategy

**Date : 26-MAY-2026**

### Status

Accepted

### Context

When a primary call fails and failover recovery is attempted, the framework previously had a fixed outcome: swallow the original exception and return the recovered result (or `null` if recovery also failed).
This gave callers no way to control what happens post-recovery:

* Some teams want the original exception to propagate when the store has nothing to serve, so monitoring and alerting fire correctly.
* Some teams want silent degradation (return stale data or `null`) regardless of recovery success.
* Some teams need custom logic — enriching the exception, mapping it to a domain-specific type, publishing a metric.

### Decision

Introduce a `MethodExceptionPolicy` strategy interface to decide the final outcome after recovery is attempted.

```java
@FunctionalInterface
public interface MethodExceptionPolicy {
    <T> T handle(MethodExceptionContext<T> context);
}
```

`MethodExceptionContext<T>` carries all relevant information:

```java
public record MethodExceptionContext<T>(
        Failover failover,
        Method method,
        List<Object> args,
        @Nullable T recoveredResult,
        Throwable cause
) {}
```

Implementations may:

* Return `context.recoveredResult()` — serve stale data transparently.
* Return `null` — propagate nothing; let the caller handle absence.
* Rethrow `context.cause()` via sneaky throw — cascade the original exception.

Three built-in implementations are provided:

| Implementation                             | Behaviour                                                                   | Property value                                    |
|--------------------------------------------|-----------------------------------------------------------------------------|---------------------------------------------------|
| `RethrowIfNoRecoveryMethodExceptionPolicy` | Returns recovered result if non-null; rethrows original exception otherwise | `rethrow` *(default, property absent)*            |
| `NeverRethrowMethodExceptionPolicy`        | Always returns recovered result or `null`, never throws                     | `never_throw`                                     |
| Custom user bean                           | Any logic; registered as a Spring bean                                      | *(register bean, set `custom` for documentation)* |

The policy is resolved by auto-configuration using `failover.exception-policy` property.
A `MethodExceptionHandler` wraps the policy to add debug logging before delegating.

### Consequences

* Default behaviour (`RethrowIfNoRecoveryMethodExceptionPolicy`) is safe: stale data is preferred, but the original failure is surfaced when there is nothing to serve. This ensures monitoring fires on genuine outages with empty stores.
* `NeverRethrowMethodExceptionPolicy` gives a pure degraded-mode experience at the cost of silent failures.
* Any team can inject a custom `MethodExceptionPolicy` bean to override auto-configuration via `@ConditionalOnMissingBean`.
* The exception policy operates at the failover boundary only — exceptions thrown during store/recover operations are already logged and swallowed internally.

___

## ADR 13 — JDBC Native Merge/Upsert — Dialect Detection and Runtime Fallback

**Date : 26-MAY-2026**

### Status

Accepted

### Context

`FailoverStoreJdbc.store()` previously relied on INSERT-only for new records. Under concurrent writes to the same key this created a race between a SELECT-check and an INSERT, causing `DuplicateKeyException` noise and requiring careful retry logic. Additionally, a simple INSERT gives no atomicity guarantee when the same key is written from multiple threads or nodes.

Native merge/upsert operations (H2 `MERGE INTO…KEY`, PostgreSQL `ON CONFLICT DO UPDATE`, MySQL/MariaDB `ON DUPLICATE KEY UPDATE`, Oracle `MERGE USING DUAL`) provide atomic upsert semantics. However, each database uses a different syntax and not all databases support any of these forms.

Two failure scenarios must be handled:

1. **Build-time unknown dialect**: the detected product name does not match any known dialect (e.g. HSQLDB, DB2, or unrecognised proxies).
2. **Runtime dialect mismatch**: the product name reported at startup is incorrect (e.g. a middleware proxy reports `"PostgreSQL"` but the actual underlying DB rejects the query), causing a `BadSqlGrammarException` on the first `store()` call.

### Decision

1. At construction, `FailoverStoreJdbc` calls `FailoverStoreQueryResolver.getMergeQuery()`. If non-null, set `mergeEnabled = true` via `AtomicBoolean`. If null (unknown dialect), `mergeEnabled = false` from the start.
2. When `mergeEnabled.get()` is `true`, attempt the native merge SQL. On success, return immediately.
3. On `BadSqlGrammarException` at runtime: log a warning, call `mergeEnabled.set(false)` (permanent — `AtomicBoolean` ensures thread safety), then fall through to `insertOrUpdate`. All subsequent `store()` calls skip the merge block entirely — no per-call overhead.
4. `insertOrUpdate` uses INSERT first. On `DuplicateKeyException` it issues an UPDATE. This covers both the no-merge and the concurrent-write-on-same-key cases.

```
store(payload):
  if mergeEnabled:
    try → jdbcTemplate.update(mergeQuery, …)   → done
    catch BadSqlGrammarException → mergeEnabled = false
  insertOrUpdate(payload):
    try → INSERT
    catch DuplicateKeyException → UPDATE
```

The four supported native dialects:

| Database          | Strategy                                        |
|-------------------|-------------------------------------------------|
| H2                | `MERGE INTO … KEY(FAILOVER_NAME, FAILOVER_KEY)` |
| PostgreSQL        | `INSERT … ON CONFLICT (…) DO UPDATE SET …`      |
| MySQL / MariaDB   | `INSERT … ON DUPLICATE KEY UPDATE …`            |
| Oracle            | `MERGE INTO … USING (SELECT … FROM DUAL) …`     |
| Everything else   | `mergeQuery = null` → INSERT + UPDATE fallback  |

### Consequences

* Atomic upsert with zero additional SELECT round-trip for all four supported databases.
* Unknown databases fall back gracefully to INSERT + UPDATE — no configuration change required.
* A single `BadSqlGrammarException` permanently disables merge for the lifetime of the bean. Subsequent calls pay only one `update()` (INSERT) instead of two, recovering performance after the one-time failure.
* The `AtomicBoolean` flip is thread-safe: multiple concurrent threads may each throw `BadSqlGrammarException` and call `set(false)` simultaneously — this is safe and idempotent.
* Adding a new dialect requires only a new SQL constant and a new `contains()` branch in `DefaultFailoverStoreQueryResolver.resolveMergeQuery()`.

___

## ADR 14 — DatabaseResolver — Strategy Interface for Database Product Detection

**Date : 26-MAY-2026**

### Status

Accepted

### Context

Database dialect selection (ADR 13) requires knowing the database product name at startup. Previously this detection logic was embedded directly inside `FailoverStoreJdbc` constructor, which caused three problems:

1. **Untestable in isolation**: unit tests for `FailoverStoreJdbc` required a live `DataSource` to exercise dialect selection.
2. **Not overridable**: applications using proxies, middleware (e.g. PgBouncer), or test environments where the reported product name is incorrect had no way to override detection.
3. **Single responsibility violation**: `FailoverStoreJdbc` held unrelated JDBC metadata concerns alongside store operations.

### Decision

Extract a `DatabaseResolver` strategy interface with a single no-argument method:

```java
public interface DatabaseResolver {
    @Nullable
    String resolve();
}
```

- `DefaultDatabaseResolver` receives a `JdbcTemplate` via constructor injection and reads `conn.getMetaData().getDatabaseProductName()` via `jdbcTemplate.execute(ConnectionCallback)`. Any `Exception` during detection is caught, a warning is logged, and `null` is returned — triggering the INSERT/UPDATE fallback.
- `DatabaseResolver` is exposed as a `@ConditionalOnMissingBean` Spring bean in `FailoverJdbcStoreAutoConfiguration`. Users override by declaring their own bean of this type.
- `DatabaseResolver.resolve()` takes no arguments. The `JdbcTemplate` is an injected constructor dependency, not a parameter. This keeps the interface minimal, consistent with the strategy pattern, and avoids the caller needing to pass infrastructure dependencies.
- `DefaultFailoverStoreQueryResolver` receives `DatabaseResolver` via constructor injection and calls `resolve()` exactly once during its own construction to select and cache the merge SQL string.

### Consequences

* `DefaultFailoverStoreQueryResolver` is fully unit-testable: mock or stub `DatabaseResolver.resolve()` without a live datasource.
* `DefaultDatabaseResolver` is independently unit-testable: mock `JdbcTemplate` to simulate product name retrieval and failure paths.
* Applications with non-standard environments (proxies, cloud SQL, test doubles) override detection by providing a single-bean `DatabaseResolver` implementation.
* `resolve()` is called once at construction — no repeated metadata queries per `store()` call.
* Returning `null` is the defined contract for "unknown" — all consumers treat `null` as "disable merge/use fallback".

___

## ADR 15 — FailoverStoreQueryResolver — Single-Responsibility Co-location of All JDBC Query Concerns

**Date : 26-MAY-2026**

### Status

Accepted

### Context

`FailoverStoreJdbc` originally held SQL strings, parameter arrays, SQL type arrays, and `ResultSet` mapping inline. This caused several issues:

1. **Fragile column ordering**: SQL column order, `Object[]` parameter order, and `int[]` type order must always agree. When scattered across a class, a column rename or reorder requires coordinated changes in at least three places, with no compile-time safety net.
2. **Untestable SQL logic**: verifying that INSERT column order matches the UPDATE SET/WHERE order, or that all SQL placeholders `?` match the parameter array length, required a running database.
3. **Not replaceable**: users who needed custom SQL (different schema, encrypted payload column, additional audit columns) had no extension point short of replacing the entire `FailoverStoreJdbc` bean.
4. **Dialect SQL embedded in the store**: merge-dialect SQL templates lived alongside CRUD operation code, mixing concerns.

### Decision

Extract a `FailoverStoreQueryResolver` interface that owns every JDBC query concern:

```java
public interface FailoverStoreQueryResolver {
    String  getInsertQuery();
    String  getUpdateQuery();
    String  getSelectQuery();
    String  getDeleteQuery();
    String  getCleanUpQuery();
    @Nullable String getMergeQuery();

    <T> Object[] buildInsertMergeParams(ReferentialPayload<T> payload);
    int[]        buildInsertMergeTypes();

    <T> Object[] buildUpdateParams(ReferentialPayload<T> payload);
    int[]        buildUpdateTypes();

    <T> ReferentialPayload<T> mapRow(ResultSet rs) throws SQLException;
    @Nullable <T> T deserializePayload(@Nullable String payload, String clazzString);
}
```

`DefaultFailoverStoreQueryResolver` implements this interface with:

- All SQL templates as private `static final` constants with a `%PREFIX%` placeholder substituted at construction time.
- Parameter builders (`buildInsertMergeParams`, `buildUpdateParams`) and type builders (`buildInsertMergeTypes`, `buildUpdateTypes`) declared adjacent to their SQL templates — column order is enforced by co-location.
- `mapRow` and `deserializePayload` co-located with the SQL they serve.
- **No I/O dependencies**: `DefaultFailoverStoreQueryResolver` holds `ObjectMapper` and `PayloadColumnResolver` — no `JdbcTemplate`, no `DataSource`. It is instantiated and unit-tested without a running database.

`FailoverStoreJdbc` depends only on the `FailoverStoreQueryResolver` interface — not on `DefaultFailoverStoreQueryResolver` directly.

`FailoverStoreQueryResolver` is registered as a `@ConditionalOnMissingBean` Spring bean in `FailoverJdbcStoreAutoConfiguration`. Users replace the entire query layer by providing a single bean of this type.

### Consequences

* SQL text, parameter order, and SQL types for each operation live in one class. A column change requires editing exactly one file.
* `DefaultFailoverStoreQueryResolver` is fully unit-tested without a database: placeholder count consistency, column order, dialect selection, parameter binding, row mapping, and deserialization edge cases are all covered by pure unit tests.
* `FailoverStoreJdbc` is reduced to pure JDBC execution logic — it knows nothing about SQL, column types, or serialization.
* Teams that need a different schema (additional columns, different key structure, encrypted payload, non-JSON serialization) implement `FailoverStoreQueryResolver` and declare the bean — no other changes required.
* `PayloadColumnResolver` remains a separate, narrower extension point for teams that only need to change the payload column SQL type and extraction method, without replacing the entire resolver.

___

## ADR 16 — Removal of BeanPostProcessor-based Store Wrapping (Supersedes ADR 11)

**Date : 02-JUN-2026**

### Status

Accepted — supersedes ADR 11

### Context

ADR 11 introduced `DefaultFailoverStoreBeanPostProcessor` and `AsyncFailoverStoreBeanPostProcessor` registered as `static @Bean` entries inside `FailoverAutoConfiguration` to intercept raw `FailoverStore` beans and wrap them with `DefaultFailoverStore → FailoverStoreAsync`.

This approach had two critical problems once multi-tenancy was introduced:

1. **BPP only intercepts Spring-managed beans.** In multi-tenant mode, per-tenant `FailoverStore` instances are created programmatically inside a `TenantStoreFactory` — they are never registered as individual Spring beans and therefore invisible to any `BeanPostProcessor`. The async/defensive-copy wrapping would have been silently skipped for every tenant except the first.
2. **@Async depends on Spring AOP proxy.** `FailoverStoreAsync` previously used `@Async` on its methods to offload work. `@Async` only functions when the call goes through a Spring-managed AOP proxy. A `FailoverStoreAsync` instance created inside a factory lambda is not a proxy — `@Async` degrades to synchronous execution silently with no error or warning.

### Decision

Remove `DefaultFailoverStoreBeanPostProcessor` and `AsyncFailoverStoreBeanPostProcessor` entirely.

Store wrapping is now performed **explicitly** inside `FailoverStoreAutoConfiguration` (see ADR 18) — not via BPP interception. The assembler directly constructs:

```
FailoverStoreAsync(DefaultFailoverStore(rawStore), taskExecutor)
```

for async mode, or:

```
DefaultFailoverStore(rawStore)
```

for sync mode (`failover.store.async=false`).

The inner `@EnableAsync` nested configuration class (`AsyncBeanProcessorConfiguration`) is also removed. `FailoverStoreAsync` no longer uses `@Async`; it holds an explicit `TaskExecutor` (see ADR 19).

### Consequences

* Wrapping chain is explicit, deterministic, and visible at a single assembly point — no implicit BPP intercept magic.
* Multi-tenant per-tenant stores receive the same wrapping chain as single-tenant stores (see ADR 20).
* ADR 11 is deprecated. The threading and ordering guarantees it relied on (`postProcessBeforeInitialization` ordering vs. `AsyncAnnotationBeanPostProcessor`) are no longer relevant.
* Removing `@EnableAsync` from the autoconfigure module means applications must provide `@EnableAsync` themselves if they need `@Async` elsewhere — this is the correct responsibility boundary.

___

## ADR 17 — TenantStoreFactory SPI — Abstracting Store Creation from Store Assembly

**Date : 02-JUN-2026**

### Status

Accepted

### Context

Previously each store auto-configuration (`FailoverCaffeineStoreAutoConfiguration`, `FailoverJdbcStoreAutoConfiguration`, `FailoverAutoConfiguration`) produced a fully assembled `FailoverStore<Object>` bean directly. This fused two separate concerns:

1. **How to create a raw store** (which implementation, with which configuration).
2. **How to assemble the decorator chain** (DefaultFailoverStore + FailoverStoreAsync).

When multi-tenancy was introduced, each tenant needed its own isolated raw store instance (separate Caffeine cache, separate JDBC table prefix). Producing a single fully-assembled `FailoverStore` bean from a store auto-configuration was incompatible with this: the bean registry holds one bean, but multiple tenant stores must exist simultaneously and be created on demand.

### Decision

Introduce `TenantStoreFactory<T>` as a `@FunctionalInterface` SPI:

```java
@FunctionalInterface
public interface TenantStoreFactory<T> {
    String SINGLE_TENANT_ID = "_single_";
    FailoverStore<T> create(String tenantId);
}
```

All store auto-configurations now register a `TenantStoreFactory<Object>` bean (annotated `@ConditionalOnMissingBean(TenantStoreFactory.class)`) instead of a `FailoverStore<Object>` bean directly:

| Auto-config | Old bean type | New bean type |
|---|---|---|
| `FailoverAutoConfiguration` (inmemory) | `FailoverStore<Object>` | `TenantStoreFactory<Object>` |
| `FailoverCaffeineStoreAutoConfiguration` | `FailoverStore<Object>` | `TenantStoreFactory<Object>` |
| `FailoverJdbcStoreAutoConfiguration` | `FailoverStore<Object>` | `TenantStoreFactory<Object>` |

The factory's `create(tenantId)` method is **always called on the calling (request) thread**, never inside an executor lambda. This is a hard contract: implementations may safely read `ThreadLocal` values (e.g. to select a `DataSource`) during `create()`, but must not capture them for later use on another thread.

In single-tenant mode, `create(SINGLE_TENANT_ID)` is called once at application startup by `FailoverStoreAutoConfiguration`.

### Consequences

* Store creation and store assembly are decoupled. Adding a new store type requires implementing `TenantStoreFactory` only; the decorator chain is applied uniformly by the assembler.
* Multi-tenant configurations override the auto-configured `TenantStoreFactory` via `@ConditionalOnMissingBean(TenantStoreFactory.class)` — no changes to `FailoverStoreAutoConfiguration`.
* Custom store authors implement `TenantStoreFactory` (not `FailoverStore` directly) when registering custom stores with multi-tenant awareness. For single-tenant custom stores, `() -> myStore` is sufficient since `SINGLE_TENANT_ID` is the only argument ever passed.
* `SINGLE_TENANT_ID = "_single_"` is a sentinel chosen to be unlikely to collide with real tenant IDs. It is not validated — implementations may ignore it.

___

## ADR 18 — FailoverStoreAutoConfiguration — Central Assembler

**Date : 02-JUN-2026**

### Status

Accepted

### Context

After introducing `TenantStoreFactory` (ADR 17), the store auto-configurations produce a factory, not a ready `FailoverStore`. Something must consume the factory and produce the single `FailoverStore<Object>` bean that the rest of the framework depends on.

Previously this assembly was implicit via `BeanPostProcessor` interception (ADR 11, now removed). With explicit assembly, a dedicated `@AutoConfiguration` class is the correct location.

### Decision

Introduce `FailoverStoreAutoConfiguration` as the single assembly point for the `FailoverStore<Object>` bean.

It runs `after` all store-type auto-configurations and `FailoverAutoConfiguration`, so `TenantStoreFactory` is guaranteed to be present.

Two conditional `@Bean` methods produce `failoverStore`:

**Async mode (default, `failover.store.async=true` or property absent):**

```java
new FailoverStoreAsync<>(
    new DefaultFailoverStore<>(storeFactory.create(SINGLE_TENANT_ID)),
    failoverTaskExecutor
)
```

A `failoverTaskExecutor` bean (`SimpleAsyncTaskExecutor` with virtual threads on JDK 21+) is also registered, overridable by name.

**Sync mode (`failover.store.async=false`):**

```java
new DefaultFailoverStore<>(storeFactory.create(SINGLE_TENANT_ID))
```

Both beans are guarded by `@ConditionalOnBean(TenantStoreFactory.class)` and `@ConditionalOnMissingBean(FailoverStore.class)`, so custom `FailoverStore` beans still take precedence.

In multi-tenant mode, `FailoverStoreMultiTenantAutoConfiguration` registers `MultiTenantFailoverStore` as the `FailoverStore` bean — which satisfies `@ConditionalOnMissingBean(FailoverStore.class)`, causing this assembler to skip its own bean registration.

### Consequences

* Assembly is visible in one class — no implicit interception.
* Async vs. sync is a single YAML property (`failover.store.async`), not two separate auto-configurations or BPP registrations.
* `failoverTaskExecutor` uses virtual threads by default (JDK 21+); applications override by declaring a `TaskExecutor` bean named `failoverTaskExecutor`.
* `@ConditionalOnMissingBean(FailoverStore.class)` means applications that declare their own `FailoverStore` bean bypass this assembler entirely — same behaviour as before.

___

## ADR 19 — FailoverStoreAsync — Explicit TaskExecutor Replacing @Async

**Date : 02-JUN-2026**

### Status

Accepted

### Context

`FailoverStoreAsync` previously used `@Async` on `store`, `delete`, and `cleanByExpiry` to offload writes to a Spring-managed thread pool.

`@Async` has a critical limitation: it only works when the call is dispatched through a Spring AOP proxy. An instance created programmatically — such as one created inside a `TenantStoreFactory.create()` lambda — is not a Spring proxy and `@Async` degrades to synchronous, in-thread execution with no warning.

In multi-tenant mode, each tenant's `FailoverStoreAsync` is created programmatically inside the decorator lambda in `MultiTenantFailoverStore`. Every write operation would have silently become synchronous, undoing the performance benefit of the async store.

Additionally, `@Async` relies on `@EnableAsync` being active in the application context, which imposed a constraint on the autoconfiguration module (it had to include `@EnableAsync` in a nested configuration class). Removing this is desirable: `@EnableAsync` is a cross-cutting concern that belongs to the application, not the library.

### Decision

Remove `@Async` from all methods in `FailoverStoreAsync`. Inject an explicit `TaskExecutor` as a constructor parameter:

```java
@RequiredArgsConstructor
public class FailoverStoreAsync<T> implements FailoverStore<T> {
    private final FailoverStore<T> failoverStore;
    private final TaskExecutor executor;

    @Override
    public void store(ReferentialPayload<T> payload) {
        executor.execute(() -> failoverStore.store(payload));
    }
    // delete and cleanByExpiry: same pattern
    // find: synchronous, unchanged
}
```

**Threading contract in lambda:**
Each lambda captures only method arguments — never any `ThreadLocal` values. Tenant routing (when multi-tenant) is performed by `MultiTenantFailoverStore` on the calling thread, before `FailoverStoreAsync.store()` is reached. By the time the lambda executes on the executor thread, `this.failoverStore` is already scoped to the correct tenant — no re-resolution is needed.

`@Async` and `@EnableAsync` are removed from the autoconfigure module entirely.

### Consequences

* `FailoverStoreAsync` works correctly whether instantiated as a Spring bean or programmatically inside a factory — behaviour is identical in both cases.
* `@EnableAsync` is no longer imposed on the application context by the library. Applications that need `@Async` for their own beans must add `@EnableAsync` themselves (same as any other Spring project).
* The executor used for failover async operations is the `failoverTaskExecutor` bean registered by `FailoverStoreAutoConfiguration` (virtual threads on JDK 21+, overridable).
* `find()` remains synchronous — the caller needs the result immediately and there is no asynchrony benefit.
* `cleanByExpiry()` is now also submitted to the executor — previously it was `@Async` but with the same silent-degradation risk in non-Spring-managed instances.

___

## ADR 20 — MultiTenantFailoverStore — Outermost Per-Tenant Routing Decorator

**Date : 02-JUN-2026**

### Status

Accepted

### Context

Multi-tenant applications need to isolate failover data per tenant: tenant A must not read or write tenant B's referential store, regardless of the backing store technology.

The decorator chain for a single tenant is `FailoverStoreAsync(DefaultFailoverStore(rawStore))`. Multi-tenancy requires this chain to be replicated per tenant, with routing at the outermost layer so that every operation is directed to the correct tenant's chain before any thread boundary is crossed.

Key constraints:

1. **ThreadLocal must be read on the calling thread.** `TenantResolver` reads tenant context from a `ThreadLocal` (or HTTP request). Once the operation enters `FailoverStoreAsync`'s executor, the `ThreadLocal` may not be set.
2. **Per-tenant stores must be created lazily.** The full set of tenants is not always known at startup, and eagerly creating stores for all tenants may be wasteful or impossible.
3. **`cleanByExpiry` is called by the scheduler** (not a request thread) — no tenant context is available. It must clean all initialized tenant stores.

### Decision

Introduce `MultiTenantFailoverStore<T> implements FailoverStore<T>` as the **outermost** decorator in the chain. It sits above `FailoverStoreAsync` and `DefaultFailoverStore`:

```
MultiTenantFailoverStore (routing, outermost)
  ├─ tenant-a → FailoverStoreAsync(DefaultFailoverStore(rawStore-a))
  └─ tenant-b → FailoverStoreAsync(DefaultFailoverStore(rawStore-b))
```

Internal structure:

```java
@RequiredArgsConstructor
public class MultiTenantFailoverStore<T> implements FailoverStore<T> {
    private final TenantResolver tenantResolver;
    private final TenantStoreFactory<T> rawFactory;
    private final UnaryOperator<FailoverStore<T>> decorator;  // applies Default + Async chain
    private final String defaultTenant;
    private final ConcurrentHashMap<String, FailoverStore<T>> stores = new ConcurrentHashMap<>();
}
```

**Routing:** `tenantStore()` is called on the calling thread. It calls `tenantResolver.resolve()`, falls back to `defaultTenant` if `null`, throws `FailoverStoreException` if both are null. `computeIfAbsent` ensures each tenant's chain is built exactly once (thread-safe).

**Decorator injection:** The `decorator` (`UnaryOperator<FailoverStore<T>>`) is injected by `FailoverStoreMultiTenantAutoConfiguration` and mirrors the single-tenant assembly: `raw -> new FailoverStoreAsync<>(new DefaultFailoverStore<>(raw), executor)`.

**`cleanByExpiry`:** Calls `cleanByExpiry` on every store currently in the `stores` map — no tenant context needed. Tenants not yet accessed are not cleaned (they have no data).

**Pre-warming:** `prewarm(Set<String> tenantIds)` can be called at startup to initialise stores for known tenants, ensuring `cleanByExpiry` covers them from the first scheduler tick.

### Consequences

* Tenant resolution always occurs on the calling thread — `ThreadLocal` contract is preserved.
* Per-tenant stores are created lazily on first access; `SINGLE_TENANT_ID` is never used in multi-tenant mode.
* `cleanByExpiry` is fan-out across all initialized tenants. Tenants receiving their first request after the cleanup tick will skip one cleanup cycle — acceptable because an empty store has nothing to expire.
* `defaultTenant` allows applications to configure a fallback for unauthenticated or system requests without throwing an exception.
* `decorator` parameter makes `MultiTenantFailoverStore` independent of specific decorator implementations — it can be unit-tested with a simple identity decorator.

___

## ADR 21 — FailoverStoreMultiTenantAutoConfiguration — Multi-Tenant Auto-Configuration and TenantResolver SPI

**Date : 02-JUN-2026**

### Status

Accepted

### Context

Multi-tenant store support (ADR 20) requires configuration wiring: the `MultiTenantFailoverStore` bean must be assembled with the correct `TenantStoreFactory`, `TenantResolver`, and decorator function. Additionally, JDBC isolation strategy and per-tenant parameters must be configurable via YAML.

Tenant resolution strategy (HTTP header, security context, custom logic) varies by application — the library cannot provide a universal default without pulling in inappropriate dependencies (Spring Security, Servlet API, etc.).

### Decision

**Auto-configuration:** Introduce `FailoverStoreMultiTenantAutoConfiguration`, activated by `failover.store.multitenant.enabled=true`. It:

1. Registers a multitenant `TenantStoreFactory` (one per store type) via `@ConditionalOnMissingBean(TenantStoreFactory.class)` — overrides the single-tenant factory from the store-type auto-configurations.
2. Assembles and registers `MultiTenantFailoverStore` as the `FailoverStore<Object>` bean, satisfying `@ConditionalOnMissingBean(FailoverStore.class)` in `FailoverStoreAutoConfiguration`, preventing double assembly.
3. Injects the decorator function that mirrors single-tenant assembly: `raw -> new FailoverStoreAsync<>(new DefaultFailoverStore<>(raw), executor)`.

**TenantResolver SPI:** `TenantResolver` is a `@FunctionalInterface` with a single `resolve()` method returning the current tenant ID (or `null`). The library provides:

- `TenantContextTenantResolver` — reads from `TenantContext` (a `ThreadLocal` holder).
- `FixedTenantResolver` — always returns a literal (useful for testing).

The application must provide the `TenantResolver` bean. The autoconfiguration declares `@ConditionalOnBean(TenantResolver.class)` — if absent, the multi-tenant configuration does not activate.

**JDBC isolation strategies** (`MultiTenant.JdbcMultiTenantStrategy`):

| Strategy | How it works | What the application provides |
|---|---|---|
| `TABLE_PREFIX` (default) | `effectivePrefix = tenantPrefix + globalPrefix`; e.g. `ACME_` + `DEMO_` = `ACME_DEMO_FAILOVER_STORE` | Per-tenant `tablePrefix` in `failover.store.multitenant.tenants.<id>.table-prefix` |
| `SCHEMA` | Application provides an `AbstractRoutingDataSource` that routes to the correct schema using `TenantContext.get()` as the lookup key | Application-managed `DataSource` routing |

**Properties:**

```yaml
failover:
  store:
    multitenant:
      enabled: true
      strategy: table-prefix        # or schema
      default-tenant: default       # fallback when TenantResolver returns null
      tenants:
        acme:
          table-prefix: ACME_
        globex:
          table-prefix: GLOBEX_
```

### Consequences

* Zero impact on existing single-tenant deployments: `failover.store.multitenant.enabled` defaults to `false`.
* Applications must declare a `TenantResolver` bean; the library does not auto-detect tenant context to avoid opinionated dependencies.
* `TABLE_PREFIX` strategy works with a single shared `DataSource` and `JdbcTemplate` — no infrastructure changes required.
* `SCHEMA` strategy delegates all routing to the application's `DataSource` — the library performs no schema-level work beyond using the provided `JdbcTemplate`.
* Per-tenant `TenantConfig` entries are optional for Caffeine and InMemory stores; only JDBC uses `tablePrefix`.
* `defaultTenant` prevents exceptions for unauthenticated or internal requests that have no tenant context, at the cost of routing all such requests to a shared store.

___

## ADR 22 — FailoverKeyGenerator — UUID-Based Key Normalisation for Fixed-Width Store Keys

**Date : 03-JUN-2026**

### Status

Accepted

### Context

`FailoverKeyGenerator` originally returned the raw key produced by the delegate `KeyGenerator` directly to the store (initial commit, 2022). No transformation was applied.

This created two latent problems as the library's store options grew:

1. **JDBC column overflow.** The `FAILOVER_KEY` column in the JDBC schema is `VARCHAR(256)`. `DefaultKeyGenerator` builds raw keys by joining all method arguments — a single `Collection<Long>` with hundreds of IDs produces a key far exceeding 256 characters. Storing such a key causes either a silent `DataTruncation` or a hard `SQLException`, with no warning at configuration time.

2. **No cross-store key-size contract.** InMemory and Caffeine stores impose no column-width constraint, but the JDBC store does. With no normalisation at the framework level, the maximum safe key length was an invisible coupling between `DefaultKeyGenerator`'s output and the JDBC schema. Custom `KeyGenerator` implementors had to know this limit; nothing enforced it.

Additionally, raw keys built from argument values exposed business data (IDs, names, codes) in the store's key column — a minor but avoidable privacy concern.

### Decision

Introduce a `generateFinalKey` step in `FailoverKeyGenerator` that wraps the raw key from any delegate `KeyGenerator` in a deterministic type-3 UUID before returning:

```java
private String generateFinalKey(Failover failover, String tempKey) {
    var key = failover.name() + ":" + tempKey;
    return UUID.nameUUIDFromBytes(key.getBytes(UTF_8)).toString();
}
```

**Why `UUID.nameUUIDFromBytes`:**

- Produces a type-3 UUID (MD5-based, RFC 4122 §4.3). Output is always exactly 36 characters (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`), regardless of input size.
- Deterministic: same byte input → same UUID every invocation, across JVM.
- Character Encoding: The result of string.getBytes() depends on the default charset of the system. To ensure you get the same UUID across different machines or environments, it is safer to specify a fixed encoding (UUID.nameUUIDFromBytes("string".getBytes(StandardCharsets.UTF_8)); otherwise, you might get different UUIDs if the default charset differs.)
- No collision risk for typical failover use cases: the effective namespace is `failover.name()` prefixed, and the method-argument space per failover is small relative to the 2^128 UUID space.

**Why `failover.name()` is included in the hashed string:**
Two failovers with different names but identical method arguments must produce different store keys to avoid cross-failover data leakage. Including `failover.name()` as a namespace prefix before hashing ensures this isolation without adding a separate lookup.

**UTF-8 encoding:** `key.getBytes(UTF_8)` is explicit rather than platform-default, ensuring identical byte sequences — and therefore identical UUIDs — across JVMs with different default charsets.

The change is applied uniformly: both the default path (`defaultKeyGenerator`) and the custom-generator path (`keyGeneratorLookup`) pass through `generateFinalKey`. The delegate `KeyGenerator` contract is unchanged — implementations continue to return an arbitrary string.

### Consequences

* All store keys are exactly 36 characters. `FAILOVER_KEY VARCHAR(256)` is always satisfied with 220 characters to spare, regardless of argument size or custom `KeyGenerator` output.
* Custom `KeyGenerator` implementors are freed from managing key length: any string they return is hashed. Length and character-set restrictions on the raw key are eliminated.
* Keys are deterministic and stable: the same `failover.name()` and raw key always map to the same UUID. No randomness is introduced.
* The raw key string is never stored. Business data in method arguments (IDs, codes, names) does not appear in the store's key column.
* Two failovers with the same arguments but different names produce different stored keys — cross-failover isolation is enforced at the hashing step.
* Keys are not human-readable in the store. Debugging which stored row corresponds to which call requires re-computing `UUID.nameUUIDFromBytes((name + ":" + rawKey).getBytes(UTF_8))` externally. Logging the raw key before hashing (at the `KeyGenerator` level) is the recommended debugging pattern.
* Collision probability via MD5 is negligible at failover-argument cardinalities, but MD5 is not cryptographically strong. This is acceptable: the UUID is a cache key, not a security token. No authentication or authorisation decision is made from it.

___

## ADR 23 — PayloadSplitter — Scatter/Gather Storage for Composite-Key Failover

**Date : 04-JUN-2026**

### Status

Accepted

### Context

Methods annotated with `@Failover` that accept a comma-separated string (e.g. `ids = "1,2,3,4"`)
or a collection argument are stored under a single composite key derived from the entire argument.
This causes two classes of cache misses:

1. **Subset miss**: a caller requests `"1,2,3"` but the cached entry was stored under `"1,2,3,4"` — different UUID, no hit.
2. **Superset miss**: a caller requests `"1,2,3,4,5"` but only `"1,2,3,4"` is cached — miss, even though four of the five items are available.

For batch-fetch patterns (fetch-by-ids), this means a failover cache is only useful if the
calling site uses the exact same ID set as the last successful call. In practice, the cache is
rarely useful.

### Decision

Introduce a `PayloadSplitter<T>` extension point that enables **scatter/gather mode** when the
`@Failover(payloadSplitter = "beanName")` attribute is populated.

**Core data model:**

- `SplitterContext(List<Object> args)` — a record that wraps the argument list for _one_ cache
  entry (composite or individual).
- `SplitResult<T>(SplitterContext context, T payload)` — pairs one individual context with its
  payload slice (used on the store path only).

**Interface:**

```java
public interface PayloadSplitter<T> {
    List<SplitterContext> extract(SplitterContext context);          // recover path
    List<SplitResult<T>>  split(SplitterContext context, T payload); // store path
    T merge(List<T> items);                                          // recover path
}
```

**Store path (`DefaultFailoverHandler.store`):** calls `split()` to decompose the composite
payload into individual slices. Each `SplitResult.context().args()` is passed to the existing
`FailoverKeyGenerator` chain to produce an individual UUID key. Each slice is stored
independently under its own key.

**Recover path (`DefaultFailoverHandler.recover`):** calls `extract()` to decompose the
composite `SplitterContext` into individual contexts. Each individual context's args are passed
to the same `FailoverKeyGenerator` chain. Available (non-expired) slices are collected and
passed to `merge()`. Partial recovery (some IDs cached, others not) is handled gracefully — the
merge of available slices is returned rather than null.

**Key-generation guarantee:** `SplitterContext.args()` is passed directly to
`keyGenerator.key(failover, args)`, which flows through `FailoverKeyGenerator.generateFinalKey`.
Any future change to the UUID derivation algorithm (encoding, prefix, hash) automatically
applies to scatter-path keys with no additional implementation work.

**Backward compatibility:** `payloadSplitter` defaults to `""`. All existing `@Failover` usages
without this attribute follow the unchanged single-key path.

### Consequences

**Easier:**

- Batch-fetch failover now works across any subset or superset of IDs, not only the exact
  previously-seen combination.
- Partial recovery: if 3 of 4 IDs are cached and the primary fails, the caller receives the
  3 cached items rather than null.
- Future key-generation changes (hash algorithm, prefix format) are automatically inherited by
  the scatter path because it reuses `FailoverKeyGenerator`.
- The extension point is purely additive — no existing API changes.

**More complex:**

- Implementors must keep `splitOnRecover()` and `splitOnStore()` in sync: the contexts returned
  by each must match for the same composite input.
- `merge()` must handle slices whose recovered payload is null (cache miss on individual entry).
- Each scatter-store call writes N store entries instead of 1 — write amplification proportional
  to the number of IDs in the batch.
- Store expiry operates per-slice: if individual slices expire at different wall-clock times (due
  to separate store calls), a gather may see mixed freshness.

___

## ADR 24 — Parallel Scatter/Gather — CompletableFuture with Injected Executor

**Date: 04-JUN-2026**

### Status

Accepted

### Context

The scatter/gather path in `ScatterGatherFailoverHandler` dispatches N slice operations (store
or recover) sequentially. For I/O-bound stores (JDBC, remote cache) with many slices, sequential
dispatch limits throughput to the sum of individual slice latencies. A 10-slice batch with 50ms
per slice costs 500ms in the sequential model but only ~50ms in the parallel model.

### Decision

`ScatterGatherFailoverHandler` accepts an optional `java.util.concurrent.Executor` via
constructor injection. When non-null, slice operations are dispatched as `CompletableFuture`
tasks via that executor. `CompletableFuture.allOf(...).join()` gates the calling thread until
all slices complete, preserving the synchronous contract of `store()` and `recover()`.

**Auto-configuration** (activated by `failover.scatter.parallel=true`) registers a
`SimpleAsyncTaskExecutor` with virtual threads enabled. This avoids platform-thread pool
exhaustion under high parallelism for I/O-bound workloads.

**Why not ForkJoinPool / parallelStream?**  
ForkJoin is optimised for CPU-bound recursive decomposition. Slice operations are I/O-bound
(store hits a JDBC or cache backend). Using the common ForkJoin pool risks pool starvation.
Injecting the executor keeps the concurrency strategy configurable and testable.

**Backward compatibility:** passing `null` executor (the 3-arg constructor default) retains
sequential dispatch. No existing behaviour changes without explicit opt-in.

```java
// Sequential (default):
new ScatterGatherFailoverHandler<>(delegateT, delegateR, payloadSplitterLookup);

// Parallel with virtual threads:
// failover.scatter.parallel=true  →  auto-configured
```

### Consequences

**Easier:**

- Slice latency reduced from O(N) to O(1) for I/O-bound stores under parallel mode.
- Thread strategy is fully configurable — users can supply their own named `Executor` bean
  (`scatterGatherExecutor`) to tune pool size, rejection policy, or monitoring.

**More complex:**

- Thread-local context (tenant ID, MDC, security) is not propagated to executor threads by
  default — requires explicit `ContextPropagator` (see ADR 25).
- Any slice failure causes the entire `CompletableFuture.allOf().join()` to throw — there is
  no partial-success path in the current implementation.

___

## ADR 25 — ContextPropagator SPI — Thread-Local Context Propagation for Parallel Scatter

**Date: 04-JUN-2026**

### Status

Accepted

### Context

Parallel scatter (ADR 24) dispatches slice operations to executor threads. Virtual threads and
platform thread pools do not inherit `ThreadLocal` values from the submitting thread. This
breaks two existing mechanisms:

1. **`TenantContext`** — `MultiTenantFailoverStore.tenantStore()` reads `TenantContext.get()`
   on the calling thread. On an executor thread this returns `null`, causing fallback to the
   `defaultTenant` and routing all slices to the wrong per-tenant store.
2. **MDC** — SLF4J Mapped Diagnostic Context (carrying `traceId`, `spanId`, and other log
   correlation keys) is thread-local. Log lines emitted from executor threads lose their trace
   context.

### Decision

A `ContextPropagator` functional interface captures thread-bound context on the calling thread
at `wrap()` invocation time and returns a `Runnable` that restores it before executing the task
on the executor thread:

```java
@FunctionalInterface
public interface ContextPropagator {
    Runnable wrap(Runnable task);               // context captured when wrap() is called
    default <V> Supplier<V> wrapSupplier(Supplier<V> task) { ... }  // value-producing variant
    static ContextPropagator noOp() { ... }
}
```

**Built-in implementations:**

| Class | Module | Propagates |
|---|---|---|
| `MdcContextPropagator` | `failover-core` | SLF4J MDC (traceId, spanId, all keys) |
| `TenantContextPropagator` | `failover-store-multitenant` | `TenantContext` tenant ID |
| `MicrometerContextPropagator` | `failover-spring-boot-autoconfigure` | Micrometer `Span` (actual span context, not just MDC keys) |

**Auto-composition** by `FailoverAutoConfiguration.contextPropagator()`:

| Condition | Composed bean |
|---|---|
| Single-tenant, no Micrometer | `MdcContextPropagator` |
| Multi-tenant only | `Composite(Tenant, MDC)` |
| Micrometer only | `Composite(Micrometer, MDC)` |
| Multi-tenant + Micrometer | `Composite(Tenant, Micrometer, MDC)` |

**Why functional interface, not Spring's `TaskDecorator`?**  
`ContextPropagator` lives in `failover-core` (no Spring dependency). This keeps the core module
framework-agnostic. The Spring `TaskDecorator` pattern achieves the same goal at the executor
level, but requires Spring and couples the executor configuration to the propagation concern.
`ContextPropagator` is injected into `ScatterGatherFailoverHandler` directly, keeping propagation
visible and testable without a running Spring context.

**Override point:** declare a `ContextPropagator` bean to replace the auto-composed default
entirely (e.g. to add security-principal propagation or a custom `ThreadLocal`).

### Consequences

**Easier:**

- Thread-local context is propagated transparently to all executor threads without changes to
  `MultiTenantFailoverStore`, MDC configuration, or Micrometer setup.
- New context types (Spring Security principal, custom `ThreadLocal`) are added by implementing
  `ContextPropagator` and declaring a bean — no framework changes required.
- `CompositeContextPropagator` chains any number of propagators without modifying existing ones.

**More complex:**

- Each `wrap()` call performs a `ThreadLocal` read on the calling thread — negligible overhead
  but non-zero.
- `MicrometerContextPropagator` requires `io.micrometer.tracing.Tracer` on the classpath and a
  `Tracer` bean; it silently does nothing when absent.
- Users who declare a custom `ContextPropagator` bean replace the entire auto-composed chain —
  they must explicitly include `MdcContextPropagator`, `TenantContextPropagator`, etc. if still
  needed.

___

## ADR 26 — Replace `LocalDateTime` with `Instant` for Timezone-Aware Expiry Timestamps

**Date : 06-JUN-2026**

### Status

Accepted

### Context

All expiry-related timestamps (`asOf`, `expireOn`) used `java.time.LocalDateTime` throughout the expiry chain — `FailoverClock`, `ReferentialPayload`, `ExpiryPolicy`, `FailoverStore.cleanByExpiry()`, and the JDBC layer.

`LocalDateTime` has no timezone concept. Two JVM nodes running in different time zones (or with different `TZ` environment variables) interpret the same stored `LocalDateTime` value differently:

- Node A (UTC): stores `2026-06-06T10:00:00` meaning 10:00 UTC.
- Node B (UTC+2): reads `2026-06-06T10:00:00` as 10:00 local time → 08:00 UTC.

In containerised deployments, cloud environments, or any multi-node setup, this creates two failure modes:

1. **Early eviction**: a node with a later local time sees an entry as expired before its actual UTC expiry.
2. **Phantom survival**: a node with an earlier local time serves data past its intended UTC expiry.

The JDBC store uses a `TIMESTAMP` column (not `TIMESTAMP(9) WITH TIME ZONE`). `Timestamp.valueOf(LocalDateTime)` writes the local representation — losing timezone information at the database boundary.

Additionally, `DefaultExpiryPolicy` used `LocalDateTime.now(ZoneId.of("UTC"))`, which creates a `LocalDateTime` with UTC wall-clock values but no attached timezone offset. This prevented correct comparison against entries stored by nodes in other zones.

### Decision

Replace `java.time.LocalDateTime` with `java.time.Instant` throughout the expiry chain.

`Instant` is always UTC-relative, unambiguous across JVM timezone settings, and directly comparable regardless of where it was produced.

**Contract changes:**

| Interface / Class | Before | After |
|---|---|---|
| `FailoverClock.now()` | `LocalDateTime` | `Instant` |
| `ExpiryPolicy.computeExpiry()` | `LocalDateTime` | `Instant` |
| `FailoverStore.cleanByExpiry()` | `LocalDateTime expiry` | `Instant expiry` |
| `ReferentialPayload.asOf` | `LocalDateTime` | `Instant` |
| `ReferentialPayload.expireOn` | `LocalDateTime` | `Instant` |
| `Referential.asOf` | `LocalDateTime` | `Instant` |
| `ReferentialAware.setAsOf()` | `LocalDateTime` | `Instant` |

**`DefaultExpiryPolicy.computeExpiry()` uses a `ZonedDateTime` intermediate** to support calendar-based `ChronoUnit` values (`MONTHS`, `YEARS`) that `Instant.plus()` rejects with `UnsupportedTemporalTypeException`:

```java
public Instant computeExpiry(Failover failover) {
    return clock.now()
            .atZone(ZoneOffset.UTC)
            .plus(failoverExpiryExtractor.expiryDuration(failover),
                  failoverExpiryExtractor.expiryUnit(failover))
            .toInstant();
}
```

**JDBC binding** uses `Timestamp.from(Instant)` for writes and `Timestamp.toInstant()` for reads, preserving UTC semantics at the database boundary.

### Consequences

* Expiry calculations are correct and identical across all JVM nodes regardless of system timezone.
* All `FailoverStore` implementations (`InMemory`, `Caffeine`, `JDBC`, `Async`, `MultiTenant`) accept `Instant` for `cleanByExpiry()` — consistent interface.
* Custom `ExpiryPolicy` implementations must update their `computeExpiry()` return type from `LocalDateTime` to `Instant` — this is a source-incompatible API change.
* Custom `ReferentialAware` implementations must update `setAsOf(LocalDateTime)` to `setAsOf(Instant)` — source-incompatible.
* JDBC stores using existing `TIMESTAMP` columns continue to work; `Timestamp.from(Instant)` writes UTC epoch milliseconds. No schema migration is required for databases that interpret `TIMESTAMP` as UTC internally (H2, PostgreSQL with `UTC` session timezone). For databases that apply local timezone during storage, changing the column to `TIMESTAMP(9) WITH TIME ZONE` is recommended but not mandatory.

___

## ADR 27 — Migrate Deprecated `JdbcTemplate` Overloads in `FailoverStoreJdbc`

**Date : 06-JUN-2026**

### Status

Accepted

### Context

`FailoverStoreJdbc` used the `JdbcTemplate` overloads that take explicit `Object[]` parameter arrays and `int[]` SQL type arrays:

```java
jdbcTemplate.queryForObject(sql, new Object[]{name, key}, new int[]{Types.VARCHAR, Types.VARCHAR}, rowMapper);
jdbcTemplate.update(sql, new Object[]{name, key}, new int[]{Types.VARCHAR, Types.VARCHAR});
```

These overloads were deprecated in Spring Framework 5.3 and are not recommended for use with Spring Boot 4.x (Spring Framework 6.x). The `int[]` SQL type hints are unnecessary when the JDBC driver can infer types from the bound values, and the `Object[]` form adds boilerplate with no benefit.

### Decision

Migrate to the non-deprecated varargs overloads:

```java
jdbcTemplate.queryForObject(sql, rowMapper, name, key);
jdbcTemplate.update(sql, name, key);
```

For `cleanByExpiry()`, the `Instant` expiry value (introduced in ADR 26) is bound as `Timestamp.from(expiry)` so the JDBC driver receives a proper SQL `TIMESTAMP` value without requiring an `int[]` type hint:

```java
jdbcTemplate.update(sql, Timestamp.from(expiry));
```

The `java.sql.Types` import is removed from `FailoverStoreJdbc` as it is no longer referenced.

### Consequences

* No deprecated API usage in `FailoverStoreJdbc`.
* `int[]` SQL type arrays removed — less boilerplate; driver-inferred types are sufficient for `VARCHAR` and `TIMESTAMP` parameters.
* Behaviour is identical: Spring's varargs overload internally delegates to the same `PreparedStatementSetter` path as the deprecated forms.

___

## ADR 28 — `domain` Attribute — Shared Store Partitioning Across `@Failover` Annotations

**Date : 07-JUN-2026**

### Status

Accepted

### Context

`FailoverStore.find(String name, String key)` is a composite lookup — both the `FAILOVER_NAME` and `FAILOVER_KEY` must match. `FailoverKeyGenerator` includes `failover.name()` as a UUID namespace prefix so that two failovers with different names and the same arguments produce different keys. This isolation is correct for independent failovers.

It becomes a problem for the scatter/gather + single-entity pattern:

- `findAll("1,2,3")` with a `PayloadSplitter` stores three slices individually. The `FAILOVER_NAME` for all three is `"country-all"`.
- `findByCode("1")` stores under `FAILOVER_NAME = "country-by-code"`.

Even if `findAll` has already stored entity with ID `1`, when `findByCode("1")` fails and attempts recovery it looks up `find("country-by-code", UUID("country-by-code:1"))`. The scatter path stored `find("country-all", UUID("country-all:1"))`. Different composite address — cache miss.

The only way to make the two endpoints share store entries is to make them use the same `FAILOVER_NAME` and the same UUID prefix in `FailoverKeyGenerator`. This requires a shared, logical namespace that is distinct from the annotation `name` (which must remain unique for the scanner).

**Options evaluated:**

| Option | Approach | Rejected because |
|---|---|---|
| Remove name uniqueness constraint | Allow two `@Failover` with same name | Scanner can no longer distinguish annotations for metrics/logging; breaks observable tooling |
| `domain` column in store | Add a third lookup key | Store schema change; backward incompatible; over-engineered for the sharing use case |
| `domain` attribute as namespace alias | `effectiveName = domain ?: name`; both store key and name use effectiveName | No schema change; backward compatible; scanner keeps unique names |

### Decision

Add an optional `domain` attribute to `@Failover`:

```java
String domain() default "";
```

Introduce `FailoverNameResolver` as a static utility:

```java
public static String effectiveName(Failover failover) {
    return failover.domain().isBlank() ? failover.name() : failover.domain();
}
```

Apply `effectiveName` in two places:

1. **`FailoverKeyGenerator.generateFinalKey`** — use `effectiveName` as the UUID namespace prefix instead of `failover.name()`. Two failovers with the same `domain` and the same raw key from Layer 1 now produce the same UUID.

2. **`DefaultFailoverHandler`** — pass `effectiveName` as `FAILOVER_NAME` to `FailoverStore.store()` and `FailoverStore.find()`. Both store and recover use the shared partition name.

Logging in `DefaultFailoverHandler` retains `failover.name()` — for debugging, the unique annotation name is more informative than the shared domain name.

`@Failover.name()` uniqueness is enforced by `SpringContextFailoverScanner` as before — `domain` does not relax this constraint. Annotations sharing a `domain` must still carry distinct `name` values.

`SpringContextFailoverScanner` logs a `WARN` at startup if two annotations share a `domain` but have different expiry configurations (last writer wins per store entry — mismatched expiry causes non-deterministic expiry).

### Consequences

**Easier:**

* Scatter/gather bulk endpoints and single-entity endpoints can share store entries with a one-line annotation change — no store schema change, no migration.
* Backward compatible: `domain` defaults to `""`, so all existing annotations behave identically.
* `FailoverNameResolver` centralises the logic — no risk of `FailoverKeyGenerator` and `DefaultFailoverHandler` using different namespaces.
* Expiry mismatch warning fires at startup, before any runtime data is stored — operations can catch misconfiguration early.

**More complex:**

* Developers must align expiry across all `@Failover` annotations in the same domain. No compile-time enforcement.
* Cross-domain store entry collision is theoretically possible if two unrelated failovers choose the same `domain` string. Convention (e.g. reverse-DNS prefix) prevents this.
* Keys stored under a domain are no longer tied to a single `@Failover` name — debugging requires knowing which domain is in use.

___

## ADR 29 — Observability Layer — Observer, Publisher SPI and MDC Logger Refactor

**Date : 07-JUN-2026**

### Status

Accepted

### Context

The original observability model exposed a `FailoverReporter` with a `report()` method backed by a `ReportPublisher` SPI. The default `LoggerReportPublisher` (later `MetricsReportPublisher`) emitted a plain log line per failover on the scheduled report tick.

Several weaknesses accumulated:

1. **Name ambiguity.** `FailoverReporter` and `ReportPublisher` implied reporting in the analytics/metrics sense, but the implementation was MDC-structured logging — not metrics at all.
2. **MDC not atomically scoped.** The old logger wrote key-value pairs to MDC without restoring prior MDC state — any thread-bound MDC keys from the caller were permanently overwritten for the duration of the log call.
3. **Extension friction.** Adding a new output sink (Micrometer, event bus) required implementing `ReportPublisher` with a name that implied report generation, not event publication.
4. **No separation between scan (discovery) and publication.** `FailoverReporter` mixed the concerns of iterating all failovers and dispatching to publishers.

### Decision

Rename and refactor the observability stack:

| Before | After | Reason |
|---|---|---|
| `FailoverReporter` | `FailoverObserver` | `observe()` is the correct verb — it reads discovered configuration and publishes; it does not generate a report |
| `report()` | `observe()` | Matches renamed interface |
| `ReportPublisher` | `ObservablePublisher` | Publisher that receives `Metrics` — "observable" describes the data model, not the output format |
| `LoggerReportPublisher` / `MetricsReportPublisher` | `MdcLoggerObservablePublisher` | Name encodes the mechanism: MDC-scoped, log output |

**`MdcLoggerObservablePublisher` pattern:**

```java
public void doPublish(Metrics metrics) {
    final Map<String, String> copyOfMdc = MDC.getCopyOfContextMap();
    metrics.getInfo().forEach(MDC::put);
    try {
        log.info("Failover metrics : {}", metrics.getName());
    } finally {
        if (copyOfMdc != null) {
            MDC.setContextMap(copyOfMdc);
        } else {
            MDC.clear();
        }
    }
}
```

The `finally` block unconditionally restores the prior MDC state — metric keys do not leak into subsequent log lines from the same thread.

`AbstractObservablePublisher` provides the `finally`-restore skeleton. Subclasses implement `doPublish(Metrics)` only — they do not manage MDC state.

`CompositeObservablePublisher` chains multiple `ObservablePublisher` instances so that both `MdcLoggerObservablePublisher` and `MicrometerObservablePublisher` (ADR 31) can be active simultaneously without either knowing about the other.

`DefaultFailoverObserver` retains the scan + dispatch loop: it calls `failoverScanner.findAllFailover()` and for each discovered failover builds a `Metrics` object and calls `observablePublisher.publish(metrics)`.

### Consequences

* MDC state is always restored after a publish call — no cross-contamination between failover metric keys and application log context.
* `ObservablePublisher` is a stable SPI — third-party publishers (Datadog, OpenTelemetry, custom event bus) implement it without coupling to the renamed internals.
* `CompositeObservablePublisher` means the log publisher and Micrometer publisher coexist at no additional wiring cost.
* The rename is source-incompatible for applications that implement `ReportPublisher` directly. Applications that only use Spring Boot auto-configuration are unaffected — the beans are replaced transparently.

___

## ADR 30 — SpringContextFailoverScanner — Replacing Reflections-Based Classpath Scanning

**Date : 07-JUN-2026**

### Status

Accepted

### Context

`DefaultFailoverScanner` (original implementation) used the `org.reflections:reflections` library to scan the classpath under a configured `failover.package-to-scan` property. This introduced several problems:

1. **Heavyweight transitive dependency.** Reflections pulled in Guava and required `--add-opens java.base/java.lang.reflect=ALL-UNNAMED` and related JVM flags on JDK 17+.
2. **Slow startup.** Classpath scanning reads all `.class` files under the configured package. For large applications this added hundreds of milliseconds to startup.
3. **Requires explicit configuration.** Users had to set `failover.package-to-scan` to the package containing their `@Failover`-annotated interfaces. Omitting or misspelling this property silently produced an empty scanner result — no failovers discovered, no warning.
4. **Proxy blindness.** CGLIB-proxied beans expose the proxy class, not the user class. Reflections saw only the proxy and missed `@Failover` annotations placed on the proxied interface or concrete class.
5. **Annotation inheritance gap.** `@Failover` placed on an interface method was not found if the concrete class did not repeat the annotation — Reflections did not walk the full inheritance hierarchy.

### Decision

Replace `DefaultFailoverScanner` with `SpringContextFailoverScanner` in `failover-observable-scanner`:

```java
public class SpringContextFailoverScanner
        implements FailoverScanner, ApplicationContextAware, SmartInitializingSingleton {

    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> userClass = ClassUtils.getUserClass(applicationContext.getType(beanName));
            ReflectionUtils.doWithMethods(userClass, method -> {
                Failover annotation = AnnotationUtils.findAnnotation(method, Failover.class);
                if (annotation != null) discovered.putIfAbsent(annotation.name(), annotation);
            }, method -> !method.isBridge() && !method.isSynthetic());
        }
    }
}
```

**Key mechanism choices:**

| Concern | Solution |
|---|---|
| Scan timing | `SmartInitializingSingleton.afterSingletonsInstantiated()` — fires once, after all singleton beans are instantiated; every Spring-managed bean is visible |
| Proxy unwrapping | `ClassUtils.getUserClass(type)` — unwraps CGLIB proxies to the real user class |
| Annotation inheritance | `AnnotationUtils.findAnnotation(method, Failover.class)` — walks interface hierarchy; `@Failover` on an interface method is found without repeating it on the concrete class |
| Synthetic method filter | `!method.isBridge() && !method.isSynthetic()` — skips compiler-generated bridge methods |
| No package config | `applicationContext.getBeanDefinitionNames()` enumerates all beans — no `failover.package-to-scan` required |

`DefaultFailoverScanner` and its supporting classes (`ReflectionsExceptionHandler`, `ExceptionHandler`, `ExceptionHandlerExecutor`) are removed from `failover-core`. The `org.reflections:reflections` dependency is removed from `failover-core/pom.xml`. The `failover.package-to-scan` property is removed.

`SpringContextFailoverScanner` is extracted to `failover-observable-scanner`, not `failover-core`. `failover-core` has no Spring dependency and must remain framework-agnostic. The scanner interface (`FailoverScanner`) stays in `failover-core`.

### Consequences

**Easier:**

* Zero configuration required — no `failover.package-to-scan`, no JVM flags, no Guava transitive.
* Startup scan cost is O(registered beans) instead of O(classpath files). Typical saving: 50–300ms.
* `@Failover` on interface methods is found correctly without any annotation repetition on concrete classes.
* CGLIB-proxied beans are handled transparently.

**More complex:**

* `SpringContextFailoverScanner` sees only Spring-managed beans. `@Failover` on a class not registered in the Spring context is invisible. This is intentional — the AOP proxy (which is the actual interception mechanism) only covers Spring beans, so unscanned non-Spring classes would never be intercepted anyway.
* Applications that extend `DefaultFailoverScanner` directly must migrate to `FailoverScanner` SPI — the concrete class is removed.
* `failover.package-to-scan` configuration is a no-op after migration — it should be removed from application YAML to avoid confusion.

___

## ADR 31 — failover-observable-micrometer — Micrometer Extension as an Optional Module

**Date : 07-JUN-2026**

### Status

Accepted

### Context

After the observability refactor (ADR 29), the `MdcLoggerObservablePublisher` provides structured MDC logging. Operators running Prometheus/Grafana need real Micrometer counters and gauges — log-parse pipelines are fragile and add latency.

Adding Micrometer support directly into `failover-core` or `failover-spring-boot-autoconfigure` would:

1. Make Micrometer a mandatory runtime dependency for all failover users — including those using only InMemory stores in tests or minimal deployments without a metrics stack.
2. Pull `micrometer-core` (and transitively `io.micrometer:micrometer-commons`) into the core module, violating the rule that `failover-core` has zero framework dependencies.
3. Pull Spring Boot Actuator into the autoconfigure module for the `HealthIndicator` — actuator is optional even in Spring Boot applications.

### Decision

Extract all Micrometer functionality into `failover-observable-micrometer`, a new optional Maven module:

```
failover-observable-micrometer
  ├── FailoverMeterBinder          — MeterBinder + SmartInitializingSingleton
  ├── MicrometerObservablePublisher — ObservablePublisher backed by MeterRegistry
  └── health/
      └── FailoverHealthIndicator  — HealthIndicator (conditional on Actuator)
```

The module depends on `failover-core`, `failover-observable-scanner` (for `FailoverScanner`), `micrometer-core`, and optionally `spring-boot-starter-actuator`.

Auto-configuration in `failover-spring-boot-autoconfigure` wires the Micrometer beans using `@ConditionalOnClass` by fully-qualified class name string, not `.class` reference — so the autoconfigure module itself does not require a compile-time Micrometer dependency:

```java
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
```

**`FailoverMeterBinder`** implements both `MeterBinder` and `SmartInitializingSingleton`:

- `bindTo(MeterRegistry)` — registers the lazy `failover.registered.total` gauge immediately. Safe to call before the scanner has finished.
- `afterSingletonsInstantiated()` — registers per-failover `failover.config.expiry.seconds` gauges. Fires after `SpringContextFailoverScanner.afterSingletonsInstantiated()`, so the scanner result is complete.

Tags on `failover.config.expiry.seconds`: `name` (annotation name), `domain` (effective domain, or name if no domain), `unit` (ChronoUnit name).

**`FailoverHealthIndicator`** is activated only when `spring-boot-starter-actuator` is on the classpath. It reports `UP` with a count of discovered failovers, and `DOWN` if the scanner returns zero (misconfiguration).

### Consequences

**Easier:**

* Applications without Micrometer pay zero cost — `failover-observable-micrometer` is not on their classpath and none of its beans are activated.
* The `failover.config.expiry.seconds` gauge with `domain` tag allows dashboards to group related failovers by domain, providing a higher-level view than per-name metrics.
* `FailoverMeterBinder` as a `SmartInitializingSingleton` guarantees gauge registration happens after the scanner completes — no race between binder and scanner initialization order.
* `FailoverHealthIndicator` requires no additional dependency when Actuator is already present — common in Spring Boot applications.

**More complex:**

* Applications that want Micrometer metrics must add the `failover-observable-micrometer` Maven dependency explicitly. This is intentional opt-in.
* Lifecycle ordering relies on both `FailoverMeterBinder` and `SpringContextFailoverScanner` implementing `SmartInitializingSingleton` — invocation order within the `afterSingletonsInstantiated` phase is determined by bean registration order in the application context, which is normally correct but should be verified in custom bootstrap scenarios.
* `MicrometerObservablePublisher` handles store/recover runtime events published through the `ObservablePublisher` SPI. It is a separate class from `FailoverMeterBinder` which handles static configuration gauges. Both must be active for full metric coverage.

___

## ADR 32 — PayloadSplitterExecutionException — Wrapping User-Splitter Failures with Diagnostic Context

**Date : 10-JUN-2026**

### Status

Accepted

### Context

`ScatterGatherFailoverHandler` calls user-provided `PayloadSplitter` methods at three points: `splitOnStore`, `splitOnRecover`, and `merge`. Any of these can throw a runtime exception — for example, an `IndexOutOfBoundsException` if the splitter reads `args.get(0)` on a `findAll()` call where args are empty.

Without wrapping, the raw exception propagates with no context indicating which splitter raised it, which operation was executing, or which `@Failover` annotation was active. Diagnosing the failure requires a full stack trace and correlation with the calling code.

### Decision

Introduce `PayloadSplitterExecutionException` (in `failover-core`) as a `RuntimeException` that wraps all user-splitter failures.

Three private helpers in `ScatterGatherFailoverHandler` — `invokeSplitOnStore`, `invokeSplitOnRecover`, and `invokeMerge` — call the corresponding splitter method inside a `try/catch(Exception)` and rethrow as `PayloadSplitterExecutionException`. Every splitter call site goes through one of these helpers; no splitter method is called directly.

The exception message includes:

- **operation** — `splitOnStore`, `splitOnRecover`, or `merge`
- **splitter bean name** — from `failover.payloadSplitter()`
- **failover name** — from `failover.name()`
- **full annotation config** — `expiryDuration`, `expiryUnit`, `domain`
- **original cause message** — the exception thrown by the splitter

Example:

```
PayloadSplitter 'countrySplitter' failed during 'splitOnRecover'
  for failover 'countries-by-codes'
  [payloadSplitter='countrySplitter', expiryDuration=24, expiryUnit='HOURS', domain='country']:
  Index 1 out of bounds for length 0
```

### Consequences

* A single exception class distinguishes splitter errors from framework errors. Stack traces point at the splitter method, not deep inside `ScatterGatherFailoverHandler`.
* Service-layer catch blocks can filter specifically on `PayloadSplitterExecutionException` without catching unrelated `RuntimeException`s.
* The original cause is preserved as `getCause()` — full stack trace from inside the splitter is retained.
* Callers that previously relied on raw `IndexOutOfBoundsException` or `NullPointerException` propagating from splitters will now receive `PayloadSplitterExecutionException` wrapping those exceptions — a source-compatible change (both are `RuntimeException`).

___

## ADR 33 — doRecoverAll All-Slices Iteration — User-Controlled Slice Count

**Date : 10-JUN-2026**

### Status

Accepted

### Context

`doRecoverAll` calls `splitOnRecover` and then dispatches each returned context to `delegateR.recoverAll`. An earlier draft limited this to `slices.get(0)` (first slice only) to prevent N×duplication when using the default `DefaultFailoverHandler` — whose `recoverAll` ignores args and returns all store entries by name, so N contexts produce N×all-entries.

Limiting to the first slice was rejected because:

1. It silently discards valid user intent. A `PayloadSplitter` may return multiple contexts intentionally — for example, when the slice delegate's `recoverAll` partitions results by the context's args (e.g. by tenant ID, region, or a custom shard key). The framework has no way to distinguish "returning multiple contexts for a reason" from "returning multiple contexts by mistake".
2. It violates the extension contract: the `PayloadSplitter` API surface implies that the framework honours all returned contexts, not just the first.

### Decision

`doRecoverAll` iterates **all** contexts returned by `splitOnRecover`. Each context drives one `delegateR.recoverAll` call. The results are flat-mapped into a single list passed to `merge`.

An empty-list guard exits early with a `WARN` log and returns `List.of()` without calling `merge` (ADR 35 covers this separately).

Deduplication is documented as the responsibility of `PayloadSplitter.merge()` when multiple slices produce overlapping results. The `PayloadSplitter.splitOnRecover` javadoc states:

> When using the default `DefaultFailoverHandler` whose `recoverAll` ignores args and returns all entries by name, return exactly one placeholder context to avoid N-times duplication. Return multiple contexts only when the slice delegate's `recoverAll` partitions results by the supplied args.

### Consequences

* Splitters that intentionally return multiple contexts for partitioned `recoverAll` work correctly.
* Splitters using the default handler must return one context or handle N×duplication in `merge`.
* The framework imposes no deduplication overhead on splitters that return a single context (the common case).
* `merge()` receives duplicates when N>1 contexts are used with a non-partitioning delegate — a documented trade-off that gives the splitter full control over merge semantics.

___

## ADR 34 — ScatterGatherFailoverHandler.recoverAll() Override — Clear Error for Scatter Case

**Date : 10-JUN-2026**

### Status

Accepted

### Context

`ScatterGatherFailoverHandler` did not previously override `recoverAll()`. The inherited implementation from the delegate chain was called, which returns all composite entries by name. This is wrong in scatter mode: slices are stored per entity (type `R`), not per composite (type `T`). Returning slice data as composite data is a type mismatch that causes a `ClassCastException` at the call site with no clear message.

Additionally, the correct pattern for recovering all slices in scatter mode is `recover()` (not `recoverAll()`) with empty args or `@Failover(recoverAll=true)`. The `BasicFailoverExecution` (used by the AOP aspect) always calls `recover()`, never `recoverAll()` directly. `recoverAll()` is only reachable via the handler API directly — and in scatter mode it is meaningless.

### Decision

Override `recoverAll()` in `ScatterGatherFailoverHandler` with two branches:

1. **Splitter configured (`payloadSplitter` non-empty):** throw `UnsupportedOperationException` with a message directing the caller to use `recover()` with empty args or `@Failover(recoverAll=true)`.
2. **No splitter:** delegate to `delegateT.recoverAll()` for correct pass-through behaviour (non-scatter case).

The error message names the failover and explains the correct alternative, so developers hitting this path during testing receive actionable guidance.

### Consequences

* The silent type-mismatch `ClassCastException` is replaced by a clear `UnsupportedOperationException` naming the failover.
* Scatter recover-all is supported via `recover()` + `@Failover(recoverAll=true)` — the existing, correct path.
* No-splitter callers are unaffected: `delegateT.recoverAll()` is the same behaviour as before.
* `recoverAll()` is now documented as unsupported in scatter mode and the reason is explicit in both the Javadoc and the exception message.

___

## ADR 35 — Empty splitOnRecover Guard — Null Return Instead of merge([])

**Date : 10-JUN-2026**

### Status

Accepted

### Context

`PayloadSplitter.merge()` is always called with the list of recovered slice contexts. Most `merge()` implementations read `contexts.get(0)` to build the composite `RecoverContext` builder. When `splitOnRecover` returns an empty list (misconfigured splitter, wrong args type, or intentional suppression), `merge([])` throws `IndexOutOfBoundsException` — a confusing crash with no failover context in the message.

Two distinct scenarios need guarding:

1. **`scatterRecover`:** `doRecover` or `doRecoverAll` returns an empty list — either no slices were split or no slices were recovered. Calling `merge([])` here crashes.
2. **`doRecoverAll`:** `splitOnRecover` itself returns an empty list — no template context to dispatch to `delegateR.recoverAll`. Proceeding produces no recovered data and calling `merge([])` would crash.

### Decision

**`doRecoverAll`:** guard before dispatching — if `invokeSplitOnRecover` returns empty, log `WARN` with the failover name and return `List.of()` immediately. This bubbles up to `scatterRecover` as an empty `recovered` list.

**`scatterRecover`:** guard before calling `invokeMerge` — if `recovered.isEmpty()`, log `WARN` with the failover name and return `null`. The caller (the AOP aspect) treats `null` as a no-recovery result, consistent with single-key failover behaviour when no data is stored.

Both guards use `WARN` level (not `ERROR`) because: returning `null` from a failover is a valid degraded state (no data stored yet, first call after startup). `ERROR` is reserved for exceptions.

### Consequences

* `merge()` is never called with an empty list — implementations that read `contexts.get(0)` are safe.
* A misconfigured splitter that returns no contexts produces a `WARN` log entry, not a `ClassCastException` or `IndexOutOfBoundsException`.
* Callers receive `null` on the empty-slices path, same as the no-entry-in-store path — consistent failover semantics.
* The `WARN` log includes the failover name so operators can identify the misconfigured annotation.

___

## ADR 36 — splitOnRecover RecoverAll Contract — Single Placeholder for DefaultFailoverHandler

**Date : 10-JUN-2026**

### Status

Accepted (documentation-only decision)

### Context

The `splitOnRecover` method serves two distinct roles depending on the recovery path:

1. **Normal scatter recover (args contain entity IDs):** returns N per-entity contexts, each with single-entity args → `delegateR.recover()` called N times with individual keys.
2. **Recover-all path (no ID args):** called with empty/filter args → each returned context drives one `delegateR.recoverAll()` call (fetching all store entries by name).

When the slice delegate is the default `DefaultFailoverHandler`, its `recoverAll(args, clazz)` ignores `args` and returns all entries matching the failover name. Returning N contexts therefore produces N identical result sets — N×duplication in the final `merge` input.

The framework cannot detect this case automatically: it does not know whether the user's delegate implements partitioned `recoverAll` (where each context produces a disjoint result) or flat `recoverAll` (where each context produces the same full result). Applying automatic deduplication would silently break partitioned implementations.

### Decision

Document the contract in `PayloadSplitter.splitOnRecover` Javadoc rather than enforcing it in framework code:

- When using `DefaultFailoverHandler` as the slice delegate, `splitOnRecover` must return exactly **one** placeholder context for the recover-all path.
- Multiple contexts are correct only when the slice delegate's `recoverAll` partitions results by the context's args.

Document the deduplication pattern in `PayloadSplitter.merge` Javadoc: when N×duplication is expected (e.g. wrong splitter returns multiple placeholders), deduplicate by entity ID using `Collectors.toMap`:

```java
List<R> deduped = contexts.stream()
    .map(RecoverContext::getPayload)
    .filter(Objects::nonNull)
    .collect(Collectors.toMap(R::getId, r -> r, (a, b) -> a))
    .values().stream().toList();
```

The framework applies no deduplication itself — `merge()` has full domain knowledge of type `R` and can deduplicate by the correct business key.

### Consequences

* Default case (one placeholder) produces zero duplication — the most common splitter implementation is correct by default.
* Partitioned delegates (custom `recoverAll` that partitions by args) work without any special handling — N contexts produce N disjoint result sets, merged correctly.
* Implementors who accidentally return multiple placeholders with a flat delegate receive N×all-entries in `merge` — behaviour is consistent and documented. `merge` can deduplicate if needed.
* No runtime overhead in the framework for the common single-placeholder case.
* The contract is enforced by documentation and code review, not by a runtime check — this is intentional. A runtime check would require the framework to call `recoverAll` speculatively or inspect the delegate implementation, neither of which is feasible.

___

## ADR 37 — Payload Deserialization Allowlist — Secure-by-Default Class Loading

**Date : 14-JUN-2026**

### Status

Accepted

### Context

The JDBC store persists each payload's fully-qualified class name in the `PAYLOAD_CLASS` column and, on recovery, reconstructs the type via `Class.forName(name)` in `JsonSerializer.toClass` before Jackson deserializes the JSON. If an attacker can write to `FAILOVER_STORE` (SQL injection elsewhere in the host app, a shared/compromised schema), they control the class name — an arbitrary-class-instantiation primitive and a classic deserialization-gadget entry point. The database is normally a trusted boundary, so this is hardening, not an active vulnerability — but the fix is cheap.

A naive fix (an opt-in allowlist property, default allow-all) only protects operators who know to configure it — an insecure default.

### Decision

Restrict `JsonSerializer.toClass` to an allowlist; reject unknown names with `FailoverStoreException` (replacing the previous `@SneakyThrows` rethrow of `ClassNotFoundException`).

The allowlist is **secure by default, zero config**, sourced from two places and merged:

1. **Auto-derived** — `FailoverScanner.findAllPayloadTypes()` exposes every `@Failover` payload type (method return type, or the element/component type for `Collection`/array returns). The autoconfig adds each type's **package** as an allowed prefix. JDK packages (`java.*`, `javax.*`, `jakarta.*`) are never added — whitelisting them would re-open the gadget surface.
2. **Operator override** — `failover.store.allowed-payload-classes` (exact class names or package prefixes), additive, for classes the scanner cannot infer (e.g. a scatter slice type in a different package than its composite).

Package **prefixes** (not exact classes) are used so that scatter slice types sharing a package with their composite are covered without configuration. The allowlist is resolved lazily and memoized on first `toClass` — necessary because the scanner (`SmartInitializingSingleton`) completes after the serializer bean is built.

Allow-all is retained only when the resolved allowlist is empty (no payload types discovered and no property set) — preserving backward compatibility for unusual setups.

### Consequences

* Out of the box, only the application's own referential packages can be materialized from the store — no configuration required.
* A poisoned `PAYLOAD_CLASS` value naming an unlisted class is rejected before instantiation.
* `ClassNotFoundException` (payload class renamed/removed between deployments) now surfaces as a `FailoverStoreException` with a remediation hint instead of an opaque sneaky-thrown checked exception.
* Operators with split-package slice types must add a prefix to `failover.store.allowed-payload-classes`; this is the documented escape hatch.
* Applies to serializing stores (JDBC); in-memory/Caffeine hold live objects and are unaffected.

___

## ADR 38 — Scatter/Gather Per-Slice Timeout — Bounded Parallel Join

**Date : 14-JUN-2026**

### Status

Accepted

### Context

Parallel scatter/gather (ADR 24) dispatches each slice on the virtual-thread executor and collects results with `CompletableFuture.allOf(...).join()` (store) or per-future `join()` (recover) — with no timeout. A single hung slice (e.g. JDBC connection-pool exhaustion on one slice store) blocks the business thread indefinitely, defeating the resilience the library exists to provide.

### Decision

Add `failover.scatter.timeout` (`Duration`, default `10s`) applied via `orTimeout` to the parallel-path futures:

* **Recover path** — a slice that exceeds the timeout is treated as *not recovered*: it contributes a `null` payload (indistinguishable from a cache miss) and the remaining slices still merge. The caller is never blocked.
* **Store path** — the timeout surfaces to the caller, where it is already isolated by `BasicFailoverExecution` (the business call returns; the store failure is logged/metered).

The timeout applies only to the parallel path (`parallel=true`); sequential calls cannot be interrupted this way. `null`/empty disables it (legacy wait-indefinitely behaviour).

### Consequences

* A hung slice can no longer stall the business thread — bounded worst-case latency under partial slice-store failure.
* Recover degrades gracefully: timed-out slices look like misses, so partial recovery still works.
* `ScatterGatherFailoverHandler` gains a constructor parameter for the timeout; existing constructors delegate with `null` (no timeout) for backward compatibility.
* Choosing the timeout is an operational trade-off: too low truncates legitimate slow slices, too high weakens the protection. The 10s default favours availability.

___

## ADR 39 — Error Propagation — Never Recover on a Failing JVM

**Date : 14-JUN-2026**

### Status

Accepted

### Context

`FailoverAspect.returnResult` caught `Throwable` from the business call and wrapped it in `ExecutionException` (a `RuntimeException`). `BasicFailoverExecution` then caught `Exception` and ran the recovery path. This meant `Error` subclasses — `OutOfMemoryError`, `StackOverflowError`, linkage errors — were wrapped and treated as recoverable, running the recovery path (which itself allocates: deserialization, defensive copies) on a JVM that may be dying. The `Error` javadoc explicitly states these are "abnormal conditions that a reasonable application should not try to catch".

### Decision

Catch and rethrow `Error` unwrapped, ahead of the generic `Throwable` wrap:

```java
try {
    return cast(joinPoint.proceed());
} catch (Error error) {
    throw error;            // never convert to a recoverable exception
} catch (Throwable throwable) {
    throw new ExecutionException(...);
}
```

### Consequences

* `Error` propagates fail-fast: a dying JVM is recycled by the platform (k8s liveness, circuit breakers) instead of limping while serving stale data.
* Normal failover is unchanged — `RuntimeException`, checked `Exception`, and upstream-down conditions (the 99.9% case) still trigger recovery exactly as before.
* Only the rare, catastrophic, genuinely-unrecoverable case changes behaviour. Methods that abuse `Error`/`AssertionError` as control flow will no longer be recovered — an acceptable trade-off, as that is an anti-pattern.

___

## ADR 40 — Multi-Tenant Strict Mode — Reject Unconfigured Tenants

**Date : 14-JUN-2026**

### Status

Accepted

### Context

In the JDBC `TABLE_PREFIX` strategy, `resolveJdbcPrefix` falls back to an empty `TenantConfig` (prefix `""`) for any tenant ID absent from `failover.store.multitenant.tenants`. Such a tenant therefore resolves to `globalPrefix + FAILOVER_STORE` — the shared global table — and **every** unconfigured tenant co-mingles its data there, silently breaking the isolation multi-tenancy exists to provide. A `TenantResolver` returning a typo'd or newly-onboarded tenant ID hits this silently. (Caffeine/in-memory are immune: each tenant ID gets its own cache instance via `computeIfAbsent`.)

### Decision

Add `failover.store.multitenant.strict` (default `false`). When resolving a tenant absent from the configured map in `TABLE_PREFIX` mode:

* **`strict=true`** — throw `FailoverStoreException`, refusing to route it.
* **`strict=false`** — allow it (legacy behaviour) but log a one-time `WARN` per tenant ID.

The configured `default-tenant` is always exempt — routing it to the global table is intentional.

### Consequences

* Operators can opt into fail-fast isolation guarantees with one property.
* The default remains backward-compatible but now emits a visible warning instead of failing silently, surfacing the latent gap that ADR 21's design left open (and the residual `cleanByExpiry`-coverage gap for runtime-only tenants).
* JDBC-`TABLE_PREFIX`-specific; other strategies/stores are unaffected.

___

## ADR 41 — Async Store Failure Metric — Visibility for a Silently-Degraded Layer

**Date : 14-JUN-2026**

### Status

Accepted

### Context

`FailoverStoreAsync` (ADR 19) offloads `store`/`delete`/`cleanByExpiry` to the executor and catches any exception inside the task so a store failure never breaks the business call. The downside: a persistently failing store layer (DB down, pool exhausted) is visible only in logs — no metric fires, so dashboards and alerts stay green while no failover data is being persisted.

### Decision

Give `FailoverStoreAsync` an optional `ObservablePublisher`. On a caught executor-side failure it publishes a metric event (action `store-async-failed`) carrying the failover name, the operation (`store`/`delete`/`cleanByExpiry`), and the exception type. `MicrometerObservablePublisher` maps this to a counter `failover.store.async.failed{name,operation,exception_type}`. Publishing is best-effort: a failure in the publisher is swallowed (debug-logged) so it can never mask the original failure or break the swallow contract.

### Consequences

* A dead async store layer is now observable as a metric, not just log lines — alertable.
* Backward compatible: the publisher is optional (`null` disables emission); the existing two-arg constructor is retained.
* In-process publishers (e.g. the MDC logger) also receive the event; only the Micrometer path adds the counter.

___

## ADR 42 — FailoverScanner Relocation to a Neutral Core Package

**Date : 14-JUN-2026**

### Status

Accepted

### Context

`FailoverScanner` (the SPI that discovers all `@Failover` methods) lived in `com.societegenerale.failover.core.observable.scanner` because observability reporting was its only consumer. ADR 37 added a second, unrelated consumer — store deserialization safety reads `findAllPayloadTypes()` to build the allowlist. The `observable.*` package now misrepresents the scanner's responsibility: it is shared `@Failover` metadata, not an observability detail.

### Decision

Move the core SPI (`FailoverScanner`, `FailoverScannerException`, `package-info`) from `core.observable.scanner` to **`core.scanner`**. The scanner becomes a first-class shared component consumed by both observability and store security. Reusing the single scan (one classpath walk, one source of truth) is correct; only the placement was wrong.

The change is a package move only — done inside the `3.0.0-SNAPSHOT` (pre-release) window, so no compatibility break. The implementation module is still named `failover-observable-scanner`; renaming the artifact is deferred (tracked with the broader split-package cleanup, audit A-1) to avoid churn to starter dependencies now.

### Consequences

* Package name now reflects responsibility; no consumer reads scanner metadata through an `observable` path.
* No new bad module coupling: `JsonSerializer` depends only on a `Supplier`, not the scanner; only the autoconfig wires scanner → serializer, and it already depends on every module.
* The impl module name (`failover-observable-scanner`) temporarily diverges from the interface package — a known, documented follow-up for the JPMS/split-package cleanup.

___

## ADR 43 — Dialect Integration Tests via Testcontainers

**Date : 15-JUN-2026**

### Status

Accepted

### Context

`DefaultFailoverStoreQueryResolver` selects a native merge/upsert dialect per database — H2
`MERGE INTO … KEY`, PostgreSQL `ON CONFLICT DO UPDATE`, MySQL/MariaDB `ON DUPLICATE KEY UPDATE`,
Oracle `MERGE USING DUAL` (ADR 13). The only real database exercised in the test suite is H2;
the other dialect SQL constants were verified by **string assertion only** (audit T-1).

A typo in a non-H2 dialect would ship silently: at runtime the first `store()` throws
`BadSqlGrammarException`, the store flips `mergeEnabled=false` permanently, and degrades to
INSERT/UPDATE — so the broken merge is masked as a "permanent silent downgrade" with no test ever
failing. The dialect SQL needs to run against the actual databases it targets.

### Decision

Add **Testcontainers-backed integration tests** that run the full store→merge→find→cleanByExpiry
round-trip against real **PostgreSQL, MySQL, and MariaDB** containers, asserting both the data
outcome and that the resolver selected the native dialect (`getMergeQuery()` is non-null and
contains the dialect-specific fragment) rather than the fallback.

Design constraints that keep the default build fast and hermetic:

* Tests are named **`*DialectIT`** and live in `failover-store-jdbc/src/test` (`store.dialect`
  package), reusing the existing direct-wiring pattern (`FailoverStoreJdbc` +
  `DefaultFailoverStoreQueryResolver`, no Spring context).
* A shared `AbstractDialectIT` holds the scenario; one concrete subclass per database supplies the
  `@Container` and dialect-specific DDL.
* The parent POM's failsafe config **excludes `**/*DialectIT.java`** from the default build, so
  `mvn verify` stays H2-only and Docker-free. A `dialect-its` Maven profile in `failover-store-jdbc`
  re-includes them. Testcontainers JARs are test-scoped (versions from an imported
  `testcontainers-bom`), so test sources always compile but the containers start only under the
  profile.
* **Oracle is deliberately excluded** from the container set: the `gvenzl/oracle-free` image is
  ~2 GB with slow startup and licensing caveats. The Oracle `MERGE USING DUAL` SQL remains
  string-asserted. PostgreSQL + MySQL + MariaDB cover the two distinct non-H2 merge grammars
  (`ON CONFLICT` and `ON DUPLICATE KEY`).

Run: `mvn -pl failover-store-jdbc -am -Pdialect-its verify` (requires Docker). In CI the job is
**advisory (non-blocking)**; the H2 build remains the required gate.

### Consequences

* A typo in the PostgreSQL or MySQL/MariaDB merge SQL now fails a test instead of silently
  degrading in production.
* Default `mvn verify` is unchanged: no Docker, no new ITs, same speed.
* Adding a new dialect = new `*DialectIT` subclass + DDL; the shared scenario is inherited.
* Oracle coverage remains a known gap (string-assertion only), documented here as a deliberate
  trade-off rather than an oversight.

___

## ADR 44 — Concurrency Test Coverage for Multi-Tenant Routing and the Async Store

**Date : 15-JUN-2026**

### Status

Accepted

### Context

Two threading invariants are central to correctness but were not covered by contention tests
(audit T-2):

1. `MultiTenantFailoverStore` uses `ConcurrentHashMap.computeIfAbsent` to build **exactly one**
   store per tenant. If first access for a tenant races across threads and the store were built
   more than once, tenants could end up with divergent store instances.
2. `FailoverStoreAsync` offloads writes to a `TaskExecutor`. Under load every submitted write must
   still be applied exactly once, and an executor-side failure must surface as the
   `store-async-failed` metric (ADR 41) — not vanish.

Existing tests covered scatter parallel dispatch and MDC propagation, but not these two paths.

### Decision

Add deterministic concurrency unit tests (no Docker, part of the default build):

* `MultiTenantFailoverStoreConcurrencyTest` — a `CountDownLatch` start-gate releases N threads
  simultaneously against the same tenant; a counting `TenantStoreFactory` asserts `create()` ran
  **once** per tenant. A second case drives M tenants × N threads and asserts exactly M factory
  invocations with no cross-tenant leakage.
* `FailoverStoreAsyncConcurrencyTest` — submits hundreds of concurrent `store()` calls through a
  real `ThreadPoolTaskExecutor`, drains the executor via `awaitTermination` (no polling library),
  and asserts every write landed exactly once. A second case injects a failing delegate and asserts
  the `store-async-failed` metric reaches the `ObservablePublisher`.

Draining is done by shutting the executor and joining it, so the tests are deterministic rather
than timing-dependent.

### Consequences

* The one-store-per-tenant and async-write-delivery guarantees are now regression-protected.
* No new runtime dependencies; tests use only the JDK concurrency primitives and Spring's executor.
* These run in the normal `mvn verify`, so the invariants are checked on every build.

___

## ADR 45 — ArchUnit Architecture Tests

**Date : 15-JUN-2026**

### Status

Accepted

### Context

The decorator architecture depends on invariants the compiler cannot enforce (audit T-3): the async
decorator must never read `ThreadLocal` (context is bound on the calling thread — ADR 19/20), store
implementations follow a naming convention, and the functional slices must stay acyclic. These were
maintained by convention and review only.

### Decision

Add an `ArchUnit` test (`FailoverArchitectureTest`) in `failover-spring-boot-autoconfigure` — the
only module with every `failover-*` artifact on its classpath, so one `@AnalyzeClasses` import
covers the whole library (tests excluded via `ImportOption.DoNotIncludeTests`). Rules enforced:

* `FailoverStoreAsync` must not depend on `java.lang.ThreadLocal`.
* Every concrete `FailoverStore` is named `*Store`.
* The `com.societegenerale.failover.(*)` slices are free of cycles.

The split-package rule (audit A-1) is **intentionally not enforced** here: collapsing the split
packages is a Phase 4 breaking change. The rule is targeted by class name rather than package
precisely because the store classes currently share a package across modules (the A-1 condition).
Once A-1 is fixed, a package-based layering rule can be added.

### Consequences

* The no-`ThreadLocal`-in-async and acyclic-slices invariants now fail the build if violated.
* Rules are scoped to what holds today; the split-package rule is a documented Phase 4 follow-up.
* Negative verification: temporarily referencing a `ThreadLocal` inside the async decorator makes
  the test fail, confirming the rule bites.

___

## ADR 46 — PIT Mutation Testing on Expiry and Key Logic

**Date : 15-JUN-2026**

### Status

Accepted

### Context

Line coverage is collected library-wide, but line coverage does not prove the assertions are
meaningful. The expiry-boundary logic (`isExpired`, `cleanByExpiry`, `<` vs `<=`) and key
derivation are exactly the kind of code where an off-by-one or boundary mutation can pass all
existing tests while being wrong (audit T-4).

### Decision

Add a profile-gated **PIT (pitest)** mutation-testing setup, scoped to the whole
`com.societegenerale.failover.core.*` package tree (handlers, expiry, key generation, payload
enrichment, exception policy). It lives in a `mutation` Maven profile (parent POM) so the default
build is unaffected.

The gate is **mandated at 95%** (`mutationThreshold=95`): `mvn -Pmutation test` fails when mutation
coverage drops below 95%, and the CI `mutation` job is **blocking** (not advisory).
`failWhenNoMutations=true` guards against a misconfigured target glob silently producing zero
mutations and passing the gate vacuously.

Reaching the gate required strengthening several assertions rather than only verifying interactions:
the delegating `FailoverExpiryPolicy` methods assert the *returned* value; `DefaultKeyGenerator`'s
warn-vs-no-warn branching is pinned with log-capture tests (the key string is identical across the
`isOfType` / `overridesToString` / identity-hash branches, so only the emitted WARN distinguishes
them); `ReferentialPayload.toString` gets a positive assertion; and the
`populateAdditionalInfoOnMetadata` extension point is exercised via a subclass so its invocation is
observable; and the reported `duration-ns` is bounded so a nanoTime subtraction→addition mutant is
caught. Result: **208/216 killed (96%), test strength 99%**. The residual survivors are equivalent,
unreachable, or `finally`-inlining artifacts (the unreachable `catch (NoSuchMethodException)` in
`overridesToString`, the dead `areturn` after `sneakyThrow`, an in-place `setMetadata`, a null-envelope
return on the not-found path, and four duplicated negate-conditional mutants on the metric `publish`
call whose values are already asserted in both branches) — none killable without production changes.

Run: `mvn -pl failover-core -am -Pmutation test` (report under `failover-core/target/pit-reports`).

### Consequences

* All of `failover-core` is gated at 95% mutation coverage; a regression in assertion strength fails
  the build, not just line coverage.
* Default build speed is unchanged (profile-gated); only the `mutation` profile/CI job runs PIT.
* The residual gap is equivalent/unreachable mutants, documented here rather than chased with
  artificial tests or production-code changes.

___

## ADR 47 — JDBC insert→update Race — Bounded Retry over Silent Drop

**Date : 15-JUN-2026**

### Status

Accepted

### Context

On non-merge dialects (or after a `BadSqlGrammarException` disables native merge — ADR 13),
`FailoverStoreJdbc.store` uses an INSERT → `DuplicateKeyException` → UPDATE pattern. A race window
exists between the failed INSERT and the follow-up UPDATE: a concurrent expiry delete
(`ExpiryCleanupScheduler`, ADR 7) can remove the row, so the UPDATE affects 0 rows and the write is
lost (audit A-4). The original behaviour logged the 0-row case at `debug` and dropped the write —
acceptable for a regenerable cache, but the lost write was effectively invisible and the edge was
untested on the fallback path. Native-merge dialects (every supported DB) avoid the window entirely.

### Decision

Apply a **single bounded retry** instead of documenting the drop as accepted. `insertOrUpdate` loops
up to `MAX_INSERT_OR_UPDATE_ATTEMPTS = 2`: when the UPDATE affects 0 rows, the row is now absent
(the concurrent delete already ran), so re-running the INSERT on the next iteration succeeds. The
loop is bounded so a pathologically, repeatedly-deleted key cannot spin — at most one re-INSERT.

If every attempt loses the race, the write is **abandoned and logged at `warn`** (escalated from the
prior `debug`), stating the value will be re-stored on the next successful upstream call. The retry
is *not* applied to the native-merge path, which is already atomic.

The deterministic race (UPDATE → 0 rows) is covered by mock-based unit tests in
`FailoverStoreJdbcMergeFallbackTest`: `reInsertSucceedsAfterUpdateLosesTheRace` (retry recovers the
write) and `updateZeroRowsAbandonsWriteAfterBoundedRetry` (2 INSERT + 2 UPDATE attempts, then give
up, no exception).

### Consequences

* The common single-collision race now self-heals with one re-INSERT instead of silently dropping.
* The bound guarantees termination; a persistently-deleted key abandons after 2 attempts rather than
  looping.
* A genuinely lost write is now visible at `warn`, not buried at `debug`.
* Behavioural change is confined to the INSERT/UPDATE fallback; the native-merge path is untouched.

___

## ADR 48 — Failover Lifecycle Logging — INFO Event, DEBUG Payload Body

**Date : 15-JUN-2026**

### Status

Accepted

### Context

`DefaultFailoverHandler` logged each store / recover / expired-delete at `INFO` **with the full
`ReferentialPayload` `toString`** in the message. In high-throughput services every failover store
and recover then serialises the whole payload at `INFO`, which is both chatty and an allocation cost
on a path that can fire frequently (audit Q-4). The lifecycle *event* (which referential, store vs
recover) is the operationally useful signal; the full payload body is diagnostic detail.

### Decision

Split each site into two log statements: an `INFO` **lifecycle** line carrying only the referential
name (the event), and a `DEBUG` line carrying the full `ReferentialPayload` body. Applied to all
three sites — store, successful recover, and expired-payload delete. Operators keep
failover-happening visibility at `INFO` without paying full-payload serialisation; payload bodies
are available at `DEBUG` when diagnosing.

### Consequences

* No full-payload `toString` on the `INFO` path; lifecycle events still visible at `INFO`.
* Payload bodies remain obtainable by raising the handler logger to `DEBUG`.
* Purely observational change — no behavioural effect on store/recover semantics.

___
