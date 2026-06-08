---
icon: material/clock-outline
---

# Scheduler

`failover-scheduler` provides two scheduled tasks: an expiry-cleanup scheduler that purges expired store entries, and an observable report scheduler that publishes a daily summary of all active failover configurations.

---

## Expiry Cleanup Scheduler

`ExpiryCleanupScheduler` runs `FailoverHandler.clean()` on a cron schedule:

```java
failoverHandler.clean();
// → failoverStore.cleanByExpiry(Instant.now())
// → deletes all entries where EXPIRE_ON < now
```

Default schedule: **every hour**.

```yaml title="application.yml"
failover:
  scheduler:
    enabled: true
    cleanup-cron: "0 0 * * * *"    # every hour (Spring cron: s m h d M dow)
```

---

## Observable Report Scheduler

`ObservableScheduler` publishes a daily summary of all discovered `@Failover` methods to the configured `ObservablePublisher`. The default publisher writes to SLF4J at INFO level.

Default schedule: **daily at midnight**.

```yaml title="application.yml"
failover:
  scheduler:
    report-cron: "0 0 0 * * *"    # daily midnight
```

The report lists each failover by name, expiry configuration, and domain — useful for auditing active failover coverage.

---

## Disabling the Schedulers

```yaml title="application.yml"
failover:
  scheduler:
    enabled: false
```

Both schedulers are disabled together. There is no way to disable them individually via properties; declare a custom scheduler bean to replace the default behaviour.

---

## Next Steps

- [Observability](observability.md) — publishing metrics and reports
- [Properties Reference](../configuration/properties-reference.md) — `failover.scheduler.*`
