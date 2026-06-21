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

### Full meter catalog

All `failover.*` meters (counters keep the `_total` suffix in Prometheus; timers export `_sum`/`_count`/`_max` in seconds; gauges export the bare name). Tag every meter with `instance` for cluster attribution by enabling `failover.observable.instance.enabled` (off by default — a Prometheus scrape already adds `instance`; turn it on for push backends / `shared-store`).

| Meter | Type | Key tags | Meaning |
|---|---|---|---|
| `failover.call.total` | counter | `name`, `domain`, `result` (`success`\|`failover`) | Per-call volume — clean upstream success vs failover triggered. |
| `failover.user.impact.total` | counter | `name`, `domain`, `impact` (`unblocked`\|`blocked`) | **Business signal** — caller got a value (fresh or recovered) vs got nothing. |
| `failover.recovery.outcome.total` | counter | `name`, `domain`, `method`, `outcome` | Recovery breakdown (`recovered`/`not_recovered`/`error`); source of the rates. |
| `failover.recovery.partial.total` | counter | `name`, `method` | Scatter/gather recoveries where some slices were missing. |
| `failover.exception.total` | counter | `name`, `exception_type`, `cause_type`, `final_cause_type` | Which exception (and root cause) triggered failover. |
| `failover.store.total` | counter | `name`, `stored` | Store attempts. |
| `failover.store.async.failed` | counter | `name`, `operation`, `exception_type` | Async store-layer failures. |
| `failover.operation.duration` | timer (+percentile histogram) | `name`, `action` (`store`\|`recover`) | Store/recover path latency → p50/p95/p99. |
| `failover.upstream.duration` | timer (+percentile histogram) | `name`, `result` (`success`\|`failure`) | Latency of the protected upstream call itself. |
| `failover.api.health` | gauge | `name`, `domain` | Recent fraction of calls where the caller got a value (1.0 healthy; lower = users blocked). |
| `failover.stale.served.ratio` | gauge | `name`, `domain` | Recent fraction of calls served from stored (stale) data. |
| `failover.live.entries` | gauge | `name`, `domain` | Current stored entry count (cache footprint). In-memory/Caffeine stores only — absent for JDBC/multi-tenant. |
| `failover.metrics.dropped.total` | counter | — | Metrics dropped because the non-blocking publish queue was full (see [non-blocking](#non-blocking-by-construction)). Active only when async publishing is on. |
| `failover.registered.total` | gauge | — | Number of discovered `@Failover` methods. |
| `failover.config.expiry.seconds` | gauge | `name`, `domain`, `unit` | Configured expiry per failover point. |

**Cardinality:** `name`/`domain`/`action`/`result`/`impact`/`outcome` are low-cardinality enums; exception tags use class names. Never tag with the raw store key or exception messages. A guard (`failover.observable.cardinality`) caps distinct `name` values.

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
