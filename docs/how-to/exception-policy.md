---
icon: material/alert-octagon-outline
---

# Exception Policy

Controls what happens when recovery finds no valid stored entry — re-throw the original exception, return `null`, or run your own logic.

---

## Three Strategies

| Strategy | Behaviour | Config value |
|---|---|---|
| `RETHROW` | Re-throws the original upstream exception | `rethrow` (default) |
| `NEVER_THROW` | Returns `null` (or `RecoveredPayloadHandler` result) | `never_throw` |
| `CUSTOM` | Your own `MethodExceptionPolicy` bean | `custom` |

---

## RETHROW (Default)

The upstream exception propagates to the caller when no stored entry is found or all entries are expired.

```yaml title="application.yml"
failover:
  exception-policy: rethrow
```

Use RETHROW when callers must know that the upstream is unavailable and cannot gracefully handle a `null` response.

---

## NEVER_THROW

Returns `null` (or the value from `RecoveredPayloadHandler`) instead of propagating the exception. The caller receives `null` on no recovery.

```yaml title="application.yml"
failover:
  exception-policy: never_throw
```

!!! warning "`never_throw` masks outages — alert on metrics"
    Because the upstream exception is suppressed, the caller cannot tell an outage occurred from the
    return value. The failover **metrics still fire regardless of policy**, so they are the signal to
    monitor. Alert on:

    - `failover.recovery.outcome.total{outcome="not_recovered"}` — upstream failed and nothing could be recovered, and
    - `failover.user.impact.total{impact="blocked"}` — the user got no value.

    The framework also logs a `WARN` at startup when `never_throw` is active. See
    [Observability](observability.md).

!!! tip "Combine with RecoveredPayloadHandler"
    Pair `never_throw` with a `RecoveredPayloadHandler` to return an empty list, a default object, or any safe fallback instead of `null`. See [Recovered Payload Handler](recovered-payload-handler.md).

---

## CUSTOM — Implement MethodExceptionPolicy

```java title="LogAndNeverThrowPolicy.java"
@Component
public class LogAndNeverThrowPolicy implements MethodExceptionPolicy {

    @Override
    public void handle(MethodExceptionContext context) {
        log.warn("Failover: no recovery for '{}', suppressing exception",
                 context.getFailover().name(), context.getCause());
        // returning without throwing means the caller gets null
    }
}
```

```yaml title="application.yml"
failover:
  exception-policy: custom
```

`MethodExceptionContext` carries:

- `getFailover()` — the `@Failover` annotation metadata
- `getArgs()` — the original method arguments
- `getCause()` — the upstream exception
- `getClazz()` — the return type class

---

## `Error` Is Never Recovered

Exception policies apply only to `Exception` thrown by the upstream call. A `java.lang.Error`
(`OutOfMemoryError`, `StackOverflowError`, linkage errors) propagates **unwrapped** straight to the
caller — the failover aspect never converts it into a recoverable exception, so the recovery path
(which itself allocates) never runs on a JVM that may be dying.

This is deliberate fail-fast behaviour: a `Error` signals a JVM-fatal condition, not "upstream is
down", so the process should be recycled by the platform (k8s liveness, circuit breaker) rather than
limp on while serving stale data. Normal failover for every `Exception` is unchanged.

---

## Next Steps

- [Recovered Payload Handler](recovered-payload-handler.md) — return a safe default on null recovery
- [Properties Reference](../configuration/properties-reference.md) — `failover.exception-policy`
