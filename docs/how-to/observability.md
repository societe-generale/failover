---
icon: material/chart-bar
---

# Observability

Failover emits structured logs and Micrometer counters on every operation. Add `failover-observable-micrometer` to enable metrics and the Actuator health indicator.

---

## Enable Micrometer Metrics

```xml title="pom.xml"
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-micrometer</artifactId>
    <version>3.0.0</version>
</dependency>
```

Requires `micrometer-core` and `spring-boot-starter-actuator` on the classpath (typically already present).

---

## Micrometer Counters

Every store and recover operation increments the `failover.store` counter:

| Tag | Values | Description |
|---|---|---|
| `name` | `<failover name>` | The `@Failover(name=...)` value |
| `action` | `store`, `recover`, `nonRecover`, `cleanByExpiry` | The operation performed |

- `store` — upstream succeeded, entry stored.
- `recover` — upstream failed, stored entry served.
- `nonRecover` — upstream failed, no valid entry found.
- `cleanByExpiry` — expiry cleanup deleted entries.

### Prometheus Scrape Example

```yaml title="prometheus.yml"
scrape_configs:
  - job_name: myapp
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: /actuator/prometheus
```

Query to track recovery rate:

```
rate(failover_store_total{action="recover"}[5m])
/
(rate(failover_store_total{action="recover"}[5m]) + rate(failover_store_total{action="nonRecover"}[5m]))
```

---

## Actuator Health Indicator

The `FailoverHealthIndicator` is registered at `/actuator/health`:

```json
{
  "status": "UP",
  "components": {
    "failover": {
      "status": "UP",
      "details": {
        "enabled": "true",
        "type": "BASIC",
        "store.type": "JDBC"
      }
    }
  }
}
```

---

## Structured Logs

| Level | Event | Logger |
|---|---|---|
| `INFO` | Entry stored successfully | `DefaultFailoverHandler` |
| `INFO` | Entry recovered successfully | `DefaultFailoverHandler` |
| `WARN` | No stored entry found | `DefaultFailoverHandler` |
| `INFO` | Expiry cleanup executed | `DefaultFailoverHandler` |
| `ERROR` | Recovery threw unexpected exception | `AdvancedFailoverHandler` |

### Adjust Log Level

```yaml title="application.yml"
logging:
  level:
    com.societegenerale.failover: INFO    # DEBUG for full payload detail
```

---

## Custom ObservablePublisher

`AdvancedFailoverHandler` calls `ObservablePublisher.publish(Metrics)` on every operation. Implement `ObservablePublisher` to send metrics to a custom sink:

```java title="KafkaObservablePublisher.java"
@Component
public class KafkaObservablePublisher implements ObservablePublisher {

    @Override
    public void publish(Metrics metrics) {
        kafkaTemplate.send("failover-events", metrics.getName(), metrics.toMap());
    }
}
```

---

## Next Steps

- [Observability Module](../modules/observability.md) — module details and scanner
- [Scheduler Module](../modules/scheduler.md) — observable report scheduler
