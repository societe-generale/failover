# Failover Store

The failover store is the persistence layer that holds cached referential payloads.
When a primary call fails, the framework reads from the store and serves the last-known-good result.
When a primary call succeeds, the result is written to the store (synchronously or asynchronously).

---

## Store Types

| Type       | Class                   | Production? | Notes                                                         |
|------------|-------------------------|-------------|---------------------------------------------------------------|
| `inmemory` | `FailoverStoreInmemory` | **No**      | `ConcurrentHashMap`; process-local; data lost on restart      |
| `caffeine` | `FailoverStoreCaffeine` | Yes         | In-process; per-entry TTL managed by Caffeine; no persistence |
| `jdbc`     | `FailoverStoreJdbc`     | Yes         | Durable; supports all major databases; fully configurable     |
| `custom`   | *User-provided*         | Yes         | Implement `FailoverStore<T>` and register the bean            |

The default store type when none is configured is `inmemory`.

---

## FailoverStoreInmemory

A plain `ConcurrentHashMap`-backed store. Entries are evicted explicitly when `cleanByExpiry` is called by the scheduler.

> **Do not use in production.** Data is process-local and lost on application restart.

**Configuration:**

```yaml
failover:
  store:
    type: inmemory   # also the default when 'type' is absent
```

**Maven dependency** (included transitively via the starter):

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-inmemory</artifactId>
</dependency>
```

---

## FailoverStoreCaffeine

A Caffeine cache-backed store. Each entry carries its own TTL derived from `ReferentialPayload.expireOn`. Caffeine evicts expired entries automatically on a background thread — `cleanByExpiry` is a **no-op** for this store type.

> **Heap impact:** Every stored referential payload lives in the JVM heap. High-cardinality referentials with large payloads can cause memory pressure.

**Configuration:**

```yaml
failover:
  store:
    type: caffeine
```

**Maven dependency:**

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-caffeine</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

**TTL behaviour:**

- TTL per entry = `Duration.between(clock.now(), payload.expireOn)` at write time.
- Re-writing the same key resets the TTL to the new `expireOn` value.
- Reading an entry does **not** reset its TTL.
- `cleanByExpiry` is called by the scheduler but does nothing — Caffeine handles eviction internally.

---

## FailoverStoreJdbc

A JDBC-backed store that persists referential payloads in a relational database table.
Suitable for production workloads requiring durability and shared state across application instances.

**Supported databases:** H2, PostgreSQL, MySQL, MariaDB, Oracle. Other databases fall back to an INSERT + UPDATE-on-duplicate-key pattern.

**Configuration:**

```yaml
failover:
  store:
    type: jdbc
```

**Maven dependency:**

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-jdbc</artifactId>
</dependency>
<!-- Jackson (or tools.jackson) for payload serialization -->
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

You must also provide a `DataSource` and a `JdbcTemplate` bean (e.g. via `spring-boot-starter-jdbc`).

### Table Schema

Create the failover store table before starting the application:

```sql
-- Default table name: FAILOVER_STORE
-- With table-prefix=DEMO_: DEMO_FAILOVER_STORE
CREATE TABLE FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    PAYLOAD        VARCHAR(2000),          -- increase size as needed; see PayloadColumnResolver
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

### Table Prefix

Isolate the failover table from other application tables using a prefix:

```yaml
failover:
  store:
    type: jdbc
    jdbc:
      table-prefix: DEMO_    # table name becomes DEMO_FAILOVER_STORE
```

Create the corresponding table:

```sql
CREATE TABLE DEMO_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    PAYLOAD        VARCHAR(2000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

### Native Merge / Upsert

`FailoverStoreJdbc` uses a single native merge/upsert statement when the database supports it:

| Database        | SQL dialect                                 |
|-----------------|---------------------------------------------|
| H2              | `MERGE INTO … KEY … VALUES …`               |
| PostgreSQL      | `INSERT … ON CONFLICT … DO UPDATE SET …`    |
| MySQL / MariaDB | `INSERT … ON DUPLICATE KEY UPDATE …`        |
| Oracle          | `MERGE INTO … USING DUAL …`                 |
| Other           | INSERT; on `DuplicateKeyException` → UPDATE |

The dialect is detected once at startup via `DatabaseResolver`. If detection fails or returns `null`, the INSERT + UPDATE fallback is used permanently.

### Payload Column Customisation

By default the `PAYLOAD` column is `VARCHAR`. For large payloads declare a custom `PayloadColumnResolver` bean:

```java
@Configuration
public class FailoverPayloadConfig {

    // Switch PAYLOAD column to CLOB for large payloads
    @Bean
    public PayloadColumnResolver payloadColumnResolver() {
        return new PayloadColumnResolver() {
            @Override
            public int payloadType() { return Types.CLOB; }

            @Override
            public String extractPayload(ResultSet rs, String column) throws SQLException {
                Clob clob = rs.getClob(column);
                return clob != null ? clob.getSubString(1, (int) clob.length()) : null;
            }
        };
    }
}
```

`@ConditionalOnMissingBean` on the autoconfigured `VarcharPayloadColumnResolver` means your bean takes precedence automatically.

### DatabaseResolver Customisation

Override `DatabaseResolver` to hard-code a dialect or add observability:

```java
@Bean
public DatabaseResolver databaseResolver() {
    return () -> "PostgreSQL";   // hard-code dialect; skip metadata round-trip
}
```

### FailoverStoreQueryResolver Customisation

Replace the entire SQL layer when you need a different schema, custom column names, or non-standard serialization:

```java
@Bean
public FailoverStoreQueryResolver failoverStoreQueryResolver(
        Serializer serializer,
        DatabaseResolver databaseResolver,
        PayloadColumnResolver payloadColumnResolver) {
    return new DefaultFailoverStoreQueryResolver(
            "MY_PREFIX_", serializer, databaseResolver, payloadColumnResolver);
}
```

Or implement the interface from scratch for full control.

---

## Async Mode

By default all write operations (`store`, `delete`, `cleanByExpiry`) are offloaded to a background `TaskExecutor` so the calling thread is not blocked. `find` is always synchronous.

```yaml
failover:
  store:
    async: true    # default — writes are non-blocking
```

To disable async (synchronous writes on the calling thread):

```yaml
failover:
  store:
    async: false
```

**Custom executor:**

The autoconfiguration registers a `SimpleAsyncTaskExecutor` (virtual-threads on JDK 21+) named `failoverTaskExecutor`. Override it with your own:

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

> **SCHEMA multitenant + async:** When using the SCHEMA isolation strategy with an `AbstractRoutingDataSource`, the routing key (`TenantContext.get()`) is read from a `ThreadLocal` that is **not** propagated to executor threads. This causes all async writes to route to the default datasource. Set `async: false` or propagate `TenantContext` via a `TaskDecorator`.

---

## Custom Store

Implement `FailoverStore<T>` and register it as a Spring bean:

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
        // Wrap in FailoverStoreAsync if you want non-blocking writes
        return new FailoverStoreAsync<>(new MyCustomStore(), myExecutor());
    }
}
```

Set `failover.store.type=custom` to signal intent (no functional effect — the bean presence controls behaviour):

```yaml
failover:
  store:
    type: custom
```

---

## Multi-Tenant Store

Multi-tenant mode creates an isolated `FailoverStore` instance per tenant. No data ever crosses tenant boundaries.

### Enabling Multi-Tenant Mode

```yaml
failover:
  store:
    multitenant:
      enabled: true
```

When enabled the autoconfiguration assembles a `MultiTenantFailoverStore` that:
1. Resolves the current tenant on the calling thread using a `TenantResolver` bean.
2. Routes every operation to the correct per-tenant store.
3. Iterates all pre-warmed tenant stores when `cleanByExpiry` is called (no `TenantContext` needed).

### TenantResolver

The application **must** provide a `TenantResolver` bean. The library does **not** supply built-in resolver implementations — tenant resolution is always an application concern. Multi-tenant applications already have infrastructure for this (request filters, security context, etc.); the resolver is the bridge between that infrastructure and the failover store.

```java
@FunctionalInterface
public interface TenantResolver {
    @Nullable String resolve();
}
```

`resolve()` is always called on the **calling (request) thread**, before any async dispatch. Implementations may safely read `ThreadLocal` values and HTTP context — they do not need to be thread-safe beyond normal servlet-model assumptions.

#### Pattern 1 — HTTP Request Header via `TenantContext`

The most common approach: a servlet filter reads a header at the start of each request, stores it in `TenantContext` (a `ThreadLocal` provided by the library), and a resolver reads from it.

**Step 1 — Filter (application code):**

```java
import com.societegenerale.failover.store.multitenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the X-Tenant-ID header and stores it in TenantContext for the duration of the request.
 * Application-provided — not part of the failover library.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = request.getHeader(TENANT_HEADER);
        TenantContext.set(tenantId);   // null if header absent — falls back to default-tenant
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();     // must clear so the thread is clean when returned to pool
        }
    }
}
```

**Step 2 — Resolver bean (application code):**

```java
import com.societegenerale.failover.store.multitenant.TenantContextTenantResolver;
import com.societegenerale.failover.store.multitenant.TenantResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FailoverTenantConfig {

    /**
     * Reads TenantContext.get() — set by TenantContextFilter on the calling thread.
     * TenantContextTenantResolver is a built-in helper provided by the failover library.
     */
    @Bean
    public TenantResolver tenantResolver() {
        return new TenantContextTenantResolver();
    }
}
```

> **Maven dependency** for `TenantContextTenantResolver`: included via `failover-store-multitenant` (transitive from the starter).

#### Pattern 2 — Spring Security Principal

If the tenant identifier is the authenticated principal name (common in OAuth2 / JWT setups where the `sub` claim is the tenant), read it from `SecurityContextHolder`:

**Resolver (application code):**

```java
import com.societegenerale.failover.store.multitenant.TenantResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
public class FailoverTenantConfig {

    @Bean
    public TenantResolver tenantResolver() {
        return new SecurityContextTenantResolver();
    }

    /**
     * Resolves the current tenant from the Spring Security authentication principal.
     * Application-provided — not part of the failover library.
     */
    static class SecurityContextTenantResolver implements TenantResolver {

        @Override
        public String resolve() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return null;   // falls back to default-tenant or throws FailoverStoreException
            }
            return auth.getName();   // principal name — adapt to your token claim as needed
        }
    }
}
```

> **Maven dependency**: `spring-boot-starter-security` (already present in security-enabled applications).

For JWT-based tenants where the tenant ID is a custom claim (e.g. `tenant_id`), adapt `resolve()` to cast `auth.getPrincipal()` to `Jwt` and read the claim:

```java
@Override
public String resolve() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) return null;
    if (auth.getPrincipal() instanceof Jwt jwt) {
        return jwt.getClaimAsString("tenant_id");
    }
    return auth.getName();
}
```

#### Pattern 3 — Custom Header Resolver (no TenantContext)

If you prefer not to use `TenantContext` as an intermediary (e.g. in reactive or non-servlet stacks), inject the `HttpServletRequest` directly:

```java
import com.societegenerale.failover.store.multitenant.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FailoverTenantConfig {

    @Bean
    public TenantResolver tenantResolver() {
        return new HeaderTenantResolver("X-Tenant-ID");
    }

    /**
     * Reads a named HTTP header from the current request via RequestContextHolder.
     * Application-provided — not part of the failover library.
     */
    static class HeaderTenantResolver implements TenantResolver {

        private final String headerName;

        HeaderTenantResolver(String headerName) {
            this.headerName = headerName;
        }

        @Override
        public String resolve() {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest request = sra.getRequest();
                return request.getHeader(headerName);
            }
            return null;   // non-request thread (e.g. scheduler) — falls back to default-tenant
        }
    }
}
```

> **Note:** `RequestContextHolder` only works in servlet-request threads. Scheduler threads return `null` from `getRequestAttributes()`, so `resolve()` returns `null` and the `default-tenant` fallback is used.

#### Pattern 4 — Fixed Tenant (testing / single-tenant migration)

`FixedTenantResolver` always returns the same literal. Useful when writing tests or when migrating a single-tenant app to multi-tenant without changing all call sites yet:

```java
import com.societegenerale.failover.store.multitenant.FixedTenantResolver;
import com.societegenerale.failover.store.multitenant.TenantResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FailoverTenantConfig {

    @Bean
    public TenantResolver tenantResolver() {
        return new FixedTenantResolver("acme");   // always routes to "acme"
    }
}
```

### TenantContext

`TenantContext` is a `ThreadLocal<String>` holder provided by the library. It is a convenience utility — you are not required to use it. If you read the tenant directly from `SecurityContextHolder`, `RequestContextHolder`, or any other mechanism, `TenantContext` is not involved.

```java
// Set at start of request
TenantContext.set("acme");

// Read during request processing (by TenantContextTenantResolver)
String tenantId = TenantContext.get();   // returns "acme"

// Clear in finally block — critical to avoid leaking the tenant ID to the next request
TenantContext.clear();
```

**When to use `TenantContext`:**
- Your application resolves the tenant from a source not available later in the call stack (e.g. a gateway-injected header that is stripped before reaching service code).
- You are using the SCHEMA JDBC isolation strategy where `TenantContext.get()` must also drive `AbstractRoutingDataSource.determineCurrentLookupKey()` — using the same `ThreadLocal` means the filter sets it once and both the failover store router and the datasource router read from the same place.

**When NOT to use `TenantContext`:**
- Spring Security is your source of truth — read from `SecurityContextHolder` directly.
- You are in a reactive (WebFlux) application — `ThreadLocal` does not propagate across reactive operators; use Reactor's context instead.

### Default Tenant Fallback

When `TenantResolver.resolve()` returns `null`, the store falls back to `default-tenant`:

```yaml
failover:
  store:
    multitenant:
      enabled: true
      default-tenant: acme
```

If both the resolver returns `null` and `default-tenant` is not set, a `FailoverStoreException` is thrown.

### Declaring Tenants

Declare tenant IDs under `multitenant.tenants`. This drives `prewarm` — the store initialises all listed tenants at startup so `cleanByExpiry` covers them from the first scheduler run, not just after the first request for each tenant.

```yaml
failover:
  store:
    multitenant:
      enabled: true
      tenants:
        acme:           # tenant ID
          table-prefix: ACME_     # JDBC TABLE_PREFIX strategy only; ignored by Caffeine / InMemory
        globex:
          table-prefix: GLOBEX_
```

### Multi-Tenant — InMemory

Each tenant gets its own `ConcurrentHashMap` instance. No cross-tenant reads are possible.

```yaml
failover:
  store:
    type: inmemory    # or omit — inmemory is the default
    async: false
    multitenant:
      enabled: true
      default-tenant: acme
      tenants:
        acme:
        globex:
```

`cleanByExpiry` evicts expired entries from every tenant's map — no `TenantContext` is required for cleanup.

### Multi-Tenant — Caffeine

Each tenant gets its own Caffeine `Cache` instance with independent per-entry TTL.

```yaml
failover:
  store:
    type: caffeine
    async: false
    multitenant:
      enabled: true
      default-tenant: acme
      tenants:
        acme:
        globex:
```

`cleanByExpiry` is a **no-op** per tenant — Caffeine handles eviction internally. Calling it on the `MultiTenantFailoverStore` is safe and never throws.

### Multi-Tenant — JDBC: TABLE_PREFIX Strategy

Each tenant stores data in its own table within a shared database. The table name is composed as:

```
effectivePrefix = tenantPrefix + globalPrefix
tableName       = effectivePrefix + "FAILOVER_STORE"
```

| `jdbc.table-prefix` | `tenants.<id>.table-prefix` | Effective table            |
|---------------------|-----------------------------|----------------------------|
| `DEMO_`             | `ACME_`                     | `ACME_DEMO_FAILOVER_STORE` |
| `DEMO_`             | *(blank)*                   | `DEMO_FAILOVER_STORE`      |
| *(blank)*           | `ACME_`                     | `ACME_FAILOVER_STORE`      |
| *(blank)*           | *(blank)*                   | `FAILOVER_STORE`           |

**Configuration:**

```yaml
failover:
  store:
    type: jdbc
    async: false
    jdbc:
      table-prefix: DEMO_               # global prefix
    multitenant:
      enabled: true
      strategy: table-prefix
      default-tenant: acme
      tenants:
        acme:
          table-prefix: ACME_           # effective table: ACME_DEMO_FAILOVER_STORE
        globex:
          table-prefix: GLOBEX_         # effective table: GLOBEX_DEMO_FAILOVER_STORE
```

**Create one table per tenant:**

```sql
CREATE TABLE ACME_DEMO_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    PAYLOAD        VARCHAR(2000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);

CREATE TABLE GLOBEX_DEMO_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    PAYLOAD        VARCHAR(2000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

`cleanByExpiry` issues a `DELETE WHERE EXPIRE_ON < ?` against each tenant's table without requiring `TenantContext` — routing is by table name, not by datasource.

### Multi-Tenant — JDBC: SCHEMA Strategy

Each tenant routes to a separate schema or physical database via Spring's `AbstractRoutingDataSource`. All tenants use the same table name (e.g. `DEMO_FAILOVER_STORE`) — isolation is at the datasource level.

The library has no schema-aware code. It uses a single `JdbcTemplate` backed by your routing datasource; routing is transparent.

**Configuration:**

```yaml
failover:
  store:
    type: jdbc
    async: false          # required — see async note below
    jdbc:
      table-prefix: DEMO_
    multitenant:
      enabled: true
      strategy: schema
      default-tenant: acme
      tenants:
        acme:
          schema: acme_schema      # informational; routing is the application's responsibility
        globex:
          schema: globex_schema
```

**Wiring steps:**

**Step 1 — Populate `TenantContext` in a filter:**

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

**Step 2 — Declare `TenantResolver` bean:**

```java
@Bean
public TenantResolver tenantResolver() {
    return new TenantContextTenantResolver();   // reads TenantContext.get()
}
```

**Step 3 — Declare `AbstractRoutingDataSource` as primary `DataSource`:**

```java
@Bean
@Primary
public DataSource routingDataSource(DataSource acmeDataSource, DataSource globexDataSource) {
    AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
        @Override
        protected Object determineCurrentLookupKey() {
            return TenantContext.get();   // same ThreadLocal — routes to correct schema/DB
        }
    };
    routing.setTargetDataSources(Map.of("acme", acmeDataSource, "globex", globexDataSource));
    routing.setDefaultTargetDataSource(acmeDataSource);
    routing.afterPropertiesSet();
    return routing;
}
```

Spring Boot's `JdbcTemplate` auto-config picks up the `@Primary DataSource`. The library's `FailoverStoreJdbc` uses that `JdbcTemplate` without any schema-awareness.

**Create the same table in every schema/database:**

```sql
-- Run in acme_schema and globex_schema (or each separate physical database)
CREATE TABLE DEMO_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
    PAYLOAD        VARCHAR(2000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

**`cleanByExpiry` with SCHEMA strategy:**

`MultiTenantFailoverStore.cleanByExpiry` iterates all pre-warmed tenant stores. Each store calls `DELETE WHERE EXPIRE_ON < ?` via the shared `JdbcTemplate`. Without `TenantContext`, the routing datasource falls back to its default target — only the default tenant's schema is cleaned.

Set context per tenant in your scheduler or wrap the `ExpiryCleanupScheduler`:

```java
@Component
public class MultiTenantExpiryScheduler {

    private final FailoverStore<Object> failoverStore;
    private final List<String> tenants = List.of("acme", "globex");

    @Scheduled(cron = "0 0 * * * *")   // hourly
    public void cleanExpired() {
        for (String tenant : tenants) {
            TenantContext.set(tenant);
            try {
                failoverStore.cleanByExpiry(LocalDateTime.now());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
```

> **`async: false` is required for SCHEMA strategy.** With `async: true`, write operations execute on the `failoverTaskExecutor` thread which has no `TenantContext`. All writes route to the default datasource. Use `async: false`, or propagate `TenantContext` to executor threads via a `TaskDecorator`.

---

## Decorator Chain

Every store bean goes through a standard decorator chain before being registered as the single `FailoverStore<Object>` bean.
The exact chain depends on whether async mode and multi-tenant mode are enabled.

### Single-Tenant (default)

**`async=true` (default):**
```
FailoverStoreAsync              ← offloads writes to a TaskExecutor; find is synchronous
  └─ DefaultFailoverStore       ← forces upToDate=false on every read and write
       └─ <raw store>           ← FailoverStoreInmemory / FailoverStoreCaffeine / FailoverStoreJdbc / custom
```

**`async=false`:**
```
DefaultFailoverStore            ← forces upToDate=false on every read and write
  └─ <raw store>                ← FailoverStoreInmemory / FailoverStoreCaffeine / FailoverStoreJdbc / custom
```

### Multi-Tenant (`failover.store.multitenant.enabled=true`)

`MultiTenantFailoverStore` is always the outermost bean. It routes each call to the correct per-tenant decorated store. The inner chain is replicated independently for each tenant.

**`async=true` (default):**
```
MultiTenantFailoverStore        ← routes each call to the correct tenant store
  ├─ tenant-a → FailoverStoreAsync → DefaultFailoverStore → rawStore-a
  └─ tenant-b → FailoverStoreAsync → DefaultFailoverStore → rawStore-b
```

**`async=false`:**
```
MultiTenantFailoverStore        ← routes each call to the correct tenant store
  ├─ tenant-a → DefaultFailoverStore → rawStore-a
  └─ tenant-b → DefaultFailoverStore → rawStore-b
```

### Summary

| `multitenant.enabled` | `async` | Assembled chain (outermost → innermost)                                                   |
|-----------------------|---------|-------------------------------------------------------------------------------------------|
| `false` (default)     | `true`  | `FailoverStoreAsync → DefaultFailoverStore → raw`                                         |
| `false` (default)     | `false` | `DefaultFailoverStore → raw`                                                              |
| `true`                | `true`  | `MultiTenantFailoverStore → (per tenant) FailoverStoreAsync → DefaultFailoverStore → raw` |
| `true`                | `false` | `MultiTenantFailoverStore → (per tenant) DefaultFailoverStore → raw`                      |

---

## Configuration Reference

```yaml
failover:
  store:
    # ── Store type ────────────────────────────────────────────────────────────
    type: inmemory          # inmemory (default) | caffeine | jdbc | custom

    # ── Async mode ───────────────────────────────────────────────────────────
    async: true             # true (default) — writes offloaded to TaskExecutor
                            # false — synchronous writes on calling thread
                            # Required false for JDBC SCHEMA multitenant strategy

    # ── JDBC options (type: jdbc only) ───────────────────────────────────────
    jdbc:
      table-prefix: ""      # prefix for FAILOVER_STORE table name
                            # e.g. "DEMO_" → table DEMO_FAILOVER_STORE

    # ── Multi-tenant options ──────────────────────────────────────────────────
    multitenant:
      enabled: false        # false (default) — single-tenant mode
                            # true — enables MultiTenantFailoverStore

      strategy: table-prefix  # table-prefix (default) | schema
                              # JDBC only; ignored by Caffeine and InMemory

      default-tenant:       # fallback tenant when TenantResolver returns null
                            # leave blank to throw FailoverStoreException instead

      tenants:              # declares known tenants; drives prewarm at startup
        <tenant-id>:
          table-prefix: ""  # TABLE_PREFIX strategy: prepended to jdbc.table-prefix
                            # effective table = tenantPrefix + globalPrefix + FAILOVER_STORE
          schema: ""        # SCHEMA strategy: informational; routing is application's responsibility
```

| Property                                               | Default        | Description                                                    |
|--------------------------------------------------------|----------------|----------------------------------------------------------------|
| `failover.store.type`                                  | `inmemory`     | Store implementation: `inmemory`, `caffeine`, `jdbc`, `custom` |
| `failover.store.async`                                 | `true`         | Offload writes to `TaskExecutor`; `false` for synchronous mode |
| `failover.store.jdbc.table-prefix`                     | `""` (empty)   | Global prefix for the JDBC table name                          |
| `failover.store.multitenant.enabled`                   | `false`        | Opt-in to multi-tenant mode                                    |
| `failover.store.multitenant.strategy`                  | `table-prefix` | JDBC isolation strategy: `table-prefix` or `schema`            |
| `failover.store.multitenant.default-tenant`            | *(none)*       | Fallback when resolver returns `null`                          |
| `failover.store.multitenant.tenants.<id>.table-prefix` | `""`           | Per-tenant prefix (TABLE_PREFIX strategy)                      |
| `failover.store.multitenant.tenants.<id>.schema`       | *(none)*       | Per-tenant schema name (SCHEMA strategy, informational)        |

---

## Extension Points

All extension points are guarded by `@ConditionalOnMissingBean` — declaring your own bean in any `@Configuration` class is sufficient to replace the default.

| Extension Point       | Interface / Class                       | How to Override                                            |
|-----------------------|-----------------------------------------|------------------------------------------------------------|
| Raw store             | `FailoverStore<T>`                      | Declare `@Bean FailoverStore<Object>`                      |
| Store factory         | `TenantStoreFactory<T>`                 | Declare `@Bean TenantStoreFactory<Object>`                 |
| Tenant resolver       | `TenantResolver`                        | Declare `@Bean TenantResolver` (required when multitenant) |
| Async executor        | `TaskExecutor`                          | Declare `@Bean("failoverTaskExecutor") TaskExecutor`       |
| Payload column type   | `PayloadColumnResolver`                 | Declare `@Bean PayloadColumnResolver`                      |
| Database detection    | `DatabaseResolver`                      | Declare `@Bean DatabaseResolver`                           |
| Full SQL layer        | `FailoverStoreQueryResolver`            | Declare `@Bean FailoverStoreQueryResolver`                 |
| Payload serialization | `Serializer`                            | Declare `@Bean Serializer`                                 |
| Row mapping           | `RowMapper<ReferentialPayload<Object>>` | Declare `@Bean RowMapper`                                  |

---
