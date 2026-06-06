---
icon: material/restore-alert
---

# Recovered Payload Handler

`RecoveredPayloadHandler` is a post-processor called after every failover recovery attempt. It receives the recovered payload (or `null` when the store has no valid entry) and the original exception, and returns the final value the caller sees.

Use `RecoveredPayloadHandler` when:

- You need to return a **default / sentinel value** instead of `null` when recovery fails.
- You need to **attach error context** to the recovered object (which exception triggered recovery, from which upstream service).
- You need to **log or audit** recoveries with full call context.
- You need to **sanitise or transform** the stored payload before it reaches the caller.

---

## Interface Contract

```java
public interface RecoveredPayloadHandler {

    <T> T handle(
        Failover      failover,  // annotation from the intercepted method
        List<Object>  args,      // original method arguments
        Class<T>      clazz,     // expected return type
        T             payload,   // recovered value — null if store miss or expiry
        Throwable     cause      // original exception that triggered recovery
    );
}
```

The default implementation (`PassThroughRecoveredPayloadHandler`) returns `payload` unchanged.

---

## Use Case 1 — Return a Default Value on Cache Miss

Callers that cannot tolerate `null` receive a sentinel object instead:

```java
@Component
public class CountryRecoveredPayloadHandler implements RecoveredPayloadHandler {

    @Override
    public <T> T handle(
            Failover failover, List<Object> args,
            Class<T> clazz, T payload, Throwable cause) {

        if (payload != null) {
            return payload;                             // (1) store hit — return as-is
        }

        if (clazz == Country.class) {
            return clazz.cast(Country.unknown());       // (2) store miss — safe sentinel
        }

        return null;                                    // (3) other types — pass through null
    }
}
```

1. Always check non-null first — the handler is called on every recovery, not only on misses.
2. `Country.unknown()` is a pre-built sentinel; callers can check `country.isUnknown()` instead of null-checking.
3. Return `null` for types the handler does not own — avoids masking issues in unrelated failover points.

---

## Use Case 2 — Attach Error Context to the Recovered Object

Set recovery metadata directly on the returned object so callers know why they received stale data:

```java
@Component
public class AuditingRecoveredPayloadHandler implements RecoveredPayloadHandler {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T handle(
            Failover failover, List<Object> args,
            Class<T> clazz, T payload, Throwable cause) {

        if (payload instanceof RecoveryAware recoveryAware) {    // (1)
            recoveryAware.setRecoveredFrom(failover.name());
            recoveryAware.setRecoveryCause(cause.getClass().getSimpleName());
            recoveryAware.setRecoveryArgs(args.toString());
        }

        return payload;
    }
}
```

1. Guard with `instanceof` — only enrich types that implement your custom `RecoveryAware` interface. Other domain types are returned unchanged.

The `RecoveryAware` interface:

```java
public interface RecoveryAware {
    void setRecoveredFrom(String failoverName);
    void setRecoveryCause(String causeType);
    void setRecoveryArgs(String args);
}
```

---

## Use Case 3 — Log Recoveries with Full Context

Track every failover event in your monitoring system without modifying domain types:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class LoggingRecoveredPayloadHandler implements RecoveredPayloadHandler {

    private final MeterRegistry meterRegistry;

    @Override
    public <T> T handle(
            Failover failover, List<Object> args,
            Class<T> clazz, T payload, Throwable cause) {

        String outcome = payload != null ? "recovered" : "miss";

        log.warn("Failover [{}] — {} | args={} | cause={}",
                failover.name(), outcome, args, cause.getMessage());

        meterRegistry.counter("failover.recovery",
                        "name",    failover.name(),
                        "outcome", outcome)
                .increment();

        return payload;
    }
}
```

---

## Registration

Declare as a Spring `@Component` or `@Bean`. The auto-configuration is `@ConditionalOnMissingBean`, so any single bean implementing `RecoveredPayloadHandler` in the application context automatically replaces the default pass-through handler.

```java
@Component   // or @Bean in a @Configuration class
public class MyRecoveredPayloadHandler implements RecoveredPayloadHandler {
    // ...
}
```

If you need to delegate to the default handler, inject `PassThroughRecoveredPayloadHandler` explicitly:

```java
@Component
@RequiredArgsConstructor
public class MyRecoveredPayloadHandler implements RecoveredPayloadHandler {

    private final PassThroughRecoveredPayloadHandler defaultHandler;

    @Override
    public <T> T handle(
            Failover failover, List<Object> args,
            Class<T> clazz, T payload, Throwable cause) {
        // your logic here, then delegate
        return defaultHandler.handle(failover, args, clazz, payload, cause);
    }
}
```

---

## Execution Order

```
upstream throws
    → FailoverAspect attempts recovery from store
        → RecoveredPayloadHandler.handle(failover, args, clazz, payload, cause)
            → MethodExceptionPolicy.handle(context)   ← next step in the chain
```

`RecoveredPayloadHandler` runs **before** `MethodExceptionPolicy`. The value it returns is what `MethodExceptionPolicy` sees as `recoveredResult`.

---

## Testing

```java
class CountryRecoveredPayloadHandlerTest {

    private final CountryRecoveredPayloadHandler handler =
            new CountryRecoveredPayloadHandler();
    private final Failover failover = mock(Failover.class);
    private final Throwable cause   = new RuntimeException("upstream down");

    @Test
    void returns_recovered_payload_when_store_hit() {
        Country country = new Country("FR", "France");
        Country result  = handler.handle(failover, List.of("FR"), Country.class, country, cause);

        assertThat(result).isSameAs(country);
    }

    @Test
    void returns_sentinel_when_store_miss() {
        Country result = handler.handle(failover, List.of("FR"), Country.class, null, cause);

        assertThat(result).isNotNull();
        assertThat(result.isUnknown()).isTrue();
    }

    @Test
    void returns_null_for_unrelated_types_on_store_miss() {
        Object result = handler.handle(failover, List.of("x"), Object.class, null, cause);

        assertThat(result).isNull();
    }
}
```

---

## See Also

- [Exception Policy](exception-policy.md) — controls rethrow behaviour after `RecoveredPayloadHandler` returns
- [Custom Payload Enricher](custom-payload-enricher.md) — enriches payloads at store/recover time (before `RecoveredPayloadHandler`)
