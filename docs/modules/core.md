# Core Module

`failover-core` contains the central abstractions: handler, key generation, expiry policy, payload enrichment, store interface, and reporting.

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-core</artifactId>
    <version>3.0.0</version>
</dependency>
```

Included transitively via `failover-spring-boot-starter`.

---

## Key Interfaces

### FailoverHandler

Coordinates store/recover/clean operations.

```java
public interface FailoverHandler<T> {
    T store(Failover failover, List<Object> args, T payload);
    T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable throwable);
    void clean();
}
```

Three implementations:

| Class | Purpose |
|---|---|
| `DefaultFailoverHandler` | Standard single-key store/recover |
| `AdvancedFailoverHandler` | Extends default with additional reporting hooks |
| `ScatterGatherFailoverHandler` | Scatter/gather routing decorator |

### FailoverStore

Backing store abstraction.

```java
public interface FailoverStore<T> {
    void store(ReferentialPayload<T> payload);
    Optional<ReferentialPayload<T>> find(String name, String key);
    void cleanByExpiry(LocalDateTime expiry);
    void delete(ReferentialPayload<T> payload);
}
```

### KeyGenerator

Derives a store key from method arguments.

```java
public interface KeyGenerator {
    String key(Failover failover, List<Object> args);
}
```

Default: `DefaultKeyGenerator`. Override per-method with `@Failover(keyGenerator = "myBean")`.

### ExpiryPolicy

Computes and checks TTL.

```java
public interface ExpiryPolicy<T> {
    LocalDateTime computeExpiry(Failover failover);
    boolean isExpired(Failover failover, ReferentialPayload<T> payload);
}
```

Override per-method with `@Failover(expiryPolicy = "myBean")`.

### PayloadEnricher

Populates `upToDate`/`asOf`/`metadata` on domain objects.

```java
public interface PayloadEnricher<T> {
    ReferentialPayload<T> enrichOnStore(Failover failover, Class<T> clazz, ReferentialPayload<T> payload);
    ReferentialPayload<T> enrichOnRecover(Failover failover, Class<T> clazz, ReferentialPayload<T> payload, Throwable cause);
}
```

Default: `DefaultPayloadEnricher`. Checks if payload implements `Referential` or `ReferentialAware`, then sets fields.

### RecoveredPayloadHandler

Handles the result after recovery (including `null` when nothing was found).

```java
public interface RecoveredPayloadHandler {
    <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload);
}
```

Default: `PassThroughRecoveredPayloadHandler` — returns the payload unchanged. Provide a custom bean to return a default object instead of `null`.

---

## Context Propagation

`ContextPropagator` wraps tasks submitted to executor threads to carry thread-bound context (MDC, tenant, security):

```java
public interface ContextPropagator {
    Runnable wrap(Runnable task);
    <T> Supplier<T> wrapSupplier(Supplier<T> task);
    static ContextPropagator noOp() { ... }
}
```

Built-in: `MdcContextPropagator` (copies MDC entries), `TenantContextPropagator` (in `failover-store-multitenant`). Compose multiple propagators with `CompositeContextPropagator`.

---

## Reporting

`FailoverReporter` emits events on every store/recover operation:

```java
public interface FailoverReporter {
    void onStore(Event event);
    void onRecover(Event event);
}
```

Two publishers auto-configured:

| Publisher | Output |
|---|---|
| `LoggerReportPublisher` | Structured log at INFO level |
| `MetricsReportPublisher` | Micrometer counters (`failover.store.count`, `failover.recover.count`) |

Compose additional publishers via `CompositeReportPublisher`. See [Reporting Guide](../guides/reporting.md).
