---
icon: material/chart-line
---

# Observability

The failover framework emits observability events on every store and recover operation via `ObservablePublisher`, and periodically observes all registered `@Failover` configurations via `FailoverObserver`. Publishers are auto-configured; add your own or extend existing ones.

For a full component reference, see [Observable Modules](../modules/observability.md).

---

## Auto-Configured Publishers

### MdcLoggerObservablePublisher

Logs every operation at INFO level using SLF4J. Before logging, all metric keys are written to the SLF4J MDC so structured log appenders (Logstash, ECS, etc.) capture them as fields:

```
INFO  Failover metrics : country-by-code
```

MDC keys set for the duration of that log line (then restored):

**On store events:**

| MDC key | Value |
|---|---|
| `failover-name` | `@Failover.name()` |
| `failover-action` | `store` |
| `failover-is-stored` | `true` or `false` |
| `failover-expiry-duration` | Configured expiry duration |
| `failover-expiry-unit` | Configured expiry unit (e.g. `HOURS`) |
| `failover-duration-ns` | Wall time of the store call in nanoseconds |

**On recover events:**

| MDC key | Value |
|---|---|
| `failover-name` | `@Failover.name()` |
| `failover-action` | `recover` |
| `failover-is-recovered` | `true` or `false` |
| `failover-is-recovery-failed` | `true` if the recovery itself threw an exception |
| `failover-exception-type` | Canonical class name of the triggering exception |
| `failover-exception-cause-type` | Canonical class name of the cause, or empty |
| `failover-exception-message` | Exception message |
| `failover-exception-cause-message` | Cause message, or empty |
| `failover-expiry-duration` | Configured expiry duration |
| `failover-expiry-unit` | Configured expiry unit |
| `failover-duration-ns` | Wall time of the recover call in nanoseconds |

**On scheduled observe events** (`FailoverObserver.observe()`):

| MDC key | Value |
|---|---|
| `failover-name` | `@Failover.name()` |
| `failover-expiry-duration` | Configured expiry duration |
| `failover-expiry-unit` | Configured expiry unit |
| `failover-metrics-as-on` | ISO-8601 timestamp of the observe run |
| `failover-service-start-time` | ISO-8601 timestamp of service startup |

MDC state is fully restored after each publish call — safe in multi-threaded environments and virtual-thread executors.

---

### MicrometerObservablePublisher

Emits real Micrometer meters on every store/recover event. Requires `failover-observable-micrometer` on the classpath and a `MeterRegistry` bean:

**Operational counters:**

| Metric | Type | Tags | Description |
|---|---|---|---|
| `failover.store.total` | Counter | `name`, `stored` | Every store attempt; `stored=true` when payload was persisted |
| `failover.recover.total` | Counter | `name`, `recovered`, `recovery_failed` | Every recover attempt |
| `failover.exception.total` | Counter | `name`, `exception_type`, `cause_type` | Exception class that triggered recovery |

**Latency timer:**

| Metric | Type | Tags | Description |
|---|---|---|---|
| `failover.operation.duration` | Timer | `name`, `action` | Wall time of store or recover path |

**Tag notes:**
- `name` — bounded cardinality (one value per `@Failover` annotation)
- `stored`, `recovered`, `recovery_failed` — boolean; cardinality 2
- `exception_type`, `cause_type` — class canonical name; keep low-cardinality by not subclassing exceptions per message

Expose via Spring Boot Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics, prometheus, health
```

Query examples:

```
GET /actuator/metrics/failover.store.total?tag=name:country-by-code
GET /actuator/metrics/failover.recover.total?tag=name:country-by-code&tag=recovered:false
```

Prometheus scrape:

```promql
# Recovery failure rate per failover
rate(failover_recover_total{recovered="false"}[5m])
  / rate(failover_recover_total[5m])

# P99 latency of the recover path
histogram_quantile(0.99,
  rate(failover_operation_duration_seconds_bucket{action="recover"}[5m]))
```

---

## Static Configuration Gauges

`FailoverMeterBinder` (part of `failover-observable-micrometer`) registers static gauges at startup for monitoring configuration drift:

| Metric | Type | Tags | Description |
|---|---|---|---|
| `failover.registered.total` | Gauge | — | Total `@Failover` annotations discovered |
| `failover.config.expiry.seconds` | Gauge | `name`, `domain`, `unit` | Configured expiry in seconds |

The `domain` tag on `failover.config.expiry.seconds` equals the `@Failover.domain()` value when set, otherwise the `name` value. Use it to group domain-sharing failovers on dashboards.

---

## Observability Schedule

`ObservableScheduler` calls `FailoverObserver.observe()` on a cron schedule. Each invocation iterates all discovered `@Failover` configurations and publishes one `Metrics` event per failover to all registered publishers.

Configure:

```yaml
failover:
  scheduler:
    enabled: true
    report-cron: "0 0 6 * * *"   # daily at 6 AM (default: midnight)
```

The observe event is separate from the per-operation events — it carries configuration metadata (name, expiry, manifest info) rather than runtime counts.

---

## Health Indicator

`FailoverHealthIndicator` (part of `failover-observable-micrometer`) registers at `/actuator/health/failover`:

| Status | Condition |
|---|---|
| `UP` | Scanner found at least one `@Failover` annotation |
| `DOWN` | Scanner found zero annotations — AOP not wired or beans not Spring-managed |

Response example:

```json
{
  "status": "UP",
  "details": {
    "registered-failovers": 5
  }
}
```

---

## Additional Info

Inject custom key-value pairs into every published `Metrics` event via `FailoverProperties`:

```yaml
failover:
  additional-info:
    environment: production
    team: platform
    app-version: 2.3.1
```

These appear as MDC keys (`failover-environment`, `failover-team`, `failover-app-version`) in every log line and as fields in the scheduled observe events.

---

## Custom ObservablePublisher

Implement `ObservablePublisher` (or extend `AbstractObservablePublisher` for the MDC-restore template) and declare it as a Spring bean:

```java
@Component
public class DatadogObservablePublisher extends AbstractObservablePublisher {

    private final StatsDClient statsd;

    @Override
    public void doPublish(Metrics metrics) {
        String action = metrics.getInfo().get("failover-action");
        if ("recover".equals(action)) {
            String name = metrics.getInfo().getOrDefault("failover-name", "unknown");
            statsd.incrementCounter("failover.recover", "name:" + name);
        }
    }
}
```

The auto-configuration collects **all** `ObservablePublisher` beans into a `CompositeObservablePublisher`. No `@Primary` annotation needed; all publishers receive every event.

Use `AbstractObservablePublisher` when the publisher needs to write to MDC or any thread-local before logging — the base class provides the `doPublish(Metrics)` hook with a clean delegation contract. Use `ObservablePublisher` directly when there is no thread-local state to manage.

---

## Disabling Observability

Disable the scheduler (stops scheduled observe calls; per-operation publishing continues):

```yaml
failover:
  scheduler:
    enabled: false
```

To suppress per-operation events entirely, provide a no-op `ObservablePublisher` bean that overrides the auto-configured one:

```java
@Bean
@Primary
public ObservablePublisher noOpPublisher() {
    return metrics -> {};
}
```
