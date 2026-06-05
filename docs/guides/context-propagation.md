# Context Propagation

When Failover dispatches work to background threads (async store writes, scatter/gather parallel slices), thread-bound context — tenant ID, MDC log entries, security principal — must be explicitly captured and restored on the executor thread.

---

## The ContextPropagator Contract

```java
public interface ContextPropagator {
    Runnable wrap(Runnable task);
    <T> Supplier<T> wrapSupplier(Supplier<T> task);
    static ContextPropagator noOp() { ... }
}
```

`wrap` captures thread-local state on the **calling thread** and returns a `Runnable` that restores that state on the **executor thread** before the task runs, then cleans up afterwards.

---

## Built-in Propagators

### MdcContextPropagator

Copies all MDC entries from the calling thread to the executor thread.

```java
@Bean
public ContextPropagator mdcContextPropagator() {
    return new MdcContextPropagator();
}
```

Use when your log format includes MDC fields (trace ID, request ID, user ID) and you want them to appear in async store operation logs.

### TenantContextPropagator

Copies the current tenant from `TenantContext` (provided by `failover-store-multitenant`).

```java
@Bean
public ContextPropagator tenantContextPropagator() {
    return new TenantContextPropagator();
}
```

Required when:
- Multi-tenant is enabled (`failover.store.multitenant.enabled=true`)
- Async writes are enabled (`failover.store.async=true`)
- Scatter/gather parallel mode is enabled (`failover.scatter.parallel=true`)

### MicrometerContextPropagator

Propagates Micrometer `Observation` context for distributed tracing.

```java
@Bean
public ContextPropagator micrometerContextPropagator(ObservationRegistry registry) {
    return new MicrometerContextPropagator(registry);
}
```

---

## Composing Multiple Propagators

Combine several propagators with `CompositeContextPropagator`:

```java
@Bean
public ContextPropagator contextPropagator(
        MdcContextPropagator mdc,
        TenantContextPropagator tenant,
        MicrometerContextPropagator micrometer) {
    return new CompositeContextPropagator(List.of(mdc, tenant, micrometer));
}
```

Each propagator wraps the task in sequence — outermost captures first, restores last.

---

## Custom Propagator

```java
@Component
public class SecurityContextPropagator implements ContextPropagator {

    @Override
    public Runnable wrap(Runnable task) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            try {
                task.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    @Override
    public <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            try {
                return task.get();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
```

---

## When Propagation Is Needed

| Scenario | Propagation required |
|---|---|
| Async store writes (`store.async=true`) | TenantContextPropagator (if multi-tenant) |
| Scatter/gather parallel (`scatter.parallel=true`) | TenantContextPropagator + any MDC/security |
| Synchronous writes (`store.async=false`) | Not required |
| Sequential scatter (`scatter.parallel=false`) | Not required |
