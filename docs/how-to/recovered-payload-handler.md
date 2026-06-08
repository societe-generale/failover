---
icon: material/refresh
---

# Recovered Payload Handler

`RecoveredPayloadHandler` gives you a last-chance hook when recovery returns `null` — no stored entry found or all entries expired. Register a bean to substitute a safe default instead of returning `null` to callers.

---

## Interface

```java
public interface RecoveredPayloadHandler {
    <T> T handle(Failover failover, Class<T> clazz, Throwable cause);
}
```

Called by `AdvancedFailoverHandler` after a `null` recovery result. Whatever you return here becomes the final value returned to the caller.

---

## When to Use

- Collections: return an empty list instead of `null`
- Optionals: return `Optional.empty()`
- DTOs: return a sentinel object with `upToDate=false` and empty fields

Requires `failover.exception-policy: never_throw` — otherwise the upstream exception is rethrown before this handler is reached.

---

## Step 1 — Set exception-policy

```yaml title="application.yml"
failover:
  exception-policy: never_throw
```

---

## Step 2 — Implement RecoveredPayloadHandler

```java title="EmptyListPayloadHandler.java"
@Component
public class EmptyListPayloadHandler implements RecoveredPayloadHandler {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T handle(Failover failover, Class<T> clazz, Throwable cause) {
        if (List.class.isAssignableFrom(clazz)) {
            return (T) List.of();
        }
        return null;
    }
}
```

!!! note "Global handler"
    There is one `RecoveredPayloadHandler` bean per application context — it applies to all `@Failover` annotations. Use `failover.name()` or `clazz` to distinguish between different failover operations.

---

## Example: Return Empty Optional

```java title="OptionalPayloadHandler.java"
@Component
public class OptionalPayloadHandler implements RecoveredPayloadHandler {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T handle(Failover failover, Class<T> clazz, Throwable cause) {
        if (Optional.class.isAssignableFrom(clazz)) {
            return (T) Optional.empty();
        }
        return null;
    }
}
```

---

## Next Steps

- [Exception Policy](exception-policy.md) — configure `never_throw` to reach this handler
- [Custom Payload Enricher](custom-payload-enricher.md) — enrich payloads at store and recover time
