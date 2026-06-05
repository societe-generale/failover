# Async Store Module

`failover-store-async` is a decorator that wraps any `FailoverStore` and offloads write operations to a background `TaskExecutor`. Read operations (`find`) remain synchronous to guarantee consistent recovery.

## How It Works

```
Caller → AsyncFailoverStore → TaskExecutor (virtual threads)
                           ↘ store / delete / cleanByExpiry (async)
         ← result immediately
```

`find` bypasses the executor and queries the delegate store directly on the calling thread.

## Configuration

Async writes are enabled by default via `failover.store.async=true`. No extra dependency is needed — the async decorator is part of `failover-spring-boot-autoconfigure`.

```yaml
failover:
  store:
    async: true   # default
```

## Disabling Async

Set `async=false` when:

- Using the JDBC `SCHEMA` multi-tenant strategy (the cleanup scheduler runs on a thread without tenant context)
- Writing deterministic integration tests that assert on database rows immediately after a store call
- Debugging unexpected `null` recoveries that might be caused by write-before-read races

```yaml
failover:
  store:
    async: false
```

## Executor Configuration

The background executor is auto-configured as a virtual-thread `TaskExecutor` (Java 21+). Override it by declaring a `TaskExecutor` bean named `failoverStoreExecutor`:

```java
@Bean("failoverStoreExecutor")
public TaskExecutor failoverStoreExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(4);
    exec.setMaxPoolSize(16);
    exec.setQueueCapacity(1000);
    exec.setThreadNamePrefix("failover-store-");
    exec.initialize();
    return exec;
}
```
