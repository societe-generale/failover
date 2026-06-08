---
icon: material/code-braces
---

# Key Interfaces

All public SPI interfaces with their method signatures and contracts.

---

## FailoverHandler\<T\>

Central coordinator. The default implementation chain is `AdvancedFailoverHandler → ScatterGatherFailoverHandler → DefaultFailoverHandler`.

```java
public interface FailoverHandler<T> {
    T store(Failover failover, List<Object> args, T payload);
    T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause);
    void clean();
}
```

| Method | Called when | Side effects |
|---|---|---|
| `store` | Upstream succeeds | Writes entry to `FailoverStore` |
| `recover` | Upstream throws | Reads from `FailoverStore`, checks expiry |
| `clean` | Scheduled cleanup | Deletes expired entries via `cleanByExpiry` |

---

## FailoverStore\<T\>

Persistence contract. Implementations must return a **defensive copy** from `find()`.

```java
public interface FailoverStore<T> {
    void store(ReferentialPayload<T> referentialPayload);
    void delete(ReferentialPayload<T> referentialPayload);
    Optional<ReferentialPayload<T>> find(String name, String key);
    void cleanByExpiry(Instant expiry);
}
```

!!! warning "Defensive copy in `find()`"
    `find()` must return a copy of the stored object, not a live reference. Callers mutate `upToDate` and `asOf` on the returned object without affecting the persisted data. This is guaranteed for all built-in stores. Custom stores must ensure this themselves.

---

## KeyGenerator

Derives a raw string key from method arguments.

```java
public interface KeyGenerator {
    String key(Failover failover, List<Object> args);
}
```

The returned string is then wrapped by `FailoverKeyGenerator` (Layer 2), which prefixes it with `effectiveName` and hashes to a UUID.

---

## ExpiryPolicy\<T\>

Computes and checks the expiry timestamp for a stored entry.

```java
public interface ExpiryPolicy<T> {
    Instant computeExpiry(Failover failover);
    boolean isExpired(Failover failover, ReferentialPayload<T> payload);
}
```

`computeExpiry` is called at store time. `isExpired` is called at recover time.

---

## PayloadEnricher\<T\>

Enriches the `ReferentialPayload` envelope on both paths.

```java
public interface PayloadEnricher<T> {
    ReferentialPayload<T> enrichOnStore(
        Failover failover, Class<T> clazz, ReferentialPayload<T> payload);

    ReferentialPayload<T> enrichOnRecover(
        Failover failover, Class<T> clazz,
        @Nullable ReferentialPayload<T> payload, Throwable cause);
}
```

Default sets `upToDate=true/false` and `asOf` on the `Referential` / `ReferentialAware` object.

---

## PayloadSplitter\<T, R\>

Splits a composite payload into per-entity slices for scatter/gather operations.

```java
public interface PayloadSplitter<T, R> {
    List<StoreContext<R>> splitOnStore(StoreContext<T> context);
    List<RecoverContext<R>> splitOnRecover(RecoverContext<T> context);
    RecoverContext<T> merge(List<RecoverContext<R>> contexts);
}
```

| Parameter | Meaning |
|---|---|
| `T` | Composite type (`List<Country>`) |
| `R` | Slice type (`Country`) |

---

## RecoveredPayloadHandler

Last-chance hook when recovery returns `null`.

```java
public interface RecoveredPayloadHandler {
    <T> T handle(Failover failover, Class<T> clazz, Throwable cause);
}
```

Called by `AdvancedFailoverHandler` when the inner handler returns `null` and `exception-policy: never_throw`.

---

## ContextPropagator

Propagates thread-local context across virtual-thread executor boundaries in scatter/gather.

```java
public interface ContextPropagator {
    Supplier<Runnable> wrap(Supplier<Runnable> task);
    static ContextPropagator noOp() { ... }
}
```

Multiple beans are composed by auto-configuration into a `CompositeContextPropagator`.

---

## TenantResolver

Returns the current tenant ID for multi-tenant store routing.

```java
public interface TenantResolver {
    String resolve();
}
```

Called on every store/find/delete/cleanByExpiry operation when multi-tenant mode is enabled.

---

## TenantStoreFactory\<T\>

Creates a `FailoverStore<T>` for a given tenant. Required for the `SCHEMA` multi-tenant strategy.

```java
public interface TenantStoreFactory<T> {
    FailoverStore<T> create(String tenantId);
}
```

---

## Next Steps

- [How-to Guides](../how-to/index.md) — implement any of these interfaces
- [@Failover Annotation](annotation.md) — wire custom beans via annotation attributes
