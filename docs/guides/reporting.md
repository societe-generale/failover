---
icon: material/chart-line
---

# Observability

The failover framework emits events on every store and recover operation via `ObservablePublisher`, and periodically observes all registered `@Failover` configurations via `FailoverObserver`. Publishers are auto-configured; you can add your own or extend the existing ones.

---

## Auto-Configured Publishers

### MdcLoggerObservablePublisher

Logs every observation at INFO level using SLF4J, with MDC enriched for the duration of the call:

```
INFO  Failover metrics : country-by-code
```

MDC is fully restored after each publish — safe in multi-threaded environments.

### MicrometerObservablePublisher

Emits Micrometer counters (requires `failover-observable-micrometer`):

| Metric | Tags | Description |
|---|---|---|
| `failover.store.count` | `name`, `result` | Incremented on every store operation |
| `failover.recover.count` | `name`, `result` | Incremented on every recover operation |

Expose via Spring Boot Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics, prometheus
```

Query:
```
http://localhost:8080/actuator/metrics/failover.store.count
http://localhost:8080/actuator/metrics/failover.recover.count
```

---

## ObservableScheduler

A scheduled summary runs on a cron schedule (default: daily at midnight). It calls `FailoverObserver.observe()`, which collects counts per failover name and publishes them via all registered `ObservablePublisher` beans. Configure:

```yaml
failover:
  scheduler:
    report-cron: "0 0 6 * * *"   # daily at 6 AM
```

---

## Custom ObservablePublisher

Implement `ObservablePublisher` (or extend `AbstractObservablePublisher`) and declare it as a Spring bean:

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

The auto-configuration collects all `ObservablePublisher` beans into a `CompositeObservablePublisher` automatically.

---

## Event Model

`Metrics` carries:

| Field | Type | Description |
|---|---|---|
| `name` | `String` | The `@Failover` name |
| `info` | `Map<String, String>` | Additional info from `FailoverProperties.additionalInfo()` |

Events on individual store/recover operations are delivered through `FailoverObserver` → `ObservablePublisher` on each observation cycle.
