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

`MicrometerObservablePublisher` emits a dedicated meter per operation:

| Meter | Type | Tags |
|---|---|---|
| `failover.store.total` | Counter | `name`, `stored` |
| `failover.recover.total` | Counter | `name`, `recovered`, `recovery_failed` |
| `failover.recovery.outcome.total` | Counter | `name`, `domain`, `method`, `outcome` (see below) |
| `failover.recovery.partial.total` | Counter | `name`, `method` (scatter/gather: some-but-not-all slices recovered) |
| `failover.exception.total` | Counter | `name`, `exception_type`, `cause_type` |
| `failover.operation.duration` | Timer | `name`, `action` |
| `failover.store.async.failed` | Counter | `name`, `operation`, `exception_type` |

- `failover.store.total` — one increment per store; `stored=true|false`.
- `failover.recover.total` — one increment per recover attempt (one per intercepted method call).
- `failover.recovery.outcome.total` — the per-method failover / recovery / non-recovery rates (below).

### Gauges (configuration & capacity)

`FailoverMeterBinder` registers these gauges:

| Gauge | Tags | Meaning |
|---|---|---|
| `failover.registered.total` | — | Number of `@Failover`-annotated methods discovered. |
| `failover.config.expiry.seconds` | `name`, `domain`, `unit` | Configured expiry per failover. |
| `failover.live.entries` | `name`, `domain` | Current stored entry count per failover (cache footprint / capacity). |

`failover.live.entries` is registered only when the store can report its size: always for in-memory /
Caffeine, and for **JDBC only when opted in** with `failover.store.jdbc.live-entries-gauge-enabled=true`
(it issues a `SELECT COUNT(*)` per scrape; audit A7). Use it to monitor table growth and alert on
capacity. Not available in multi-tenant mode.

### Failover / Recovery / Non-recovery Rate (per method)

`failover.recovery.outcome.total` is a single counter, recorded **once per intercepted method call**
(the composite — a `findAll()` is one event, not one per entity), from which the three operational
rates are derived. It is tagged by the actual method, so two methods sharing a referential `name`
/`domain` (e.g. `getById` and `findAll`) are distinguishable.

| Counter | Tag | Values |
|---|---|---|
| `failover.recovery.outcome.total` | `name` | the `@Failover(name=...)` value |
| | `domain` | the `@Failover(domain=...)`, falling back to `name` |
| | `method` | the intercepted method as `SimpleClass#method` (e.g. `CountryService#findAll`) |
| | `outcome` | `recovered`, `not_recovered`, `error` |

- `recovered` — a stored value was returned within its expiry (user unblocked).
- `not_recovered` — no stored value (not found or expired) — **actual user impact**.
- `error` — the recover path itself threw (store/serialization fault); kept distinct so a fault is
  never miscounted as a clean miss.

```promql
# Failover rate — upstream failures intercepted, per method
sum(rate(failover_recovery_outcome_total[5m])) by (name, method)

# Recovery rate — failures resolved from the store
rate(failover_recovery_outcome_total{outcome="recovered"}[5m])

# Non-recovery rate — failures with no stored result (alert on this)
rate(failover_recovery_outcome_total{outcome="not_recovered"}[5m])
```

```promql
# Alert: non-recovery share of intercepted failures climbs for a method
sum by (name, method) (rate(failover_recovery_outcome_total{outcome="not_recovered"}[5m]))
/
sum by (name, method) (rate(failover_recovery_outcome_total[5m]))
  > 0.2
```

### Async Store Failure Counter

When `failover.store.async=true` (the default), store writes run on a background executor and any
failure there is swallowed so it never breaks the business call. To keep a silently-degraded store
layer visible, `FailoverStoreAsync` emits a dedicated counter on every dropped write:

| Counter | Tag | Values |
|---|---|---|
| `failover.store.async.failed` | `name` | The `@Failover(name=...)` value |
| | `operation` | `store`, `delete`, `cleanByExpiry` |
| | `exception_type` | The failure's class name |

This counter covers **both** ways an async write can be lost:

- **In-flight failure** — the task ran but the delegate store threw (e.g. DB down, connection pool
  exhausted). `exception_type` is the store's exception.
- **Submit-time rejection** — the task was never accepted because the executor was saturated or
  shutting down. With a bounded executor (`failover.store.async-executor.concurrency-limit > 0`,
  rejection policy `ABORT`) `exception_type` is `java.util.concurrent.RejectedExecutionException`.
  This closes the gap where a saturated executor would otherwise drop writes with no metric.

Alert on any increase — it means failover data is not being persisted:

```
increase(failover_store_async_failed_total[5m]) > 0
```

!!! note "`DISCARD` rejection policy"
    The `DISCARD` rejection policy intentionally drops the task and logs a `WARN` **without**
    incrementing this counter — discarding is the configured behaviour, not a failure. If you need
    every dropped write counted as an alertable signal, use the `ABORT` policy (the rejection is then
    metered as above) rather than `DISCARD`.

### Prometheus Scrape Example

```yaml title="prometheus.yml"
scrape_configs:
  - job_name: myapp
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: /actuator/prometheus
```

Query to track the recovery success ratio (recovered ÷ all intercepted failures), per method:

```promql
sum by (name, method) (rate(failover_recovery_outcome_total{outcome="recovered"}[5m]))
/
sum by (name, method) (rate(failover_recovery_outcome_total[5m]))
```

---

## Kibana Dashboard

`micrometer-registry-elastic` pushes `failover.*` counters and timers to Elasticsearch on a configurable interval. Build native Kibana visualizations directly from that data — no additional instrumentation needed.

### 1. Add the Elastic Registry

```xml title="pom.xml"
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-elastic</artifactId>
</dependency>
```

### 2. Configure the Export

```yaml title="application.yml"
management:
  elastic:
    metrics:
      export:
        enabled: true
        host: https://your-elasticsearch:9200
        index: failover-metrics
        step: 60s
        user-name: elastic
        password: ${ES_PASSWORD}
        # api-key-credentials: ${ES_API_KEY}  # alternative to user/password
```

Metrics land in index `failover-metrics-YYYY-MM-DD` by default. Each document contains the metric `name`, its type-specific value fields (`count`, `sum`, `mean`, `max`), the push `@timestamp`, and all Micrometer tags as top-level fields.

### 3. Create a Kibana Data View

In **Kibana → Stack Management → Data Views**, create:

| Field | Value |
|---|---|
| Name | `Failover Metrics` |
| Index pattern | `failover-metrics-*` |
| Time field | `@timestamp` |

### 4. Key Visualizations (Lens)

Open **Kibana → Dashboards → Create** and add panels using the **Lens** editor.

#### Recovery Rate — line chart over time

| Setting | Value |
|---|---|
| X-axis | `@timestamp` (date histogram, auto / 1 min) |
| Y-axis | `Sum(count)` where `name: failover.recovery.outcome.total AND outcome: recovered` ÷ `Sum(count)` where `name: failover.recovery.outcome.total` |
| Break by | `name.keyword` |

Ratio formula in Lens `Formula` layer: `count(kql='name: "failover.recovery.outcome.total" AND outcome: "recovered"') / count(kql='name: "failover.recovery.outcome.total"')`.

#### Non-Recovery Events — bar chart (alert target)

| Setting | Value |
|---|---|
| X-axis | `@timestamp` (date histogram, 5 min) |
| Y-axis | `Sum(count)` where `name: failover.recovery.outcome.total AND outcome: not_recovered` |
| Break by | `name.keyword` |

Every non-zero bar represents a caller that received no value (user blocked).

#### Exception Breakdown per Endpoint — grouped bar

| Setting | Value |
|---|---|
| X-axis | `name.keyword` (top 10) |
| Y-axis | `Sum(count)` where `name: failover.exception.total` |
| Break by | `exception_type.keyword` |

Shows which endpoint throws which exception type most — aids root-cause triage without navigating logs.

#### Async Store Failures — metric (single value)

| Setting | Value |
|---|---|
| Value | `Sum(count)` where `name: failover.store.async.failed` |
| Time range | Last 1 h |

Any non-zero value means failover data is silently not being persisted.

#### Recovery Latency — line chart

Micrometer timers emit `mean` and `max` per push interval.

| Setting | Value |
|---|---|
| X-axis | `@timestamp` |
| Y-axis | `Avg(mean)` where `name: failover.operation.duration AND action: recover` |

!!! note "True percentiles"
    Enable histogram publishing to get p95/p99 bucket fields:
    ```yaml
    management.elastic.metrics.export.histogramPublish: true
    ```
    Then use the `histogram` bucket fields in Kibana TSVB percentile aggregation.

### 5. Recommended Dashboard Layout

```
┌────────────────────────────────────────────────────────────────────┐
│  Recovery Rate (line, per endpoint)  │  Non-Recovery (bar, 5 min) │
├──────────────────────────────────────┴────────────────────────────┤
│  Exception Breakdown per Endpoint (grouped bar — full width)       │
├──────────────────────┬──────────────────────┬─────────────────────┤
│  Recovery Latency    │  Async Store Failures │  Registered Endpoints │
└──────────────────────┴──────────────────────┴─────────────────────┘
```

### 6. Alerting Rules

In **Kibana → Stack Management → Rules → Create rule → Elasticsearch query**:

**Non-recovery spike (user-impacting — page):**

```
KQL:   name: "failover.recovery.outcome.total" AND outcome: "not_recovered"
Agg:   Sum(count) over last 5 min
When:  > 0
```

**Async store failure (data-loss risk — page):**

```
KQL:   name: "failover.store.async.failed"
Agg:   Sum(count) over last 5 min
When:  > 0
```

**Recovery rate below threshold (warn):**

```
KQL:   name: "failover.recovery.outcome.total"
Agg:   Sum(count, filter: outcome:"recovered") / Sum(count) < 0.7   (10-min window)
When:  formula result < 0.7
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
