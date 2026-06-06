# Customization

This page covers all extension points: module dependencies, execution strategies, store types, JDBC customization, async mode, multi-tenant setup, and pluggable policies.

---

## 1. Maven Modules

Each failover capability lives in its own module. Pull in only what you need.

### failover-domain

Basic annotations and domain classes. No transitive dependencies.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-domain</artifactId>
    <version><!-- latest --></version>
</dependency>
```

### failover-core

All major framework components. Lightweight — no large framework dependencies.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-core</artifactId>
    <version><!-- latest --></version>
</dependency>
```

### failover-store-inmemory

In-memory store backed by `ConcurrentHashMap`. **Not for production use.**

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-inmemory</artifactId>
    <version><!-- latest --></version>
</dependency>
```

```yaml
failover:
  store:
    type: inmemory
```

> **Warning:** Data is process-local and lost on restart. Use only for local development or testing.

### failover-store-caffeine

Caffeine cache-backed store with per-entry TTL. Eviction is managed automatically by Caffeine.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-caffeine</artifactId>
    <version><!-- latest --></version>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version><!-- latest --></version>
</dependency>
```

```yaml
failover:
  store:
    type: caffeine
```

### failover-store-jdbc

Durable JDBC-backed store. Requires a `DataSource` and `JdbcTemplate` bean.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-jdbc</artifactId>
    <version><!-- latest --></version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

```yaml
failover:
  store:
    type: jdbc
```

### failover-store-multitenant

Multi-tenant SPI: `TenantStoreFactory`, `TenantResolver`, `MultiTenantFailoverStore`, `TenantContext`.
Required when `failover.store.multitenant.enabled=true`.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-multitenant</artifactId>
    <version><!-- latest --></version>
</dependency>
```

> Pulled in transitively by the autoconfigure starter when multi-tenant mode is enabled. Add explicitly only when referencing `TenantStoreFactory` or `TenantResolver` directly in application code.

---

## 2. Failover Execution

Controls how the primary call is attempted and how failures are handled.

### BASIC _(default)_

Simple try/catch execution. No external dependencies.

```yaml
failover:
  type: basic
```

### RESILIENCE

Wraps execution with a Resilience4j circuit breaker. Do **not** combine with other retry or resilience libraries on the same call.

```yaml
failover:
  type: resilience
```

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
    <version><!-- latest --></version>
</dependency>
```

### CUSTOM

Provide your own `FailoverExecution<T>` bean for full control over the execution strategy.

```yaml
failover:
  type: custom
```

```java
public interface FailoverExecution<T> {
    T execute(Failover failover, Supplier<T> supplier, Method method, List<Object> args);
}

@Component
public class CustomFailoverExecution<T> implements FailoverExecution<T> {
    @Override
    public T execute(Failover failover, Supplier<T> supplier, Method method, List<Object> args) {
        // custom execution logic
    }
}
```

---

## 3. Failover Store

### Async Mode

By default, write operations (`store`, `delete`, `cleanByExpiry`) are offloaded to a background `TaskExecutor` so the calling thread is not blocked. `find` is always synchronous.

```yaml
failover:
  store:
    async: true    # default — non-blocking writes
    # async: false # synchronous writes on calling thread
```

The autoconfiguration registers a `SimpleAsyncTaskExecutor` (virtual threads on JDK 21+) named `failoverTaskExecutor`. Users can override it with your own (if required) by defining a `TaskExecutor` bean named `failoverTaskExecutor`.:

```java
@Bean("failoverTaskExecutor")
public TaskExecutor failoverTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("failover-");
    executor.initialize();
    return executor;
}
```

> The failover library does **not** enable `@Async` globally. If your application uses `@Async`, add `@EnableAsync` to your own `@Configuration` class.

---

### Custom Store

Implement `FailoverStore<T>` and register it as a Spring bean. The autoconfiguration backs off via `@ConditionalOnMissingBean(FailoverStore.class)`.

```java
public interface FailoverStore<T> {
    void store(ReferentialPayload<T> referentialPayload);
    void delete(ReferentialPayload<T> referentialPayload);
    Optional<ReferentialPayload<T>> find(String name, String key);
    void cleanByExpiry(LocalDateTime expiry);
}
```

```java
@Configuration
public class MyStoreConfig {

    @Bean
    public FailoverStore<Object> failoverStore() {
        return new MyCustomStore<>();
    }
}
```

```yaml
failover:
  store:
    type: custom    # documents intent; bean presence is what controls behaviour
```

> For per-tenant isolation with a custom store, implement `TenantStoreFactory` instead (see [Multi-Tenant Store](#multi-tenant-store)). The assembler applies `DefaultFailoverStore` and `FailoverStoreAsync` wrapping automatically.

---

### Custom TenantStoreFactory

`TenantStoreFactory<T>` is the SPI for creating an isolated raw store per tenant. Prefer this over implementing `FailoverStore` directly when multi-tenant support is needed.

```java
@FunctionalInterface
public interface TenantStoreFactory<T> {
    String SINGLE_TENANT_ID = "_single_";
    FailoverStore<T> create(String tenantId);
}
```

`create(tenantId)` is always called on the calling thread. Implementations may safely read `ThreadLocal` values.

```java
@Configuration
public class MyStoreFactoryConfig {

    @Bean
    public TenantStoreFactory<Object> myTenantStoreFactory() {
        return tenantId -> new MyCustomStore<>(tenantId);
    }
}
```

In single-tenant mode, `create(TenantStoreFactory.SINGLE_TENANT_ID)` is called once at startup.

---

### Multi-Tenant Store

Multi-tenant mode routes every failover operation to an isolated per-tenant store. See [failover-store.md](failover-store.md) for the full reference.

```yaml
failover:
  store:
    multitenant:
      enabled: true
      default-tenant: acme    # fallback when TenantResolver returns null
```

#### TenantResolver

The application **must** provide a `TenantResolver` bean — the library does not supply a default.

```java
@FunctionalInterface
public interface TenantResolver {
    @Nullable String resolve();
}
```

Common patterns:

```java
// From TenantContext ThreadLocal — populate in a servlet filter
@Bean
public TenantResolver tenantResolver() {
    return new TenantContextTenantResolver();
}

// From Spring Security
@Bean
public TenantResolver tenantResolver() {
    return () -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    };
}

// Fixed value — useful for testing or single-tenant migration
@Bean
public TenantResolver tenantResolver() {
    return new FixedTenantResolver("acme");
}
```

#### TenantContext

`TenantContext` is a `ThreadLocal` holder. Populate it per-request in a filter and clear it in a `finally` block:

```java
@Component
public class TenantContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        TenantContext.set(req.getHeader("X-Tenant-ID"));
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
```

#### JDBC Isolation Strategies

| Strategy               | How it works                                                                    | Config key                       |
|------------------------|---------------------------------------------------------------------------------|----------------------------------|
| `table-prefix` _(default)_ | `effectiveTable = tenantPrefix + globalPrefix + FAILOVER_STORE`             | `tenants.<id>.table-prefix`      |
| `schema`               | `AbstractRoutingDataSource` routes to tenant schema; same table name per schema | Application-managed `DataSource` |

```yaml
failover:
  store:
    type: jdbc
    jdbc:
      table-prefix: DEMO_
    multitenant:
      enabled: true
      strategy: table-prefix
      default-tenant: acme
      tenants:
        acme:
          table-prefix: ACME_      # effective table: ACME_DEMO_FAILOVER_STORE
        globex:
          table-prefix: GLOBEX_    # effective table: GLOBEX_DEMO_FAILOVER_STORE
```

> **SCHEMA + async:** `TenantContext` is a `ThreadLocal` not propagated to executor threads. Set `failover.store.async=false` when using the SCHEMA strategy, or propagate `TenantContext` via a `TaskDecorator` on `failoverTaskExecutor`.

---

## 4. JDBC Customization

### Table Prefix

Namespace the failover table with a prefix:

```yaml
failover:
  store:
    type: jdbc
    jdbc:
      table-prefix: DEMO_    # table becomes DEMO_FAILOVER_STORE
```

Create the matching table:

```sql
CREATE TABLE DEMO_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP WITH TIME ZONE  NOT NULL,
    EXPIRE_ON      TIMESTAMP WITH TIME ZONE  NOT NULL,
    PAYLOAD        VARCHAR(2000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

### PayloadColumnResolver

The default payload column is `VARCHAR(2000)`. Override with `PayloadColumnResolver` for `TEXT` or `CLOB`:

```java
public interface PayloadColumnResolver {
    int payloadType();
    String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException;
}
```

Default implementation:

```java
public class VarcharPayloadColumnResolver implements PayloadColumnResolver {

    @Override
    public int payloadType() {
        return Types.VARCHAR;
    }

    @Override
    public String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException {
        return resultSet.getString(payloadColumn);
    }
}
```

Declare your own bean to replace it — `@ConditionalOnMissingBean` backs off the default automatically.

### DatabaseResolver

`DatabaseResolver` detects the database product name to select the correct native merge/upsert dialect.

```java
public interface DatabaseResolver {
    @Nullable String resolve();
}
```

The default `DefaultDatabaseResolver` reads from `conn.getMetaData().getDatabaseProductName()`. Override when:

- A proxy or middleware misreports the product name
- You want to hard-code a known dialect and skip the JDBC metadata round-trip
- You need observability (metrics, logging) around dialect detection

```java
@Configuration
public class FailoverDatabaseResolverConfig {

    // Hard-code dialect
    @Bean
    public DatabaseResolver databaseResolver() {
        return () -> "PostgreSQL";
    }
}
```

Or delegate with a fallback:

```java
@Bean
public DatabaseResolver databaseResolver(JdbcTemplate jdbcTemplate) {
    DefaultDatabaseResolver defaultResolver = new DefaultDatabaseResolver(jdbcTemplate);
    return () -> {
        String product = defaultResolver.resolve();
        return product != null ? product : "PostgreSQL";
    };
}
```

### FailoverStoreQueryResolver

`FailoverStoreQueryResolver` owns all JDBC query concerns: SQL text, parameter binding, result-set mapping, and payload serialization.

```java
public interface FailoverStoreQueryResolver {

    String getInsertQuery();
    String getUpdateQuery();
    String getSelectQuery();
    String getDeleteQuery();
    String getCleanUpQuery();
    @Nullable String getMergeQuery();

    <T> Object[] buildInsertMergeParams(ReferentialPayload<T> payload);
    int[]        buildInsertMergeTypes();

    <T> Object[] buildUpdateParams(ReferentialPayload<T> payload);
    int[]        buildUpdateTypes();

    <T> ReferentialPayload<T> mapRow(ResultSet rs) throws SQLException;
    @Nullable <T> T deserializePayload(@Nullable String payload, String clazzString);
}
```

Override when you need a different table schema, a custom merge dialect, or custom payload serialization (Protobuf, Avro, encrypted payloads).

**Option A — prefix or payload column only (no full replacement):**

```yaml
failover:
  store:
    jdbc:
      table-prefix: MY_
```

```java
@Bean
public PayloadColumnResolver payloadColumnHandler() {
    return new ClobPayloadColumnResolver();
}
```

**Option B — full custom `FailoverStoreQueryResolver`:**

```java
@Configuration
public class FailoverQueryResolverConfig {

    @Bean
    public FailoverStoreQueryResolver failoverStoreQueryResolver(
            DatabaseResolver databaseResolver,
            PayloadColumnResolver payloadColumnResolver) {
        return new DefaultFailoverStoreQueryResolver(
                "MY_PREFIX_", new JsonSerializer(objectMapper), databaseResolver, payloadColumnResolver);
    }
}
```

Or implement the interface from scratch:

```java
@Bean
public FailoverStoreQueryResolver failoverStoreQueryResolver() {
    return new CustomFailoverStoreQueryResolver();
}
```

---

## 5. Extension Points

### ExpiryPolicy

Controls when a stored payload expires.

```java
public interface ExpiryPolicy<T> {
    LocalDateTime computeExpiry(Failover failover);
    boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload);
}
```

The default `DefaultExpiryPolicy` uses the `expiryDuration` and `expiryUnit` from the `@Failover` annotation:

```java
public class DefaultExpiryPolicy<T> implements ExpiryPolicy<T> {

    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        return clock.now().plus(failover.expiryDuration(), failover.expiryUnit());
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload) {
        return clock.now().isAfter(referentialPayload.getExpireOn());
    }
}
```

To replace the global default, declare a bean named `"defaultExpiryPolicy"`.

To use a custom policy on a specific `@Failover`, set `expiryPolicy` to the bean name:

```java
@Failover(name = "client-by-id", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES,
          expiryPolicy = "custom-expiry-policy")
@GetMapping("/api/v1/clients/{id}")
Client findClientById(@PathVariable Long id);
```

```java
@Configuration
public class ExpiryPolicyConfig {

    @Bean("custom-expiry-policy")
    public ExpiryPolicy<Object> customExpiryPolicy() {
        return new CustomExpiryPolicy<>();
    }
}
```

> An exception is thrown if the bean named in `expiryPolicy` is not found in the application context.

---

### KeyGenerator

Generates the cache key from method arguments.

The default `DefaultKeyGenerator` is used unless overridden. To replace the global default, declare a bean named `"defaultKeyGenerator"`.

To use a custom key generator on a specific `@Failover`, set `keyGenerator` to the bean name:

```java
@Failover(name = "client-by-id", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES,
          keyGenerator = "custom-key-generator")
@GetMapping("/api/v1/clients/{id}")
Client findClientById(@PathVariable Long id);
```

```java
public class CustomKeyGenerator implements KeyGenerator {
    @Override
    public String key(Failover failover, List<Object> args) {
        // return generated key
    }
}

@Configuration
public class KeyGeneratorConfig {

    @Bean("custom-key-generator")
    public KeyGenerator customKeyGenerator() {
        return new CustomKeyGenerator();
    }
}
```

> An exception is thrown if the bean named in `keyGenerator` is not found in the application context.

---

### PayloadSplitter — Scatter/Gather Mode

Enables per-entity storage and partial recovery for methods that accept composite arguments such
as a comma-separated string (`ids = "1,2,3"`) or a collection.

**Problem without scatter/gather:**
Storing `getItems("1,2,3")` under a single composite key means `getItems("1,2")` — a subset —
always misses the cache, even though items 1 and 2 are already stored.

**Solution:**
Implement `PayloadSplitter<T>` and set `payloadSplitter` in `@Failover`. The framework
decomposes the composite payload into individual per-entity slices on store, and reassembles
available slices on recover. Partial recovery (some IDs cached, others missing) is handled
gracefully — only the available items are merged and returned.

#### Core types

```java
// Wraps the argument list for one cache entry (composite or individual)
public record SplitterContext(List<Object> args) {}

// Pairs an individual context with its payload slice — used on the store path
public record SplitResult<T>(SplitterContext context, T payload) {}
```

#### PayloadSplitter interface

```java
public interface PayloadSplitter<T> {

    // RECOVER path — no payload available
    // Decompose composite SplitterContext into N individual SplitterContexts (one per entity)
    List<SplitterContext> extract(SplitterContext context);

    // STORE path — payload is available
    // Decompose composite payload into per-entity slices; each SplitResult.context() must
    // match what extract() returns for the same composite context
    List<SplitResult<T>> split(SplitterContext context, T compositePayload);

    // RECOVER path — reassemble gathered slices into composite
    // items may be smaller than requested when some IDs were absent or expired
    T merge(List<T> items);
}
```

#### Key-generation guarantee

Each `SplitterContext.args()` is passed directly to `KeyGenerator.key(failover, args)`, which
flows through `FailoverKeyGenerator` — the same UUID derivation pipeline as every standard
(non-scatter) failover call. Any future change to the hashing algorithm or prefix automatically
applies to scatter-path keys with no additional work.

#### Example — single CSV string arg

```java
// @Failover(name = "items-by-ids", payloadSplitter = "itemsSplitter")
// List<Item> getItems(String ids)   ids = "1,2,3"

@Component("itemsSplitter")
public class ItemsPayloadSplitter implements PayloadSplitter<List<Item>> {

    @Override
    public List<SplitterContext> extract(SplitterContext context) {
        String csv = (String) context.args().getFirst();
        return Arrays.stream(csv.split(","))
                .map(id -> new SplitterContext(List.of(id.strip())))
                .toList();
    }

    @Override
    public List<SplitResult<List<Item>>> split(SplitterContext context, List<Item> payload) {
        String csv = (String) context.args().getFirst();
        String[] ids = csv.split(",");
        List<SplitResult<List<Item>>> results = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i].strip();
            Item item = payload.get(i);           // assumes payload order matches ids order
            results.add(new SplitResult<>(
                    new SplitterContext(List.of(id)),
                    List.of(item)));              // store single-element list per entry
        }
        return results;
    }

    @Override
    public List<Item> merge(List<List<Item>> items) {
        return items.stream().flatMap(List::stream).toList();
    }
}
```

#### Example — multi-arg with one CSV column

```java
// @Failover(name = "items", payloadSplitter = "multiArgItemsSplitter")
// List<Item> getItems(String status, String ids, String region)
//   status="active", ids="1,2,3", region="india"

@Component("multiArgItemsSplitter")
public class MultiArgItemsPayloadSplitter implements PayloadSplitter<List<Item>> {

    @Override
    public List<SplitterContext> extract(SplitterContext context) {
        String status = (String) context.args().get(0);
        String ids    = (String) context.args().get(1);
        String region = (String) context.args().get(2);
        return Arrays.stream(ids.split(","))
                .map(id -> new SplitterContext(List.of(status, id.strip(), region)))
                .toList();
    }

    @Override
    public List<SplitResult<List<Item>>> split(SplitterContext context, List<Item> payload) {
        List<SplitterContext> individualCtxs = extract(context);
        List<SplitResult<List<Item>>> results = new ArrayList<>();
        for (int i = 0; i < individualCtxs.size(); i++) {
            results.add(new SplitResult<>(individualCtxs.get(i), List.of(payload.get(i))));
        }
        return results;
    }

    @Override
    public List<Item> merge(List<List<Item>> items) {
        return items.stream().flatMap(List::stream).toList();
    }
}
```

#### Wiring

```java
@Failover(name = "items-by-ids",
          expiryDuration = 1, expiryUnit = ChronoUnit.HOURS,
          payloadSplitter = "itemsSplitter")
List<Item> getItems(String ids);
```

> An exception is thrown if the bean named in `payloadSplitter` is not found in the application context.

#### Partial recovery semantics

| IDs requested | IDs cached | Behaviour |
|---|---|---|
| `"1,2,3"` | `1`, `2`, `3` | All found — merge returns full list |
| `"1,2,3"` | `1`, `3` only | Partial — merge receives `[item1, item3]` |
| `"1,2,3,4"` | `1`, `2`, `3` cached from earlier `"1,2,3"` call | All three cached IDs found; `4` is a miss |
| `"1,2,3"` | none | All miss — returns `null` (or `RecoveredPayloadHandler` result) |
| `"1,2,3"` | `1` expired, `2` valid, `3` valid | Expired entry deleted; merge receives `[item2, item3]` |

#### Write amplification note

Each scatter-store writes N entries for N IDs. If a method is called frequently with large ID
sets, consider the throughput and storage impact before enabling scatter/gather mode.

---

### RecoveredPayloadHandler

Post-processes the payload returned from the failover store before it is handed back to the caller.

```java
public interface RecoveredPayloadHandler {
    <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload);
}
```

The default `PassThroughRecoveredPayloadHandler` returns the payload unchanged. Declare your own bean to replace it:

```java
@Component
public class CustomRecoveredPayloadHandler implements RecoveredPayloadHandler {

    @Override
    public <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload) {
        if (payload == null && Client.class.isAssignableFrom(clazz)) {
            Client stub = new Client(0L, "NA", 0);
            stub.setUpToDate(false);
            stub.setAsOf(LocalDateTime.now());
            return (T) stub;
        }
        return payload;
    }
}
```

---

### MethodExceptionPolicy

Controls whether the original exception is propagated or swallowed after a primary call fails and failover recovery has been attempted.

```java
@FunctionalInterface
public interface MethodExceptionPolicy {
    <T> T handle(MethodExceptionContext<T> context);
}
```

`MethodExceptionContext<T>` carries the full decision context:

```java
public record MethodExceptionContext<T>(
        Failover failover,
        Method method,
        List<Object> args,
        T recoveredResult,      // null if store was empty or recovery failed
        Throwable cause         // original exception from the primary call
) {}
```

#### Built-in policies

| Policy                                                 | Behaviour                                                                    | Config                              |
|--------------------------------------------------------|------------------------------------------------------------------------------|-------------------------------------|
| `RethrowIfNoRecoveryMethodExceptionPolicy` _(default)_ | Returns recovered data when available; rethrows when recovery produced `null` | `exception-policy: rethrow` or omit |
| `NeverRethrowMethodExceptionPolicy`                    | Always returns recovered data or `null`; never propagates                    | `exception-policy: never_throw`     |
| Custom                                                 | Any registered `MethodExceptionPolicy` bean                                  | `exception-policy: custom`          |

```yaml
failover:
  exception-policy: rethrow       # default
  # exception-policy: never_throw
  # exception-policy: custom
```

Custom policy example — rethrow for unexpected failures, serve `null` for known transient errors:

```java
@Configuration
public class FailoverExceptionPolicyConfig {

    @Bean
    public MethodExceptionPolicy methodExceptionPolicy() {
        return context -> {
            if (context.recoveredResult() != null) {
                return context.recoveredResult();
            }
            if (context.cause() instanceof TimeoutException) {
                return null;    // transient — degrade gracefully
            }
            throw new RuntimeException(
                    "Failover: no recovery for " + context.failover().name(), context.cause());
        };
    }
}
```

---

## 6. Schedulers

Two built-in schedulers run on configurable cron expressions:

| Scheduler      | Purpose                                               | Default                  |
|----------------|-------------------------------------------------------|--------------------------|
| `report-cron`  | Publishes failover configuration reports for monitoring | Daily (`0 0 0 * * *`)  |
| `cleanup-cron` | Removes expired entries from the store                | Hourly (`0 0 * * * *`)   |

```yaml
failover:
  scheduler:
    report-cron: 0 0 0 * * *    # daily
    cleanup-cron: 0 0 * * * *   # hourly
```

---

## 7. Scatter/Gather Parallel Execution

Scatter/gather (`@Failover(payloadSplitter = "...")`) dispatches N slice operations per request.
By default slices execute **sequentially**. Enable parallel dispatch with virtual threads:

```yaml
failover:
  scatter:
    parallel: true    # default: false
```

When enabled, `ScatterGatherFailoverHandler` uses a `SimpleAsyncTaskExecutor` with virtual
threads to dispatch all slices concurrently. Slice latency drops from O(N) to O(1) for
I/O-bound stores.

### Custom Executor

Override the auto-configured executor by declaring a bean named `scatterGatherExecutor`:

```java
@Bean("scatterGatherExecutor")
public TaskExecutor scatterGatherExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(50);
    executor.setThreadNamePrefix("failover-scatter-");
    executor.initialize();
    return executor;
}
```

---

## 8. Context Propagation for Parallel Scatter

When `failover.scatter.parallel=true`, slice tasks run on executor threads. Thread-local context
(tenant ID, MDC, Micrometer span) is not propagated automatically. The `ContextPropagator` SPI
handles this.

### Auto-configured Composition

| Condition | Active propagators |
|---|---|
| Single-tenant, no Micrometer | MDC |
| Multi-tenant only | Tenant → MDC |
| Micrometer Tracer bean present | Micrometer → MDC |
| Multi-tenant + Micrometer | Tenant → Micrometer → MDC |

`MicrometerContextPropagator` activates automatically when `io.micrometer:micrometer-tracing` is
on the classpath and a `Tracer` bean is present (e.g. via Spring Boot Actuator + tracing bridge).

### Custom ContextPropagator

Declare a `ContextPropagator` bean to replace the auto-composed default:

```java
@Configuration
public class FailoverContextConfig {

    @Bean
    public ContextPropagator contextPropagator(
            TenantContextPropagator tenant,
            Tracer tracer) {

        // Custom propagator — e.g. Spring Security principal
        ContextPropagator security = task -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return () -> {
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(auth);
                SecurityContextHolder.setContext(ctx);
                try {
                    task.run();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
        };

        return CompositeContextPropagator.of(
            tenant,                              // TenantContext
            new MicrometerContextPropagator(tracer),  // Span context
            security,                            // Spring Security principal
            new MdcContextPropagator()           // MDC (always last)
        );
    }
}
```

### Built-in Propagators

| Class | Module | Propagates |
|---|---|---|
| `MdcContextPropagator` | `failover-core` | SLF4J MDC — covers trace/span IDs when Micrometer Tracing bridge is active |
| `TenantContextPropagator` | `failover-store-multitenant` | `TenantContext` tenant ID — required for correct per-tenant store routing |
| `MicrometerContextPropagator` | `failover-spring-boot-autoconfigure` | Micrometer `Span` — records slice work as child spans in distributed traces |
| `CompositeContextPropagator` | `failover-core` | Chains multiple propagators; applied in list order |

---

## 9. PayloadSplitter — Scatter/Gather Implementation

Register a `PayloadSplitter<T, R>` Spring bean and reference it by name in `@Failover`:

```java
@Failover(name = "third-parties-failover", expiry = 24, expiryUnit = ChronoUnit.HOURS,
          payloadSplitter = "thirdPartiesSplitter")
ThirdPartiesResult fetchThirdParties(String csvIds);
```

### Sample Implementation

```java
/**
 * Splits a ThirdPartiesResult (composite) into individual ThirdParty slices.
 * T = ThirdPartiesResult (composite), R = ThirdParty (slice)
 */
@Component("thirdPartiesSplitter")
public class ThirdPartyPayloadSplitter implements PayloadSplitter<ThirdPartiesResult, ThirdParty> {

    /**
     * Store path: one StoreContext per ThirdParty in the composite result.
     * Each slice uses the ThirdParty's own ID as its key argument.
     */
    @Override
    public List<StoreContext<ThirdParty>> splitOnStore(StoreContext<ThirdPartiesResult> context) {
        return context.getPayload().getThirdParties().stream()
                .map(tp -> StoreContext.<ThirdParty>builder()
                        .failover(context.getFailover())
                        .args(List.of(String.valueOf(tp.getId())))
                        .payload(tp)
                        .build())
                .toList();
    }

    /**
     * Recover path: one RecoverContext per ID in the comma-separated arg.
     * Each slice recovers a single ThirdParty by its individual ID.
     */
    @Override
    public List<RecoverContext<ThirdParty>> splitOnRecover(RecoverContext<ThirdPartiesResult> context) {
        String csvArg = (String) context.getArgs().getFirst();
        return Arrays.stream(csvArg.split(","))
                .map(id -> RecoverContext.<ThirdParty>builder()
                        .failover(context.getFailover())
                        .args(List.of(id.trim()))
                        .clazz(ThirdParty.class)
                        .cause(context.getCause())
                        .build())
                .toList();
    }

    /**
     * Merge path: collect recovered ThirdParty payloads back into ThirdPartiesResult.
     * Null payloads (cache miss for that ID) are filtered out.
     */
    @Override
    public RecoverContext<ThirdPartiesResult> merge(List<RecoverContext<ThirdParty>> contexts) {
        List<ThirdParty> parties = contexts.stream()
                .map(RecoverContext::getPayload)
                .filter(Objects::nonNull)           // handle partial cache hits
                .toList();
        ThirdPartiesResult merged = new ThirdPartiesResult();
        merged.setThirdParties(parties);
        String compositeArg = contexts.stream()
                .map(ctx -> (String) ctx.getArgs().getFirst())
                .collect(Collectors.joining(","));
        return RecoverContext.<ThirdPartiesResult>builder()
                .failover(contexts.getFirst().getFailover())
                .args(List.of(compositeArg))
                .clazz(ThirdPartiesResult.class)
                .cause(contexts.getFirst().getCause())
                .payload(merged)
                .build();
    }
}
```

### Scatter/Gather + Multi-Tenant + Parallel — Full Example

```yaml
failover:
  package-to-scan: com.example.app
  store:
    type: jdbc
    multitenant:
      enabled: true
      default-tenant: default
      tenants:
        acme:
          schema: acme_schema
        globex:
          schema: globex_schema
  scatter:
    parallel: true
```

```java
// TenantContextPropagator is auto-registered by FailoverStoreMultiTenantAutoConfiguration.
// MicrometerContextPropagator is auto-registered when Tracer bean present.
// MdcContextPropagator is always registered.
// All three are auto-composed into the contextPropagator bean — no extra config needed.

@Failover(name = "third-parties-failover", expiry = 24, expiryUnit = ChronoUnit.HOURS,
          payloadSplitter = "thirdPartiesSplitter")
ThirdPartiesResult fetchThirdParties(String csvIds);
```

Each tenant's request routes to its own JDBC store. Slices are dispatched in parallel on
virtual threads. Tenant ID, MDC trace keys, and Micrometer span are all propagated to each
slice thread automatically.
