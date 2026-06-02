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

The autoconfiguration registers a `SimpleAsyncTaskExecutor` (virtual threads on JDK 21+) named `failoverTaskExecutor`. Override it with your own (if required) by defining a `TaskExecutor` bean named `failoverTaskExecutor`.:

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
    AS_OF          TIMESTAMP(9)  NOT NULL,
    EXPIRE_ON      TIMESTAMP(9)  NOT NULL,
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
