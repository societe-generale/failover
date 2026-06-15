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

### Async Store Failure Counter

When `failover.store.async=true` (the default), store writes run on a background executor and any
failure there is swallowed so it never breaks the business call. To keep a silently-degraded store
layer visible, `FailoverStoreAsync` emits a dedicated counter on each executor-side failure:

| Counter | Tag | Values |
|---|---|---|
| `failover.store.async.failed` | `name` | The `@Failover(name=...)` value |
| | `operation` | `store`, `delete`, `cleanByExpiry` |
| | `exception_type` | The failure's class name |

Alert on any increase — it means failover data is not being persisted (e.g. DB down, connection
pool exhausted):

```
increase(failover_store_async_failed_total[5m]) > 0
```

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
| `INFO` | Entry stored successfully (referential name only) | `DefaultFailoverHandler` |
| `INFO` | Entry recovered successfully (referential name only) | `DefaultFailoverHandler` |
| `DEBUG` | Full `ReferentialPayload` body on store / recover / expired-delete | `DefaultFailoverHandler` |
| `WARN` | No stored entry found | `DefaultFailoverHandler` |
| `INFO` | Expiry cleanup executed | `DefaultFailoverHandler` |
| `ERROR` | Recovery threw unexpected exception | `AdvancedFailoverHandler` |

The lifecycle events stay at `INFO` carrying only the referential name; the full payload body is
logged at `DEBUG` so high-throughput services pay no full-payload serialisation on the `INFO` path
(ADR 48).

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
