---
icon: material/shield-alert-outline
---

# Exception Policy

`MethodExceptionPolicy` controls what happens after the primary method throws **and** failover recovery has been attempted. It decides whether the caller sees the recovered value, `null`, or the original exception.

---

## Built-in Policies

| Policy | Property value | Behaviour |
|---|---|---|
| `RethrowIfNoRecoveryMethodExceptionPolicy` | `rethrow` *(default)* | Returns recovered result when available; rethrows the original exception when nothing was recovered |
| `NeverRethrowMethodExceptionPolicy` | `never_throw` | Always returns recovered result or `null`; swallows the exception completely |
| Custom | `custom` | Your `MethodExceptionPolicy` bean |

---

## Choosing a Policy

```yaml
failover:
  exception-policy: rethrow       # default — best-effort: serve stale data, honest about total failures
  # exception-policy: never_throw # silent — always suppress; callers must null-check
  # exception-policy: custom      # plug in your own MethodExceptionPolicy bean
```

### `rethrow` (default)

```
upstream throws
  → recovery attempted
  → recovered payload found  → return payload to caller  ✅
  → no payload / expired     → rethrow original exception ❌
```

Use when callers should know a total failure occurred and can act on it (circuit breaker, fallback UI, error page).

### `never_throw`

```
upstream throws
  → recovery attempted
  → recovered payload found  → return payload to caller  ✅
  → no payload / expired     → return null to caller      ⚠️
```

Use when the caller already handles `null` gracefully and you never want an exception to surface (e.g. batch jobs, optional enrichment calls).

---

## Custom Policy

Implement `MethodExceptionPolicy` when the built-in options don't fit. Common reasons:

- Wrap the original exception in a domain-specific type.
- Trigger an alert or circuit-breaker on total failure.
- Return a domain-specific default instead of re-throwing.

### Example — Wrap and Rethrow as Domain Exception

```java
@Component
public class DomainExceptionPolicy implements MethodExceptionPolicy {

    @Override
    public <T> T handle(MethodExceptionContext<T> context) {
        if (context.recoveredResult() != null) {
            return context.recoveredResult();               // (1) stale data available — serve it
        }

        // (2) total failure — convert to domain exception
        throw new ReferentialUnavailableException(
                "Failover [" + context.failover().name() + "] could not recover. " +
                "Method: " + context.method().getName() + ", " +
                "Args: " + context.args(),
                context.cause()
        );
    }
}
```

1. Always check recovered result first — only construct the domain exception when the store also has nothing.
2. `MethodExceptionContext` gives you the full call context: annotation, method, args, recovered result, and original cause.

### Example — Alert on Total Failure, Return Null

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertingExceptionPolicy implements MethodExceptionPolicy {

    private final AlertService alertService;

    @Override
    public <T> T handle(MethodExceptionContext<T> context) {
        if (context.recoveredResult() != null) {
            return context.recoveredResult();
        }

        log.error("Total failover failure [{}]. No stored entry available. Cause: {}",
                context.failover().name(), context.cause().getMessage());

        alertService.sendAlert(
                "Failover miss",
                context.failover().name(),
                context.cause()
        );

        return null;    // caller gets null — must handle it
    }
}
```

---

## MethodExceptionContext Fields

```java
public record MethodExceptionContext<T>(
    Failover      failover,         // @Failover annotation from the intercepted method
    Method        method,           // the intercepted method itself
    List<Object>  args,             // arguments passed at the call site
    T             recoveredResult,  // recovered value — null on store miss or expiry
    Throwable     cause             // original exception from the primary call
) {}
```

---

## Registration

For `custom` policy: declare any single `MethodExceptionPolicy` bean. The auto-configuration is `@ConditionalOnMissingBean`:

```yaml
failover:
  exception-policy: custom
```

```java
@Component   // picked up automatically
public class DomainExceptionPolicy implements MethodExceptionPolicy {
    // ...
}
```

For `rethrow` or `never_throw`: no bean needed — the framework instantiates the built-in policy.

---

## Execution Order

```
upstream throws
    → RecoveredPayloadHandler.handle(...)   ← returns the recovered value (or null)
        → MethodExceptionPolicy.handle(context)
            ← what this returns (or throws) reaches the caller
```

`MethodExceptionPolicy` is the final gate. The recovered result it receives is already the output of `RecoveredPayloadHandler`.

---

## Testing

```java
class DomainExceptionPolicyTest {

    private final DomainExceptionPolicy policy = new DomainExceptionPolicy();

    @Test
    void returns_recovered_result_when_available() {
        Country country = new Country("FR", "France");
        MethodExceptionContext<Country> ctx = new MethodExceptionContext<>(
                mock(Failover.class), mock(Method.class),
                List.of("FR"), country,
                new RuntimeException("upstream down")
        );

        Country result = policy.handle(ctx);

        assertThat(result).isSameAs(country);
    }

    @Test
    void throws_domain_exception_when_no_recovery() {
        MethodExceptionContext<Country> ctx = new MethodExceptionContext<>(
                mock(Failover.class), mock(Method.class),
                List.of("FR"), null,
                new RuntimeException("upstream down")
        );

        assertThatThrownBy(() -> policy.handle(ctx))
                .isInstanceOf(ReferentialUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
```

---

## See Also

- [Recovered Payload Handler](recovered-payload-handler.md) — post-processes the recovered payload before the exception policy sees it
