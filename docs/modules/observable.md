---
icon: material/eye-outline
---

# Observable Modules

Failover includes a lightweight observability layer that collects metrics from all registered `@Failover` configurations and publishes them to one or more sinks. Two modules cover observability; they are independent so you can use either or both.

---

## Module Overview

| Module | Artifact ID | Purpose |
|---|---|---|
| Scanner | `failover-observable-scanner` | Discovers all `@Failover` annotations in the Spring context |
| Micrometer | `failover-observable-micrometer` | Micrometer meters + Spring Boot Actuator health indicator |

Both are included automatically via `failover-spring-boot-starter`. Use individual artifacts only when assembling a custom dependency set.

---

## Important: Spring Beans Only

!!! warning "@Failover works with Spring-managed beans only"
    The `@Failover` annotation is intercepted through **Spring AOP proxies**. It must be placed on a method of a Spring-managed bean (`@Service`, `@Component`, `@Repository`, `@FeignClient`, etc.) and called through that proxy — not via `this.method()` within the same class.

    The scanner discovers `@Failover` annotations by inspecting Spring bean definitions. Methods on plain Java objects (not registered as beans) are invisible to both the scanner and the aspect.

---

## failover-observable-scanner

### What it does

`SpringContextFailoverScanner` scans the Spring `ApplicationContext` for all beans whose methods carry `@Failover`. It runs after every singleton bean has been instantiated (`SmartInitializingSingleton`) so it sees the complete, fully-wired context.

Key behaviours:

- No base-package configuration needed — scans **all** registered beans automatically.
- Unwraps CGLIB proxies so annotations on the real target class are found.
- Follows the method hierarchy: an `@Failover` on an interface method is found even when the implementing class does not repeat it.
- Detects duplicate `@Failover` names at startup and throws `FailoverScannerException` to prevent silent data collisions.

### Dependency (if not using the starter)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-scanner</artifactId>
    <version>3.0.0</version>
</dependency>
```

This module depends only on `failover-core` and `spring-context`. It does **not** pull in Micrometer or Spring Boot Actuator.

---

## FailoverObserver (Core)

`FailoverObserver` is the central observable interface. It collects data from all scanned `@Failover` configurations and emits `Metrics` objects to every registered `ObservablePublisher`.

```java
public interface FailoverObserver {
    void observe();
}
```

`observe()` is called on startup (via Spring `initMethod`) and on the configured schedule (see [Scheduler](scheduler.md)).

### Default publisher: MdcLoggerObservablePublisher

Auto-configured out of the box. Writes `@Failover` metrics to MDC and then logs at INFO:

```
INFO  Failover metrics : country-by-code
```

MDC entries (visible in any structured-log appender):

| MDC key | Value |
|---|---|
| `name` | The `@Failover` name |
| Any additional info key | Corresponding value from `FailoverProperties.additionalInfo()` |

The MDC state from the calling thread is preserved and fully restored after each publish call, making it safe in multi-threaded environments.

---

## failover-observable-micrometer

### What it does

An extension module that adds:

1. **`MicrometerObservablePublisher`** — publishes per-`@Failover` Micrometer gauges so counts appear in Prometheus / Actuator metrics.
2. **`FailoverHealthIndicator`** — reports `DOWN` to Spring Boot Actuator health if the scanner found zero `@Failover` annotations (likely a misconfiguration or AOP not wired).

### Dependency (if not using the starter)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-micrometer</artifactId>
    <version>3.0.0</version>
</dependency>
```

Transitively brings in `failover-observable-scanner`.

### Micrometer metrics

| Metric | Type | Tags | Description |
|---|---|---|---|
| `failover.store.count` | Counter | `name`, `result` | Incremented on every store operation |
| `failover.recover.count` | Counter | `name`, `result` | Incremented on every recover operation |

Expose via Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics, prometheus, health
```

### Health indicator

`/actuator/health/failover` returns:

- `UP` — scanner found at least one `@Failover` bean.
- `DOWN` — scanner found zero annotations. Check that AOP is wired and your annotated beans are Spring-managed.

---

## Custom ObservablePublisher

Implement `ObservablePublisher` and declare it as a Spring bean:

```java
@Component
public class SlackObservablePublisher extends AbstractObservablePublisher {

    private final SlackClient slack;

    @Override
    public void doPublish(Metrics metrics) {
        slack.sendAlert("Failover observe: " + metrics.getName());
    }
}
```

The auto-configuration picks up all `ObservablePublisher` beans and wraps them in a `CompositeObservablePublisher`. No `@Primary` override needed.

---

## ObservableScheduler

The scheduler calls `FailoverObserver.observe()` on a cron schedule (default: daily at midnight). Configure:

```yaml
failover:
  scheduler:
    enabled: true
    report-cron: "0 0 6 * * *"   # daily at 6 AM
```

See [Scheduler](scheduler.md) for full scheduler configuration.
