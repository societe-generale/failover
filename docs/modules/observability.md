---
icon: material/chart-line
---

# Observability

Two modules provide observability: `failover-observable-scanner` discovers `@Failover` methods at startup; `failover-observable-micrometer` adds Micrometer counters and a health indicator.

---

## failover-observable-scanner

Walks the Spring `ApplicationContext` at startup, finds all `@Failover`-annotated methods, and registers them with the `ObservablePublisher`.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-scanner</artifactId>
    <version>3.0.0</version>
</dependency>
```

At startup, the scanner logs a summary:

```
INFO  FailoverScanner: Discovered 5 @Failover methods:
  - country-by-code    (domain=country, expiry=24h)
  - all-countries      (domain=country, expiry=24h)
  - product-by-id      (expiry=6h)
  - exchange-rates     (expiry=1h)
  - client-profile     (expiry=12h)
```

The scanner also warns when two `@Failover` annotations share a domain but have mismatched expiry configurations.

---

## failover-observable-micrometer

Extends the scanner with Micrometer counters and a Spring Boot Actuator health indicator.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-micrometer</artifactId>
    <version>3.0.0</version>
</dependency>
```

Includes `failover-observable-scanner` transitively.

### Micrometer Counter

Counter name: `failover.store`

| Tag | Values |
|---|---|
| `name` | The `@Failover(name=...)` value |
| `action` | `store`, `recover`, `nonRecover`, `cleanByExpiry` |

Counter name: `failover.store.async.failed` — incremented when an async write fails inside the
executor (the async store layer is otherwise visible only in logs).

| Tag | Values |
|---|---|
| `name` | The `@Failover(name=...)` value |
| `operation` | `store`, `delete`, `cleanByExpiry` |
| `exception_type` | The failure's class name |

### Health Indicator

Registered at `/actuator/health` under the `failover` component:

```json
{
  "failover": {
    "status": "UP",
    "details": {
      "enabled": "true",
      "type": "BASIC",
      "store.type": "JDBC",
      "store.jdbc.table-prefix": "MYAPP_",
      "scheduler.enabled": "true"
    }
  }
}
```

---

## ObservablePublisher SPI

`AdvancedFailoverHandler` calls `ObservablePublisher.publish(Metrics)` after every store and recover event. Implement this interface to route metrics to any custom sink:

```java
@Component
public class MyPublisher implements ObservablePublisher {
    @Override
    public void publish(Metrics metrics) {
        log.info("failover event: name={} action={} duration={}ns",
            metrics.getName(),
            metrics.get("action"),
            metrics.get("duration-ns"));
    }
}
```

`Metrics.toMap()` returns all key/value pairs collected during the operation.

---

## Next Steps

- [Observability How-to](../how-to/observability.md) — Prometheus/Grafana setup
- [Scheduler](scheduler.md) — daily report publisher
