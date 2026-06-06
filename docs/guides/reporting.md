---
icon: material/chart-line
---

# Reporting

The failover framework emits events on every store and recover operation. Two report publishers are auto-configured; you can add your own or extend the existing ones.

---

## Auto-Configured Publishers

### LoggerReportPublisher

Logs every store/recover event at INFO level using SLF4J:

```
INFO  Failover : Storing information on 'country-by-code' for failover. ReferentialPayload : {name=country-by-code, key=FR, ...}
INFO  Failover Recovery : Successfully recovered the information on 'country-by-code' from failover store.
```

### MetricsReportPublisher

Emits Micrometer counters:

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

## ReportScheduler

A scheduled summary report runs daily at midnight (configurable). It collects counts per failover name and publishes them via `FailoverReporter`. Configure:

```yaml
failover:
  scheduler:
    report-cron: "0 0 6 * * *"   # daily at 6 AM
```

---

## Custom Report Publisher

Implement `ReportPublisher` and declare it as a Spring bean:

```java
@Component
public class SlackReportPublisher implements ReportPublisher {

    private final SlackClient slack;

    @Override
    public void publish(Event event) {
        if (event.getType() == EventType.RECOVER) {
            slack.sendAlert("Failover recover: " + event.getName() + " — " + event.getKey());
        }
    }
}
```

### CompositeReportPublisher

Combine multiple publishers:

```java
@Bean
@Primary
public ReportPublisher compositePublisher(
        LoggerReportPublisher logger,
        MetricsReportPublisher metrics,
        SlackReportPublisher slack) {
    return new CompositeReportPublisher(List.of(logger, metrics, slack));
}
```

Mark with `@Primary` to replace the default composite, or let the auto-configuration pick up all `ReportPublisher` beans automatically.

---

## Event Model

`Event` carries:

| Field | Type | Description |
|---|---|---|
| `type` | `EventType` | `STORE` or `RECOVER` |
| `name` | `String` | The `@Failover` name |
| `key` | `String` | The derived store key |
| `timestamp` | `Instant` | When the event occurred |
| `payload` | `Object` | The stored or recovered payload |
| `throwable` | `Throwable` | The exception (recover events only) |
