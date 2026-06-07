---
icon: material/eye-outline
---

# Observability

The failover framework includes a layered observability stack. Each layer is independent and optional beyond the core scanner; they compose through a clean SPI.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Application (Spring beans with @Failover)                       │
└─────────────────────────────┬────────────────────────────────────┘
                              │ AOP interception
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│  AdvancedFailoverHandler                                          │
│  Wraps store/recover; publishes Metrics on every call            │
└────────────┬─────────────────────────────────────────────────────┘
             │ ObservablePublisher.publish(Metrics)
             ▼
┌──────────────────────────────────────────────────────────────────┐
│  CompositeObservablePublisher                                     │
│  Fan-out to all registered ObservablePublisher beans             │
├─────────────────────────┬────────────────────────────────────────┤
│  MdcLoggerObservable    │  MicrometerObservablePublisher          │
│  Publisher              │  (failover-observable-micrometer)       │
│  (failover-core)        │                                         │
└─────────────────────────┴────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────┐
  │  SpringContextFailoverScanner  (failover-observable-scanner)  │
  │  Scans all Spring beans at startup; feeds FailoverObserver   │
  │  and FailoverMeterBinder                                     │
  └──────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────┐
  │  ObservableScheduler (failover-scheduler)                    │
  │  Triggers FailoverObserver.observe() on cron                 │
  └──────────────────────────────────────────────────────────────┘
```

---

## Modules

| Module | Artifact ID | What it adds |
|---|---|---|
| Core | `failover-core` | `ObservablePublisher` SPI, `MdcLoggerObservablePublisher`, `FailoverObserver`, `Metrics` |
| Scanner | `failover-observable-scanner` | `SpringContextFailoverScanner` — discovers `@Failover` beans at startup |
| Micrometer | `failover-observable-micrometer` | `MicrometerObservablePublisher`, `FailoverMeterBinder`, `FailoverHealthIndicator` |

All three are included automatically via `failover-spring-boot-starter`. Use individual artifacts for fine-grained dependency control.

---

## Important: Spring Beans Only

!!! warning "@Failover works with Spring-managed beans only"
    The `@Failover` annotation is intercepted through **Spring AOP proxies**. It must be placed on a method of a Spring-managed bean (`@Service`, `@Component`, `@Repository`, `@FeignClient`, etc.) and called through that proxy — not via `this.method()` within the same class.

    The scanner discovers `@Failover` annotations by inspecting Spring bean definitions. Methods on plain Java objects (not registered as beans) are invisible to both the scanner and the aspect.

---

## SpringContextFailoverScanner

`SpringContextFailoverScanner` is the discovery component. It runs once, after all singleton beans are fully instantiated (`SmartInitializingSingleton`), and walks every bean definition in the `ApplicationContext` to find methods annotated with `@Failover`.

**Key behaviours:**

| Behaviour | Mechanism |
|---|---|
| Scan all beans | `applicationContext.getBeanDefinitionNames()` |
| CGLIB proxy unwrapping | `ClassUtils.getUserClass(type)` |
| Annotation on interface method | `AnnotationUtils.findAnnotation(method, Failover.class)` walks hierarchy |
| Skip bridge/synthetic methods | Method filter `!method.isBridge() && !method.isSynthetic()` |
| Duplicate name detection | `discovered.putIfAbsent(name, annotation)` → throws `FailoverScannerException` |
| Domain expiry mismatch | Logs `WARN` if same-domain failovers have different expiry configurations |

No base-package configuration is needed — scans **all** registered beans automatically. Unlike the previous Reflections-based scanner, no `failover.package-to-scan` property is needed or supported.

**`FailoverScanner` SPI:**

```java
public interface FailoverScanner {
    Failover findFailoverByName(String name);
    List<Failover> findAllFailover();
}
```

Override by declaring your own `FailoverScanner` bean — `@ConditionalOnMissingBean` ensures the auto-configured `SpringContextFailoverScanner` backs off.

### Dependency (if not using the starter)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-scanner</artifactId>
    <version>${failover.version}</version>
</dependency>
```

This module depends only on `failover-core` and `spring-context`. It does **not** pull in Micrometer or Spring Boot Actuator.

---

## FailoverObserver and DefaultFailoverObserver

`FailoverObserver` is the central observable interface:

```java
public interface FailoverObserver {
    void observe();
}
```

`DefaultFailoverObserver` implements it: on each `observe()` call it iterates all failovers returned by `FailoverScanner.findAllFailover()` and publishes one `Metrics` event per failover to the `ObservablePublisher`. Each event carries:

| `Metrics` key (prefixed `failover-`) | Value |
|---|---|
| `name` | `@Failover.name()` |
| `expiry-duration` | Configured expiry duration |
| `expiry-unit` | Configured expiry unit |
| `metrics-as-on` | Current time (ISO-8601) |
| `service-start-time` | Service startup time (ISO-8601) |
| Manifest info keys | Build version, artifact, etc. from `MANIFEST.MF` |
| Additional info keys | Any entries from `failover.additional-info` |

`observe()` is called at service startup (via `@PostConstruct`) and on the configured cron schedule via `ObservableScheduler` (default: daily at midnight).

---

## ObservablePublisher SPI

`ObservablePublisher` is the extension point for custom sinks:

```java
public interface ObservablePublisher {
    void publish(Metrics metrics);
}
```

All `ObservablePublisher` beans registered in the Spring context are automatically collected by the auto-configuration into a `CompositeObservablePublisher`, which fan-outs to every registered publisher. No `@Primary` annotation needed.

**Two event types** flow through `publish(Metrics)`:

| Source | Distinguishing key | Description |
|---|---|---|
| `AdvancedFailoverHandler` | `failover-action` = `"store"` or `"recover"` | Per-operation runtime events |
| `DefaultFailoverObserver` | No `failover-action` key (has `failover-metrics-as-on`) | Scheduled configuration summary |

Publishers that handle only one type should check the discriminating key:

```java
String action = metrics.getInfo().get("failover-action");
if (action == null) return; // scheduled summary — not a runtime event
```

---

## MdcLoggerObservablePublisher

Auto-configured in `failover-core`. Writes all `Metrics` entries into SLF4J MDC, emits one INFO log line, then restores the prior MDC state unconditionally:

```java
public void doPublish(Metrics metrics) {
    final Map<String, String> copyOfMdc = MDC.getCopyOfContextMap();
    metrics.getInfo().forEach(MDC::put);
    try {
        log.info("Failover metrics : {}", metrics.getName());
    } finally {
        MDC.setContextMap(copyOfMdc != null ? copyOfMdc : Map.of());
    }
}
```

**On store operations** — MDC keys set for the log line:

| MDC key | Value |
|---|---|
| `failover-name` | `@Failover.name()` |
| `failover-action` | `store` |
| `failover-is-stored` | `true` / `false` |
| `failover-expiry-duration` | Configured duration |
| `failover-expiry-unit` | ChronoUnit name |
| `failover-duration-ns` | Store wall time in nanoseconds |

**On recover operations:**

| MDC key | Value |
|---|---|
| `failover-name` | `@Failover.name()` |
| `failover-action` | `recover` |
| `failover-is-recovered` | `true` / `false` |
| `failover-is-recovery-failed` | `true` if recovery itself threw |
| `failover-exception-type` | Triggering exception class |
| `failover-exception-cause-type` | Cause class, or empty |
| `failover-exception-message` | Exception message |
| `failover-exception-cause-message` | Cause message, or empty |
| `failover-expiry-duration` | Configured duration |
| `failover-expiry-unit` | ChronoUnit name |
| `failover-duration-ns` | Recover wall time in nanoseconds |

**On scheduled observe events:**

| MDC key | Value |
|---|---|
| `failover-name` | `@Failover.name()` |
| `failover-expiry-duration` | Configured duration |
| `failover-expiry-unit` | ChronoUnit name |
| `failover-metrics-as-on` | Observe timestamp (ISO-8601) |
| `failover-service-start-time` | Service startup time (ISO-8601) |
| Any `failover.additional-info` key | Corresponding configured value |

The `finally` restore is safe under virtual threads and platform-thread pools — no MDC leakage across calls.

---

## MicrometerObservablePublisher

Part of `failover-observable-micrometer`. Translates per-operation `Metrics` events into real Micrometer meters. Startup/observe events (no `failover-action` key) are silently ignored — those are handled by `FailoverMeterBinder`.

**Meters emitted:**

| Meter | Type | Tags | Description |
|---|---|---|---|
| `failover.store.total` | Counter | `name`, `stored` | Every store attempt; `stored=true` when payload was persisted |
| `failover.recover.total` | Counter | `name`, `recovered`, `recovery_failed` | Every recover attempt |
| `failover.exception.total` | Counter | `name`, `exception_type`, `cause_type` | Exception class that triggered recovery |
| `failover.operation.duration` | Timer | `name`, `action` | Wall time of store or recover path |

`failover.operation.duration` is recorded only when the `Metrics` bag contains a `failover-duration-ns` key — always present when `AdvancedFailoverHandler` is in the chain (the auto-configured default).

**Tag cardinality rules:**

- `name` — bounded (one value per `@Failover` annotation)
- `stored`, `recovered`, `recovery_failed` — boolean; cardinality 2
- `exception_type`, `cause_type` — class canonical names; keep low-cardinality (never tag exception messages)

**Prometheus queries:**

```promql
# Recovery failure rate per failover
rate(failover_recover_total{recovered="false"}[5m])
  / rate(failover_recover_total[5m])

# P99 recover latency
histogram_quantile(0.99,
  rate(failover_operation_duration_seconds_bucket{action="recover"}[5m]))

# Any store failures (should be 0)
rate(failover_store_total{stored="false"}[5m]) > 0
```

---

## FailoverMeterBinder

Part of `failover-observable-micrometer`. Implements both `MeterBinder` and `SmartInitializingSingleton`.

**`bindTo(MeterRegistry)`** — called immediately when the registry is available. Registers a lazy total-count gauge that samples the scanner live on each scrape:

| Meter | Type | Tags | Description |
|---|---|---|---|
| `failover.registered.total` | Gauge | — | Total `@Failover` annotations found |

**`afterSingletonsInstantiated()`** — called after the scanner has completed its scan. Registers per-failover expiry gauges:

| Meter | Type | Tags | Description |
|---|---|---|---|
| `failover.config.expiry.seconds` | Gauge | `name`, `domain`, `unit` | Configured expiry in seconds |

The `domain` tag equals `@Failover.domain()` when set, otherwise `@Failover.name()`. Use it to group domain-sharing failovers on dashboards without duplicating per-name rows.

---

## FailoverHealthIndicator

Part of `failover-observable-micrometer`. Active when `spring-boot-starter-actuator` is on the classpath. Registered automatically at `/actuator/health/failover`.

| Status | Condition |
|---|---|
| `UP` | Scanner found ≥ 1 `@Failover` annotation |
| `DOWN` | Scanner found 0 annotations — AOP not wired or beans not Spring-managed |

`DOWN` is a strong signal of misconfiguration: the `@Failover` beans are either not Spring-managed, or `@EnableAspectJAutoProxy` is missing.

Response example:

```json
{
  "status": "UP",
  "details": {
    "registered-failovers": 5
  }
}
```

Enable the health endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics, prometheus, health
  endpoint:
    health:
      show-details: always
```

---

## Metrics Event Model

All observability events carry a `Metrics` object — a prefixed key-value bag:

```java
Metrics.of("country-by-code")          // sets failover-name = "country-by-code"
    .collect("action", "store")         // → failover-action = "store"
    .collect("is-stored", "true");      // → failover-is-stored = "true"
```

Every key is prefixed with `"failover-"` automatically by `Metrics.collect()`. Publishers read from `metrics.getInfo()` which returns an unmodifiable map.

---

## Dependency Reference

### Scanner only (no Micrometer)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-scanner</artifactId>
    <version>${failover.version}</version>
</dependency>
```

This module depends only on `failover-core` and `spring-context`. It does **not** pull in Micrometer or Spring Boot Actuator.

### Scanner + Micrometer meters + Actuator health

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-micrometer</artifactId>
    <version>${failover.version}</version>
</dependency>
```

Transitively includes `failover-observable-scanner`.

### Starter (includes everything)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-spring-boot-starter</artifactId>
    <version>${failover.version}</version>
</dependency>
```

---

## Custom ObservablePublisher

Implement `ObservablePublisher` (or extend `AbstractObservablePublisher` for the MDC-restore template) and declare it as a Spring bean. The auto-configuration wraps all `ObservablePublisher` beans into a `CompositeObservablePublisher` automatically — no `@Primary` override needed.

**Publishing to an alert channel on missed recovery:**

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

**Filtering to runtime events only and acting on recovery misses:**

```java
@Component
public class PagerDutyObservablePublisher extends AbstractObservablePublisher {

    private final PagerDutyClient pagerDuty;

    @Override
    public void doPublish(Metrics metrics) {
        String action = metrics.getInfo().get("failover-action");
        if (!"recover".equals(action)) return;

        boolean recovered = Boolean.parseBoolean(
            metrics.getInfo().getOrDefault("failover-is-recovered", "false"));
        if (!recovered) {
            pagerDuty.trigger("Failover miss: " + metrics.getName());
        }
    }
}
```

Use `AbstractObservablePublisher` when delegating to MDC-sensitive code — the base class provides the `doPublish(Metrics)` hook with a clean delegation contract. Implement `ObservablePublisher` directly for simpler cases.

---

## Observability Schedule Configuration

| Property | Default | Description |
|---|---|---|
| `failover.scheduler.enabled` | `true` | Enable/disable both schedulers |
| `failover.scheduler.report-cron` | `0 0 0 * * *` | Cron for `FailoverObserver.observe()` |
| `failover.scheduler.cleanup-cron` | `0 0 * * * *` | Cron for expiry cleanup (unrelated to observability) |

```yaml
failover:
  scheduler:
    enabled: true
    report-cron: "0 0 6 * * *"   # daily at 6 AM

  additional-info:
    environment: production
    team: platform
    version: 2.3.1
```

See [Scheduler](scheduler.md) for full scheduler configuration.
