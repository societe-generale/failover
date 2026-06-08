---
icon: material/transfer
---

# Context Propagation

Scatter/gather operations dispatch per-entity slices to a virtual-thread executor. Thread-local state (tenant ID, MDC trace context, security principal) captured on the calling thread is not automatically available on executor threads. `ContextPropagator` bridges this gap.

---

## Interface

```java
public interface ContextPropagator {
    Supplier<Runnable> wrap(Supplier<Runnable> task);

    static ContextPropagator noOp() { ... }
}
```

`wrap` is a higher-order function. It receives a `Supplier<Runnable>` (the slice task) and returns a new `Supplier<Runnable>` that:

1. Captures context on the calling thread when `wrap` is invoked.
2. Restores context before executing the task on the executor thread.
3. Cleans up context after the task completes.

---

## When to Use

You need `ContextPropagator` when:

- Scatter/gather is enabled (`payloadSplitter` set)
- Your store/recover path reads thread-local state (e.g. `TenantContext.current()`, `MDC.get("traceId")`)
- You use `failover.scatter.parallel=true` (the default)

---

## Step 1 — Implement ContextPropagator

```java title="MdcContextPropagator.java"
@Component
public class MdcContextPropagator implements ContextPropagator {

    @Override
    public Supplier<Runnable> wrap(Supplier<Runnable> task) {
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();  // capture on caller thread

        return () -> {
            Runnable runnable = task.get();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
                    else MDC.clear();
                    runnable.run();
                } finally {
                    if (previous != null) MDC.setContextMap(previous);
                    else MDC.clear();
                }
            };
        };
    }
}
```

---

## Step 2 — Tenant Context Example

```java title="TenantContextPropagator.java"
@Component
public class TenantContextPropagator implements ContextPropagator {

    @Override
    public Supplier<Runnable> wrap(Supplier<Runnable> task) {
        String tenantId = TenantContext.current();   // captured on the calling thread

        return () -> {
            Runnable runnable = task.get();
            return () -> {
                TenantContext.set(tenantId);
                try {
                    runnable.run();
                } finally {
                    TenantContext.clear();
                }
            };
        };
    }
}
```

Multiple `ContextPropagator` beans are composed into a `CompositeContextPropagator` by auto-configuration — register one bean per concern.

---

## Next Steps

- [Payload Splitter](payload-splitter.md) — scatter/gather implementation
- [Scatter / Gather Concepts](../concepts/scatter-gather.md) — how parallel dispatch works
