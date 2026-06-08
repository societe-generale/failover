---
icon: material/lightning-bolt-circle
---

# Caffeine Store

In-process store backed by the Caffeine cache library. Suitable for single-node deployments where process-local caching is sufficient.

---

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-caffeine</artifactId>
    <version>3.0.0</version>
</dependency>
```

---

## Configuration

```yaml title="application.yml"
failover:
  store:
    type: caffeine
```

No additional configuration is required. Caffeine uses the `expireOn` field from `ReferentialPayload` to set per-entry TTL at write time.

---

## Behaviour

- **Eviction**: entries are evicted by Caffeine at their configured `expireOn` timestamp. The cleanup scheduler still runs but has nothing to remove in most cases.
- **Memory**: all entries live in the JVM heap. Size is bounded by the number of distinct failover keys in your application.
- **Persistence**: data is lost on JVM restart — the first post-restart calls are unprotected until upstream succeeds.
- **Multi-node**: each node has its own cache. There is no cluster synchronisation.

---

## When to Use vs JDBC

| Scenario | Caffeine | JDBC |
|---|---|---|
| Single-node deployment | ✅ | ✅ |
| Multiple nodes / horizontal scale | ❌ | ✅ |
| Survive restarts | ❌ | ✅ |
| Zero external dependency | ✅ | Requires a DB |
| Low-latency reads | ✅ (in-process) | Depends on DB latency |

---

## Next Steps

- [JDBC Store](store-jdbc.md) — persistent, multi-node store
- [Store Types](../configuration/store-types.md) — comparison of all store types
