---
icon: material/frequently-asked-questions
---

# Frequently Asked Questions

---

## Getting Started

### What does Failover actually do?

Failover intercepts any Spring-managed method annotated with `@Failover`. On a **successful** call it serialises the result to a backing store. On a **failed** call (any exception) it looks up the last stored result for the same input arguments and returns it instead of propagating the exception.

The caller sees a stale-but-valid result rather than an error. If no stored result exists the exception is re-thrown (default) or `null` is returned, depending on the configured exception policy.

---

### Do I need to change my domain model?

No. Failover works on any return type. Extending `Referential` or implementing `ReferentialAware` is **optional** — it adds `upToDate`, `asOf`, and `metadata` fields to recovered objects so callers can tell live data from stale data. Without either, failover still stores and recovers correctly.

---

### Which store should I use in production?

Use **JDBC**. It persists across restarts, works with multiple application instances, and is the only store that supports schema-level multi-tenancy. `INMEMORY` and `CAFFEINE` lose data on restart and should be used only in local development or tests.

---

### Does Failover work with Feign clients, `@Service`, `@RestTemplate` wrappers, and similar beans?

Yes — anything managed by the Spring container and proxied by AOP. This includes Feign clients, `@Service`, `@Component`, `@Repository`, Spring Data repositories, and `@RestController`. The annotated method must be invoked through the Spring proxy (not via `this.method()` within the same class) for the aspect to intercept it.

`@Failover` works with **Spring beans only**. Methods on plain Java objects not registered in the Spring context are invisible to both the aspect and the scanner.

---

### Can I disable Failover without removing the annotations?

Yes:

```yaml
failover:
  enabled: false
```

With `enabled=false` the framework is fully bypassed. All `@Failover`-annotated methods execute normally without any interception, storing, or recovering.

---

## The `@Failover` Annotation

### What is the `name` attribute and why must it be unique?

`name` is the logical identifier for a failover point. It is used as the `FAILOVER_NAME` column in the store — every stored entry belongs to a named slot. If two methods share the same name, their stored entries collide (one will overwrite the other's data on recovery). Startup fails with a duplicate-name error to prevent this.

---

### What happens on the very first call when there is no stored entry?

The first call always goes to the upstream. If it succeeds the result is stored. If it fails and there is nothing in the store, the framework follows the configured exception policy:

- `RETHROW` (default) — re-throws the original exception.
- `NEVER_THROW` — returns `null` (or the `RecoveredPayloadHandler` result).

There is no way to recover without a prior successful call.

---

### Can I use SpEL expressions for expiry?

Yes. Use `expiryDurationExpression` and `expiryUnitExpression` to reference application properties or Spring beans:

```java
@Failover(
    name = "country-by-code",
    expiryDurationExpression = "${myapp.failover.country-by-code.duration}",
    expiryUnitExpression     = "${myapp.failover.country-by-code.unit}"
)
```

This avoids hard-coding TTL values in annotations and lets you adjust them per environment.

---

### Can I annotate a method that returns `void` or `Optional`?

Methods returning `void` produce nothing to store — annotating them has no effect.

`Optional<T>` is supported. The `Optional` wrapper itself is serialised and stored. On recovery the stored `Optional` (possibly `Optional.empty()`) is returned.

---

### Does `@Failover` interact with `@Transactional` or `@Cacheable`?

It can coexist with both but order matters. By default Spring applies AOP proxies in declaration order. If `@Failover` wraps `@Transactional`, a transaction exception will trigger recovery. If `@Transactional` wraps `@Failover`, the failover store/recover happens within the transaction.

Recommended: put `@Failover` on the outermost layer (client/adapter) and keep it away from transactional service methods unless recovery semantics across transactions are intentional.

---

## Store and Persistence

### How are payloads serialised?

Payloads are serialised to JSON using **Jackson**. The `PAYLOAD_CLASS` column stores the fully-qualified class name for deserialisation. Your domain types must be Jackson-compatible:

- Public no-arg constructor (or `@JsonCreator`)
- All fields readable (public getters or `@JsonProperty`)
- No unresolvable type references

---

### My payload is very large and VARCHAR(4000) is not enough. What should I do?

Two options:

1. **Change the DDL** — use `TEXT` (PostgreSQL, MySQL) or `CLOB` (Oracle) for the `PAYLOAD` column.
2. **Add a `PayloadColumnResolver` bean** — implement `PayloadColumnResolver` to return `Types.CLOB` from `payloadType()` and read via `getClob()`. See [Payload Column Resolver](../guides/payload-column-resolver.md).

---

### Can I use a different database for the failover store than the application database?

Yes. Declare a separate `JdbcTemplate` bean wired to the failover DataSource and provide a custom `FailoverStoreQueryResolver` or `FailoverStoreJdbc` that uses it. The JDBC store does not assume a shared DataSource.

---

### How do I create the `FAILOVER_STORE` table?

DDL for a table with prefix `MYAPP_`:

```sql
CREATE TABLE MYAPP_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP(9) WITH TIME ZONE     NOT NULL,
    EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE     NOT NULL,
    PAYLOAD        VARCHAR(4000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
);
```

Replace `MYAPP_` with your `failover.store.jdbc.table-prefix` value. For no prefix, use `FAILOVER_STORE` directly.

---

### Will the store grow without bound?

No — if the scheduler is enabled (default), an `ExpiryCleanupScheduler` runs on the `failover.scheduler.cleanup-cron` schedule (every hour by default) and deletes all rows where `EXPIRE_ON < now`. Disable it only if you manage cleanup externally:

```yaml
failover:
  scheduler:
    enabled: false
```

---

### Can I point multiple application instances at the same JDBC store?

Yes. The JDBC store uses a native upsert/merge (or INSERT + UPDATE fallback) keyed on `(FAILOVER_NAME, FAILOVER_KEY)`, so concurrent writes from multiple instances are safe. All instances share the same stored entries.

---

### What databases does the JDBC store support?

The built-in `DefaultFailoverStoreQueryResolver` provides native merge dialects for **H2**, **PostgreSQL**, **MySQL**, **MariaDB**, and **Oracle**. Any JDBC-compatible database is supported in fallback mode (separate INSERT + UPDATE). Implement a custom `DatabaseResolver` to force a specific dialect. See [Database Resolver](../guides/database-resolver.md).

---

## Key Generation

### How is the store key derived from method arguments?

The default `KeyGenerator` serialises all method arguments as a string (using their `toString()` representation joined and hashed with MD5 into a fixed-length UUID-based key). The key plus the `@Failover` name form the compound store lookup: `(name, key)`.

Override `KeyGenerator` when you need to control which arguments contribute to the key, normalise argument values, or use a different hashing strategy. See [Key Generator](../guides/custom-key-generator.md).

---

### Two methods share the same argument values but must have separate stored entries. How?

Give them different `name` values in `@Failover`. The name is always part of the compound key — two methods with different names never share entries regardless of argument overlap.

---

### I added a new method argument but old stored entries stop matching. Why?

Key derivation includes all arguments by default. Adding an argument changes the key for all future calls, so old entries (keyed without the new argument) are no longer found on recovery. Implement a custom `KeyGenerator` that excludes the new argument from the key to preserve backward compatibility with existing entries.

---

## Expiry and Cleanup

### What happens when a stored entry expires during recovery?

Expired entries are treated the same as missing entries — the framework returns `null` or re-throws depending on the exception policy. The entry is not returned even if it physically exists in the store.

---

### Can expiry be computed at runtime from the payload content?

Yes. Implement `ExpiryPolicy` and return an `Instant` based on the actual payload (e.g. use an embedded `validUntil` field from an API response). See [Expiry Policy](../guides/custom-expiry-policy.md).

---

### How do I extend the TTL without redeploying?

Use `expiryDurationExpression` with a Spring property:

```yaml
myapp:
  failover:
    country-ttl-hours: 48
```

```java
@Failover(name = "countries", expiryDurationExpression = "${myapp.failover.country-ttl-hours}", expiryUnitExpression = "HOURS")
```

Change the property and restart (or use Spring Cloud Config for dynamic refresh).

---

## Recovery Behaviour

### What exception types trigger recovery?

All `Throwable`s that propagate out of the annotated method — `RuntimeException`, checked `Exception`, and `Error` subtypes alike. There is no filtering by exception type in the default setup.

To limit recovery to specific exception types, implement a custom `FailoverExecution` bean (see `failover.type: CUSTOM`).

---

### Can I recover only on specific exceptions (e.g. only `HttpServerErrorException`, not `HttpClientErrorException`)?

Not out of the box with the default execution strategy. Implement a custom `FailoverExecution` that inspects the caught throwable and re-throws it for exception types you don't want to recover from, or use the `RESILIENCE` type with a Resilience4j circuit breaker configured for specific exception predicates.

---

### How do I know whether a caller received live or recovered data?

If your domain type extends `Referential` or implements `ReferentialAware`, the `upToDate` field is `true` for live results and `false` for recovered ones. The `asOf` field holds the timestamp of the original successful call.

```java
Country country = client.findByCode("FR");
if (!country.isUpToDate()) {
    log.warn("Serving stale data as of {}", country.getAsOf());
}
```

---

### The exception is still thrown even though data exists in the store. Why?

Most likely causes:

1. **Key mismatch** — the recovery call uses different arguments than the store call. Check that arguments at recovery time produce the same key. Enable DEBUG logging for `com.societegenerale.failover` to see the generated keys.
2. **Entry expired** — the `EXPIRE_ON` timestamp has passed. Check the stored row and your TTL configuration.
3. **Wrong `name`** — the method has the wrong `@Failover` name, so it looks up entries in a different slot.
4. **Async write race** — with `failover.store.async=true`, the store write may not have completed before the test re-invokes the method. Set `failover.store.async=false` in tests.

---

### Can I return a default/sentinel value instead of `null` on store miss?

Yes. Implement `RecoveredPayloadHandler`:

```java
@Component
public class CountryHandler implements RecoveredPayloadHandler {
    @Override
    public <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload, Throwable cause) {
        if (payload != null) return payload;
        if (clazz == Country.class) return clazz.cast(Country.unknown());
        return null;
    }
}
```

See [Recovered Payload Handler](../guides/recovered-payload-handler.md).

---

### Can I wrap or translate the exception thrown on total failure?

Yes. Implement `MethodExceptionPolicy` and register it with `failover.exception-policy: custom`:

```java
@Component
public class DomainPolicy implements MethodExceptionPolicy {
    @Override
    public <T> T handle(MethodExceptionContext<T> ctx) {
        if (ctx.recoveredResult() != null) return ctx.recoveredResult();
        throw new ServiceUnavailableException("Upstream failed and no stored entry found", ctx.cause());
    }
}
```

See [Exception Policy](../guides/exception-policy.md).

---

## Scatter / Gather

### When should I use `PayloadSplitter`?

When the annotated method returns a **collection** but you want individual entries stored per element — so partial recovery is possible. If three of five items are in the store, those three are returned even if the upstream is fully down. See [Payload Splitter](../guides/payload-splitter.md).

---

### Does scatter/gather work with async writes?

Yes, but use it carefully with `ContextPropagator` if you rely on thread-local context (security, tenant, trace). Each scatter slot dispatches in a separate virtual thread. Implement `ContextPropagator` to copy the required context into the worker threads. See [Context Propagation](../guides/context-propagation.md).

---

## Multi-Tenancy

### How do I isolate stored data between tenants?

Two strategies are available:

- **`TABLE_PREFIX`** — each tenant gets its own table (e.g. `TENANT_A_FAILOVER_STORE`, `TENANT_B_FAILOVER_STORE`). Configure `failover.store.multitenant.tenants` with a `table-prefix` per tenant.
- **`SCHEMA`** — each tenant gets its own database schema or database. Provide a `TenantStoreFactory` bean and set `failover.store.async=false` (schema switching requires synchronous thread-bound connections).

See [Multi-Tenant](../configuration/multi-tenant.md).

---

### I use the SCHEMA multi-tenant strategy and am seeing connection leaks or wrong-schema errors.

The SCHEMA strategy relies on thread-local schema switching (e.g. `SET search_path` on PostgreSQL). Async writes offload to background threads that don't carry the tenant context. Always set:

```yaml
failover:
  store:
    async: false
```

when using the SCHEMA strategy.

---

## Testing

### How do I test failover behaviour in integration tests?

Use the real JDBC store backed by H2 in-memory:

```java
@SpringBootTest
@TestPropertySource(properties = {
    "failover.store.type=jdbc",
    "failover.store.async=false",
    "failover.exception-policy=never_throw"
})
class CountryClientFailoverIT {
    @Autowired CountryClient client;
    @MockitoBean CountryServiceStub stub;

    @Test
    void recovers_stored_country_on_failure() {
        given(stub.findByCode("FR")).willReturn(new Country("FR", "France", "EUR"));
        client.findByCode("FR");    // prime the store

        given(stub.findByCode("FR")).willThrow(new RuntimeException("down"));
        Country recovered = client.findByCode("FR");

        assertThat(recovered.getCode()).isEqualTo("FR");
        assertThat(recovered.isUpToDate()).isFalse();
    }
}
```

Set `failover.store.async=false` to make writes synchronous so assertions are deterministic.

---

### Should I mock the failover store in unit tests?

Only for testing SPI implementations in isolation (e.g. testing a custom `KeyGenerator` or `ExpiryPolicy`). For end-to-end failover behaviour tests, use a real store — mocking the store doesn't exercise the serialisation, key derivation, expiry, or enrichment paths, which is where most integration issues occur.

---

### How do I verify the key that gets stored?

Enable DEBUG logging for the failover package:

```yaml
logging:
  level:
    com.societegenerale.failover: DEBUG
```

The aspect logs the generated key on every store and recover call.

---

## Production Concerns

### Is there a performance overhead on the happy path?

Yes — every successful call performs a store write (serialisation + DB insert/upsert). With `failover.store.async=true` (default) the write is offloaded to a background executor, so the calling thread is not blocked. Measure the serialisation cost for very large payloads and size the thread pool accordingly.

---

### What happens if the store itself becomes unavailable during a store write?

With async writes, a store failure on the write path is logged but does not propagate to the caller — the upstream result is still returned. On the recovery path a store failure causes the exception to be re-thrown (or `null` returned) per the exception policy.

---

### Does Failover guarantee exactly-once storage?

No. With multiple instances and async writes, concurrent store calls for the same `(name, key)` may race. The JDBC store resolves this with native upsert (last write wins by timestamp). There is no distributed lock. For most referential-data use cases this is acceptable — the stored value is idempotent.

---

### How do I monitor failover events in production?

Implement `ObservablePublisher` to receive failover metrics and forward them to your backend (Micrometer, Prometheus, Datadog, etc.). See [Observability](../guides/observability.md).

Alternatively, implement `RecoveredPayloadHandler` to increment a counter on every recovery:

```java
meterRegistry.counter("failover.recovery", "name", failover.name(), "outcome", payload != null ? "hit" : "miss").increment();
```

---

### Can I disable the expiry scheduler in production and run cleanup externally?

Yes:

```yaml
failover:
  scheduler:
    enabled: false
```

Then run your own scheduled job or database maintenance task:

```sql
DELETE FROM FAILOVER_STORE WHERE EXPIRE_ON < NOW();
```

This is useful when you need more control over cleanup timing or want to batch deletes with a cursor to avoid large lock contention.

---

### Does Failover support GraalVM native image?

Not officially tested. The Jackson-based serialisation and Spring AOP proxy generation are the main concerns. Standard Spring Boot native image support handles AOP proxies, but you may need explicit Jackson type hints for your domain classes if they use reflection-based deserialisation.

---

## Troubleshooting

### The `@Failover` annotation has no effect — the method runs normally and no store entry is created.

Check:

1. `failover.enabled` is not set to `false`.
2. The annotated method is invoked through the Spring proxy — not via a direct `this.method()` call within the same class.
3. The class is a Spring-managed bean (`@Component`, `@Service`, `@FeignClient`, etc.) — `@Failover` works with Spring beans only.
4. The return type is not `void`.

Enable DEBUG logging (`com.societegenerale.failover: DEBUG`) to confirm the aspect is intercepting calls.

---

### Startup fails with "Duplicate failover name" or similar.

Two `@Failover` annotations in the scanned package share the same `name` value. Each name must be globally unique. Search the codebase for duplicate values:

```
grep -r "@Failover" src/ | grep 'name = "your-name"'
```

---

### Jackson deserialisation fails on recovery with `InvalidDefinitionException` or `UnrecognizedPropertyException`.

Common causes:

- Domain class has no public no-arg constructor — add one or use `@JsonCreator`.
- A field was renamed or removed after the entry was stored — Jackson can't map the old JSON to the new class. Clear the store row and let it be repopulated.
- The class is in a module without Jackson visibility — annotate with `@JsonAutoDetect` or configure the `ObjectMapper`.

---

### Recovery returns `null` but I expected the stored entry.

Run through this checklist:

1. Confirm a row exists in the store table for the expected `FAILOVER_NAME` and `FAILOVER_KEY`.
2. Confirm `EXPIRE_ON` in that row is in the future.
3. Log the key generated at store time and at recovery time — they must match.
4. Check `failover.exception-policy` — `NEVER_THROW` returns `null` on miss; `RETHROW` re-throws (so a `null` result with no exception means the store returned an entry that deserialised to `null`).

---

### The JDBC store always falls back to INSERT + UPDATE and never uses native upsert.

The `DefaultDatabaseResolver` reads the database product name from JDBC metadata. If metadata access is restricted or returns an unexpected name, `DatabaseResolver.resolve()` returns `null` and the merge query is disabled. Implement a hardcoded `DatabaseResolver` bean to force the correct dialect:

```java
@Component
public class ForcedPostgresResolver implements DatabaseResolver {
    @Override public String resolve() { return "PostgreSQL"; }
}
```

See [Database Resolver](../guides/database-resolver.md).

---

### Async store writes appear to be dropped silently in tests.

Async writes run on a background `TaskExecutor`. In tests the application context may shut down before background tasks complete. Fix by setting synchronous writes:

```properties
failover.store.async=false
```

Or use `@DirtiesContext` with care and add an explicit delay (not recommended — use synchronous mode in tests instead).
