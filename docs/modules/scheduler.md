# Scheduler Module

`failover-scheduler` provides two background jobs that maintain the health of the failover store.

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-scheduler</artifactId>
    <version>3.0.0</version>
</dependency>
```

Included transitively via `failover-spring-boot-starter`.

---

## ExpiryCleanupScheduler

Removes all expired entries from the store by calling `FailoverHandler.clean()`.

```yaml
failover:
  scheduler:
    enabled: true
    cleanup-cron: "0 0 * * * *"   # every hour (default)
```

The cleanup calls `FailoverStore.cleanByExpiry(now)`. For JDBC stores this executes:

```sql
DELETE FROM FAILOVER_STORE WHERE EXPIRE_ON < ?
```

!!! tip "Cleanup frequency"
    Tune `cleanup-cron` based on your store size and expiry distribution. More frequent cleanup keeps the JDBC table smaller at the cost of more DELETE queries.

---

## ReportScheduler

Publishes a store health snapshot via `FailoverReporter` on a cron schedule.

```yaml
failover:
  scheduler:
    enabled: true
    report-cron: "0 0 0 * * *"   # daily at midnight (default)
```

The report includes counts per failover name and any store-level metrics. Output depends on the configured `ReportPublisher` — default is a structured log entry.

---

## Disabling the Scheduler

```yaml
failover:
  scheduler:
    enabled: false
```

Disables both schedulers. Individual jobs cannot be disabled independently — disable the whole scheduler and implement your own if needed.

---

## Manual Invocation

Call either scheduler's method directly from application code:

```java
@Autowired FailoverHandler<Object> handler;

// manual cleanup
handler.clean();
```

```java
@Autowired FailoverReporter reporter;

// manual report
reporter.report();
```
