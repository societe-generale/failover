# Architecture Decision Record (ADR)
> An architectural decision record (ADR) is a document that captures an important architectural decision made along with its context and consequences.
> Read more about [Architecture decision record (ADR)](https://github.com/joelparkerhenderson/architecture_decision_record)

#### ADR Template 
> For each decision, please write these sections in ADR file :
> A Template by Michael Nygard : [More-details](https://github.com/joelparkerhenderson/architecture_decision_record/blob/master/adr_template_by_michael_nygard.md)
___

```markdown
## Title 

**Date : dd-MMM-yyyy** 

#### Status

What is the status, such as proposed, accepted, rejected, deprecated, superseded, etc.?

#### Context

What is the issue that we're seeing that is motivating this decision or change?

#### Decision

What is the change that we're proposing and/or doing?

#### Consequences

What becomes easier or more difficult to do because of this change?

```
___

## 1. Build a failover lib

**Date : 10-NOV-2021**    

#### Status
Accepted

#### Context
Most of our platforms are highly dependent on many referential systems (external api / internal api) for various business process.  
In such case any issues ( ex: unavailability ) on these referential systems will have a huge impact on our platform, both in prod and non prod.  
This can cause a huge impact on our platform resilience, where due to such issues on the referential systems or external systems (API) , our platform will become not usable.  

* Some of these referential won't change quite often or changes very rarely. Or some case business is ok for having slightly old data for their business continuity.
* If you have more such dependent services ( referential ) then the impact on your platforms wil be exponential

#### Decision
Build a lib to handle the failover with very minimal changes in each project or services.  
* Keep a local store (persistence store) for storing the referential after every successful call.
* On failure , recover the referential information from the local store
* Keep an expiry policy for each referential, so that we don't serve the data once its expired
* Keep do the cleanup for old / expired data from the local store
* The expiry duration need to be decided with the business 


#### Consequences
* The expiry duration need to be decided with the business
* IT Team should not keep the long expiry for all referential without discussing with the business. If they do so, the platform can have very old data

**Challenges:**
* This may be needed for many services, so the lib need to be kept very simple.
  
___

## 2. @Failover Annotations 

**Date : 10-NOV-2021**

#### Status
Accepted

#### Context
Use @Failover annotation for declaring the failover.   
We could also leverage the FeignClient annotation, but having a dedicated annotation for @Failover will help the readability of the code.  
Each Failover must have a unique name and should also help the developers to configure the expiry.

#### Decision
* **@Failover** annotation for declaring the failover. ex: ***@Failover(name = "client-all", expiryDuration = 1, expiryUnit = ChronoUnit.HOURS)***. The ***'name'*** must be unique, default value of expiry is 1 HOUR.

#### Consequences
* This only works with spring AOP.
* The expiry need to be configured wisely, with the business acceptable expiry.
___

## 3. Metadata for referential : As Of , Up To Date ? 

**Date : 15-NOV-2021**

#### Status
Accepted

#### Context
Most of the time, when we recover the referential from local store, it is important to keep below two information : 
1. **As Of** : To mention how old is the local referential data on the local storage 
2. **upToDate** : To mention whether the data is a live data or a recovered data

#### Decision
* Build a lightweight module for domain. The domain module which has only 1 annotation, 1 interface, 1 class

1. Referential to extend Abstract **Referential** Class with asOf , upToDate fields
```java
@Data
public abstract class Referential implements Serializable {

    private Boolean upToDate;

    private LocalDateTime asOf;
}
```

2. Referential to implement **ReferentialAware** interface with setAsOf , setUpToDate methods
```java
public interface ReferentialAware {
    void setUpToDate(Boolean upToDate);
    void setAsOf(LocalDateTime asOf);
}
```
* 
* If any of the above contract is applied , we will populate the information. 
* These are optional, and if we did not apply any of these contract, the metadata information will be not applied  

#### Consequences
* Failover Domain module dependency required for your service domain
___

## 4. Recovered Payload Handler

**Date : 15-NOV-2021**

#### Status
Accepted

#### Context
Some time, we won't be able to recover the referential , either we don't have the information in our local store , or the available information is too old and expired.   
In this context, the framework return null and this may create an issue in the further processing of your code.  

Some case , if team want to return a default object instead of null.  

#### Decision
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

#### Consequences
* The custom RecoveredPayloadHandler implementation can impact the behaviour of your platform based on the implementation.
___

## 5. Failover Store

**Date : 16-NOV-2021**

#### Status
Accepted

#### Context
How do we store the data locally for recovery ? It will be better to provide some basic storage options as part of the lib.  


#### Decision
* The lib provide below storage types :
1. **INMEMORY** : With basic ConcurrentHashMap implementation. We highly recommend to ***NOT USE*** this in ***Production***
2. **CAFFEINE** : With Caffeine cache implementation.
3. **JDBC** : With Jdbc implementation. This required ***JdbcTemplate*** and ***ObjectMapper*** beans
4. **CUSTOM** : Allows each service to provide a custom store.

* Both **store** & **delete** can be executed **asynchronously** 

#### Consequences
* The performance of the I/O operation on store and recover may impact the overall performance of your platform 
___

## 6. Failover Execution

**Date : 17-NOV-2021**

#### Status
Accepted

#### Context
By default, provide a basic failover execution with a simple try catch.

#### Decision
* We have provided below Failover Execution
1. BASIC : Basic failover execution with a simple try catch.
2. RESILIENCE : failover execution with resilience4j implementation. We highly recommend ***NOT TO CLUB*** this with other resilience or retry solutions.
3. CUSTOM : Allows each service to provide a custom Failover Execution.

* Make the failover execution as fault tolerant. Any exception on failover execution should not impact the actual business flow.

#### Consequences
* Clubbing multiple resilience with RESILIENCE Failover Execution may impact the overall performance and behaviour of your platforms
___

## 7. Auto Cleanup

**Date : 17-NOV-2021**

#### Status
Accepted

#### Context
Provide a provision to auto cleanup the expired referential data from the referential store

#### Decision
* A configurable scheduler to trigger a auto cleanup
* Default is 1 hour.
* This can configure from yml by providing a new cron expression
* **Auto cleanup** can be executed **asynchronously**

#### Consequences
* Any custom expiry policy may not be applied on auto cleanup. 
* After expiry cleanup, you may have no data to recover.
___

## 8. Monitoring

**Date : 17-NOV-2021**

#### Status
Accepted

#### Context
Provide useful metrics for monitoring  

#### Decision
* Publish useful metrics for monitoring , which help us to create useful dashboard in Kibana

#### Consequences
* NA 
___

## 9. Key Generator

**Date : 30-DEC-2021**

#### Status
Accepted

#### Context
Provide an option to customize the key generator for a specific failover

#### Decision
* Provide an option to declare the custom key generator bean name in @Failover
* Provide a KeyGenerator lookup features to get the Key Generator by a given name
* Provide a Failover composite key generator which select the proper Key Generator if mentioned, else to use the Default Key Generator.

#### Consequences
* If the custom key generator (name) is missing, an exception may occur.
___

## 10. DefaultFailoverStore — Defensive Copy for Immutability

**Date : 25-MAY-2026**

#### Status
Accepted

#### Context
`FailoverStore` implementations hold `ReferentialPayload` instances in memory (ConcurrentHashMap, Caffeine cache).
If the same object reference is shared between the store and the caller, mutations on either side corrupt the stored state silently.
Additionally, data recovered from the failover store must always be distinguishable from a live response — a recovered payload must never appear as `upToDate=true`.

#### Decision
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

#### Consequences
* Every store/find operation allocates one extra `ReferentialPayload` object — negligible overhead.
* `upToDate` is always `false` for data served from the failover store, regardless of what was stored. Callers can rely on this invariant unconditionally.
* Mutating the payload object received from `find()` has no effect on the store.
* `DefaultFailoverStore` is automatically applied to all `FailoverStore` beans via `FailoverStoreBeanPostProcessor` (see ADR 11) — no manual wiring required.
___

## 11. FailoverStoreBeanPostProcessor — Uniform Store Wrapping via BeanPostProcessor

**Date : 25-MAY-2026**

#### Status
Deprecated — superseded by ADR 16, ADR 18, ADR 19

#### Context
Every `FailoverStore` bean — whether auto-configured (INMEMORY, CAFFEINE, JDBC) or user-provided (CUSTOM) — must be consistently wrapped with:
1. `DefaultFailoverStore` — enforces `upToDate=false` and defensive copy (see ADR 10).
2. `FailoverStoreAsync` — makes `store`, `delete`, and `cleanByExpiry` asynchronous via Spring `@Async`.

Previously, each auto-configuration class manually constructed the wrapping chain, leading to duplication and risk of inconsistency for custom stores. User-defined `FailoverStore` beans received no wrapping at all.

#### Decision
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

#### Consequences
* All `FailoverStore` beans — auto-configured or user-defined — get the same `FailoverStoreAsync → DefaultFailoverStore → concrete store` chain automatically.
* Custom store authors define only the raw `FailoverStore` implementation; wrapping is transparent. **CustomStore should not use any bean post construct or bean init**
* `FailoverStoreAsync` and `DefaultFailoverStore` themselves are excluded from re-wrapping by the guard.
* BPP ordering relative to `AsyncAnnotationBeanPostProcessor` is safe because `postProcessBeforeInitialization` always precedes AOP proxy creation.
___

## 12. MethodExceptionPolicy — Pluggable Exception Handling Strategy

**Date : 26-MAY-2026**

#### Status
Accepted

#### Context
When a primary call fails and failover recovery is attempted, the framework previously had a fixed outcome: swallow the original exception and return the recovered result (or `null` if recovery also failed).
This gave callers no way to control what happens post-recovery:

* Some teams want the original exception to propagate when the store has nothing to serve, so monitoring and alerting fire correctly.
* Some teams want silent degradation (return stale data or `null`) regardless of recovery success.
* Some teams need custom logic — enriching the exception, mapping it to a domain-specific type, publishing a metric.

#### Decision
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

#### Consequences
* Default behaviour (`RethrowIfNoRecoveryMethodExceptionPolicy`) is safe: stale data is preferred, but the original failure is surfaced when there is nothing to serve. This ensures monitoring fires on genuine outages with empty stores.
* `NeverRethrowMethodExceptionPolicy` gives a pure degraded-mode experience at the cost of silent failures.
* Any team can inject a custom `MethodExceptionPolicy` bean to override auto-configuration via `@ConditionalOnMissingBean`.
* The exception policy operates at the failover boundary only — exceptions thrown during store/recover operations are already logged and swallowed internally.
___

## 13. JDBC Native Merge/Upsert — Dialect Detection and Runtime Fallback

**Date : 26-MAY-2026**

#### Status
Accepted

#### Context
`FailoverStoreJdbc.store()` previously relied on INSERT-only for new records. Under concurrent writes to the same key this created a race between a SELECT-check and an INSERT, causing `DuplicateKeyException` noise and requiring careful retry logic. Additionally, a simple INSERT gives no atomicity guarantee when the same key is written from multiple threads or nodes.

Native merge/upsert operations (H2 `MERGE INTO…KEY`, PostgreSQL `ON CONFLICT DO UPDATE`, MySQL/MariaDB `ON DUPLICATE KEY UPDATE`, Oracle `MERGE USING DUAL`) provide atomic upsert semantics. However, each database uses a different syntax and not all databases support any of these forms.

Two failure scenarios must be handled:
1. **Build-time unknown dialect**: the detected product name does not match any known dialect (e.g. HSQLDB, DB2, or unrecognised proxies).
2. **Runtime dialect mismatch**: the product name reported at startup is incorrect (e.g. a middleware proxy reports `"PostgreSQL"` but the actual underlying DB rejects the query), causing a `BadSqlGrammarException` on the first `store()` call.

#### Decision
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

#### Consequences
* Atomic upsert with zero additional SELECT round-trip for all four supported databases.
* Unknown databases fall back gracefully to INSERT + UPDATE — no configuration change required.
* A single `BadSqlGrammarException` permanently disables merge for the lifetime of the bean. Subsequent calls pay only one `update()` (INSERT) instead of two, recovering performance after the one-time failure.
* The `AtomicBoolean` flip is thread-safe: multiple concurrent threads may each throw `BadSqlGrammarException` and call `set(false)` simultaneously — this is safe and idempotent.
* Adding a new dialect requires only a new SQL constant and a new `contains()` branch in `DefaultFailoverStoreQueryResolver.resolveMergeQuery()`.
___

## 14. DatabaseResolver — Strategy Interface for Database Product Detection

**Date : 26-MAY-2026**

#### Status
Accepted

#### Context
Database dialect selection (ADR 13) requires knowing the database product name at startup. Previously this detection logic was embedded directly inside `FailoverStoreJdbc` constructor, which caused three problems:
1. **Untestable in isolation**: unit tests for `FailoverStoreJdbc` required a live `DataSource` to exercise dialect selection.
2. **Not overridable**: applications using proxies, middleware (e.g. PgBouncer), or test environments where the reported product name is incorrect had no way to override detection.
3. **Single responsibility violation**: `FailoverStoreJdbc` held unrelated JDBC metadata concerns alongside store operations.

#### Decision
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

#### Consequences
* `DefaultFailoverStoreQueryResolver` is fully unit-testable: mock or stub `DatabaseResolver.resolve()` without a live datasource.
* `DefaultDatabaseResolver` is independently unit-testable: mock `JdbcTemplate` to simulate product name retrieval and failure paths.
* Applications with non-standard environments (proxies, cloud SQL, test doubles) override detection by providing a single-bean `DatabaseResolver` implementation.
* `resolve()` is called once at construction — no repeated metadata queries per `store()` call.
* Returning `null` is the defined contract for "unknown" — all consumers treat `null` as "disable merge/use fallback".
___

## 15. FailoverStoreQueryResolver — Single-Responsibility Co-location of All JDBC Query Concerns

**Date : 26-MAY-2026**

#### Status
Accepted

#### Context
`FailoverStoreJdbc` originally held SQL strings, parameter arrays, SQL type arrays, and `ResultSet` mapping inline. This caused several issues:
1. **Fragile column ordering**: SQL column order, `Object[]` parameter order, and `int[]` type order must always agree. When scattered across a class, a column rename or reorder requires coordinated changes in at least three places, with no compile-time safety net.
2. **Untestable SQL logic**: verifying that INSERT column order matches the UPDATE SET/WHERE order, or that all SQL placeholders `?` match the parameter array length, required a running database.
3. **Not replaceable**: users who needed custom SQL (different schema, encrypted payload column, additional audit columns) had no extension point short of replacing the entire `FailoverStoreJdbc` bean.
4. **Dialect SQL embedded in the store**: merge-dialect SQL templates lived alongside CRUD operation code, mixing concerns.

#### Decision
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

#### Consequences
* SQL text, parameter order, and SQL types for each operation live in one class. A column change requires editing exactly one file.
* `DefaultFailoverStoreQueryResolver` is fully unit-tested without a database: placeholder count consistency, column order, dialect selection, parameter binding, row mapping, and deserialization edge cases are all covered by pure unit tests.
* `FailoverStoreJdbc` is reduced to pure JDBC execution logic — it knows nothing about SQL, column types, or serialization.
* Teams that need a different schema (additional columns, different key structure, encrypted payload, non-JSON serialization) implement `FailoverStoreQueryResolver` and declare the bean — no other changes required.
* `PayloadColumnResolver` remains a separate, narrower extension point for teams that only need to change the payload column SQL type and extraction method, without replacing the entire resolver.
___

## 16. Removal of BeanPostProcessor-based Store Wrapping (Supersedes ADR 11)

**Date : 02-JUN-2026**

#### Status
Accepted — supersedes ADR 11

#### Context
ADR 11 introduced `DefaultFailoverStoreBeanPostProcessor` and `AsyncFailoverStoreBeanPostProcessor` registered as `static @Bean` entries inside `FailoverAutoConfiguration` to intercept raw `FailoverStore` beans and wrap them with `DefaultFailoverStore → FailoverStoreAsync`.

This approach had two critical problems once multi-tenancy was introduced:

1. **BPP only intercepts Spring-managed beans.** In multi-tenant mode, per-tenant `FailoverStore` instances are created programmatically inside a `TenantStoreFactory` — they are never registered as individual Spring beans and therefore invisible to any `BeanPostProcessor`. The async/defensive-copy wrapping would have been silently skipped for every tenant except the first.
2. **@Async depends on Spring AOP proxy.** `FailoverStoreAsync` previously used `@Async` on its methods to offload work. `@Async` only functions when the call goes through a Spring-managed AOP proxy. A `FailoverStoreAsync` instance created inside a factory lambda is not a proxy — `@Async` degrades to synchronous execution silently with no error or warning.

#### Decision
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

#### Consequences
* Wrapping chain is explicit, deterministic, and visible at a single assembly point — no implicit BPP intercept magic.
* Multi-tenant per-tenant stores receive the same wrapping chain as single-tenant stores (see ADR 20).
* ADR 11 is deprecated. The threading and ordering guarantees it relied on (`postProcessBeforeInitialization` ordering vs. `AsyncAnnotationBeanPostProcessor`) are no longer relevant.
* Removing `@EnableAsync` from the autoconfigure module means applications must provide `@EnableAsync` themselves if they need `@Async` elsewhere — this is the correct responsibility boundary.
___

## 17. TenantStoreFactory SPI — Abstracting Store Creation from Store Assembly

**Date : 02-JUN-2026**

#### Status
Accepted

#### Context
Previously each store auto-configuration (`FailoverCaffeineStoreAutoConfiguration`, `FailoverJdbcStoreAutoConfiguration`, `FailoverAutoConfiguration`) produced a fully assembled `FailoverStore<Object>` bean directly. This fused two separate concerns:

1. **How to create a raw store** (which implementation, with which configuration).
2. **How to assemble the decorator chain** (DefaultFailoverStore + FailoverStoreAsync).

When multi-tenancy was introduced, each tenant needed its own isolated raw store instance (separate Caffeine cache, separate JDBC table prefix). Producing a single fully-assembled `FailoverStore` bean from a store auto-configuration was incompatible with this: the bean registry holds one bean, but multiple tenant stores must exist simultaneously and be created on demand.

#### Decision
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

#### Consequences
* Store creation and store assembly are decoupled. Adding a new store type requires implementing `TenantStoreFactory` only; the decorator chain is applied uniformly by the assembler.
* Multi-tenant configurations override the auto-configured `TenantStoreFactory` via `@ConditionalOnMissingBean(TenantStoreFactory.class)` — no changes to `FailoverStoreAutoConfiguration`.
* Custom store authors implement `TenantStoreFactory` (not `FailoverStore` directly) when registering custom stores with multi-tenant awareness. For single-tenant custom stores, `() -> myStore` is sufficient since `SINGLE_TENANT_ID` is the only argument ever passed.
* `SINGLE_TENANT_ID = "_single_"` is a sentinel chosen to be unlikely to collide with real tenant IDs. It is not validated — implementations may ignore it.
___

## 18. FailoverStoreAutoConfiguration — Central Assembler

**Date : 02-JUN-2026**

#### Status
Accepted

#### Context
After introducing `TenantStoreFactory` (ADR 17), the store auto-configurations produce a factory, not a ready `FailoverStore`. Something must consume the factory and produce the single `FailoverStore<Object>` bean that the rest of the framework depends on.

Previously this assembly was implicit via `BeanPostProcessor` interception (ADR 11, now removed). With explicit assembly, a dedicated `@AutoConfiguration` class is the correct location.

#### Decision
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

#### Consequences
* Assembly is visible in one class — no implicit interception.
* Async vs. sync is a single YAML property (`failover.store.async`), not two separate auto-configurations or BPP registrations.
* `failoverTaskExecutor` uses virtual threads by default (JDK 21+); applications override by declaring a `TaskExecutor` bean named `failoverTaskExecutor`.
* `@ConditionalOnMissingBean(FailoverStore.class)` means applications that declare their own `FailoverStore` bean bypass this assembler entirely — same behaviour as before.
___

## 19. FailoverStoreAsync — Explicit TaskExecutor Replacing @Async

**Date : 02-JUN-2026**

#### Status
Accepted

#### Context
`FailoverStoreAsync` previously used `@Async` on `store`, `delete`, and `cleanByExpiry` to offload writes to a Spring-managed thread pool.

`@Async` has a critical limitation: it only works when the call is dispatched through a Spring AOP proxy. An instance created programmatically — such as one created inside a `TenantStoreFactory.create()` lambda — is not a Spring proxy and `@Async` degrades to synchronous, in-thread execution with no warning.

In multi-tenant mode, each tenant's `FailoverStoreAsync` is created programmatically inside the decorator lambda in `MultiTenantFailoverStore`. Every write operation would have silently become synchronous, undoing the performance benefit of the async store.

Additionally, `@Async` relies on `@EnableAsync` being active in the application context, which imposed a constraint on the autoconfiguration module (it had to include `@EnableAsync` in a nested configuration class). Removing this is desirable: `@EnableAsync` is a cross-cutting concern that belongs to the application, not the library.

#### Decision
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

#### Consequences
* `FailoverStoreAsync` works correctly whether instantiated as a Spring bean or programmatically inside a factory — behaviour is identical in both cases.
* `@EnableAsync` is no longer imposed on the application context by the library. Applications that need `@Async` for their own beans must add `@EnableAsync` themselves (same as any other Spring project).
* The executor used for failover async operations is the `failoverTaskExecutor` bean registered by `FailoverStoreAutoConfiguration` (virtual threads on JDK 21+, overridable).
* `find()` remains synchronous — the caller needs the result immediately and there is no asynchrony benefit.
* `cleanByExpiry()` is now also submitted to the executor — previously it was `@Async` but with the same silent-degradation risk in non-Spring-managed instances.
___

## 20. MultiTenantFailoverStore — Outermost Per-Tenant Routing Decorator

**Date : 02-JUN-2026**

#### Status
Accepted

#### Context
Multi-tenant applications need to isolate failover data per tenant: tenant A must not read or write tenant B's referential store, regardless of the backing store technology.

The decorator chain for a single tenant is `FailoverStoreAsync(DefaultFailoverStore(rawStore))`. Multi-tenancy requires this chain to be replicated per tenant, with routing at the outermost layer so that every operation is directed to the correct tenant's chain before any thread boundary is crossed.

Key constraints:
1. **ThreadLocal must be read on the calling thread.** `TenantResolver` reads tenant context from a `ThreadLocal` (or HTTP request). Once the operation enters `FailoverStoreAsync`'s executor, the `ThreadLocal` may not be set.
2. **Per-tenant stores must be created lazily.** The full set of tenants is not always known at startup, and eagerly creating stores for all tenants may be wasteful or impossible.
3. **`cleanByExpiry` is called by the scheduler** (not a request thread) — no tenant context is available. It must clean all initialized tenant stores.

#### Decision
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

#### Consequences
* Tenant resolution always occurs on the calling thread — `ThreadLocal` contract is preserved.
* Per-tenant stores are created lazily on first access; `SINGLE_TENANT_ID` is never used in multi-tenant mode.
* `cleanByExpiry` is fan-out across all initialized tenants. Tenants receiving their first request after the cleanup tick will skip one cleanup cycle — acceptable because an empty store has nothing to expire.
* `defaultTenant` allows applications to configure a fallback for unauthenticated or system requests without throwing an exception.
* `decorator` parameter makes `MultiTenantFailoverStore` independent of specific decorator implementations — it can be unit-tested with a simple identity decorator.
___

## 21. FailoverStoreMultiTenantAutoConfiguration — Multi-Tenant Auto-Configuration and TenantResolver SPI

**Date : 02-JUN-2026**

#### Status
Accepted

#### Context
Multi-tenant store support (ADR 20) requires configuration wiring: the `MultiTenantFailoverStore` bean must be assembled with the correct `TenantStoreFactory`, `TenantResolver`, and decorator function. Additionally, JDBC isolation strategy and per-tenant parameters must be configurable via YAML.

Tenant resolution strategy (HTTP header, security context, custom logic) varies by application — the library cannot provide a universal default without pulling in inappropriate dependencies (Spring Security, Servlet API, etc.).

#### Decision
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

#### Consequences
* Zero impact on existing single-tenant deployments: `failover.store.multitenant.enabled` defaults to `false`.
* Applications must declare a `TenantResolver` bean; the library does not auto-detect tenant context to avoid opinionated dependencies.
* `TABLE_PREFIX` strategy works with a single shared `DataSource` and `JdbcTemplate` — no infrastructure changes required.
* `SCHEMA` strategy delegates all routing to the application's `DataSource` — the library performs no schema-level work beyond using the provided `JdbcTemplate`.
* Per-tenant `TenantConfig` entries are optional for Caffeine and InMemory stores; only JDBC uses `tablePrefix`.
* `defaultTenant` prevents exceptions for unauthenticated or internal requests that have no tenant context, at the cost of routing all such requests to a shared store.
___
