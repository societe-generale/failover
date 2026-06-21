---
icon: material/chart-line
---

# Observability

Two modules provide observability: `failover-scanner` discovers `@Failover` methods at startup; `failover-observable-micrometer` adds Micrometer counters and a health indicator.

---

## failover-scanner

Walks the Spring `ApplicationContext` at startup, finds all `@Failover`-annotated methods, and registers them with the `ObservablePublisher`.

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-scanner</artifactId>
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

Includes `failover-scanner` transitively.

### Micrometer Counter

Counters: `failover.store.total{name, stored}` (one per store) and
`failover.recover.total{name, recovered, recovery_failed}` (one per recover attempt). A `Timer`
`failover.operation.duration{name, action}` records wall time.

Counter name: `failover.recovery.outcome.total` — one event **per intercepted method call**; the
source for the failover / recovery / non-recovery rates. See
[Observability how-to](../how-to/observability.md#failover-recovery-non-recovery-rate-per-method).

| Tag | Values |
|---|---|
| `name` | The `@Failover(name=...)` value |
| `domain` | The `@Failover(domain=...)`, falling back to `name` |
| `method` | The intercepted method as `SimpleClass#method` |
| `outcome` | `recovered`, `not_recovered`, `error` |

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

### Non-blocking by construction

Every `ObservablePublisher` — the built-in ones **and your custom bean** — runs **off the caller's thread**, so publishing can never block or slow the `@Failover` business call. You get this for free; no async code in your publisher.

How: all `ObservablePublisher` beans are gathered into a single `CompositeObservablePublisher`, which is wrapped in an `AsyncObservablePublisher`. The `@Failover` path only ever calls that wrapper — it does a bounded, non-blocking hand-off to a virtual-thread drain worker, and your `publish(...)` runs there. A full queue **drops** the metric (counted as `failover.metrics.dropped.total`) rather than back-pressuring the caller.

Implications for a custom publisher:

- Do **not** assume `publish(...)` runs on the request thread — no `ThreadLocal`/request-scoped state, no MDC unless you set it yourself.
- A slow or failing publisher cannot stall the business call; an exception is logged and the drain loop continues.
- Disable globally for deterministic tests with `failover.observable.async.enabled=false` (publishes synchronously). Tune the buffer with `failover.observable.async.queue-capacity`.

---

## Next Steps

- [Observability How-to](../how-to/observability.md) — Prometheus/Grafana setup
- [Scheduler](scheduler.md) — daily report publisher
