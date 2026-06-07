---
icon: material/key-outline
---

# Key Generation

The store key uniquely identifies a stored entry within a named failover. It is derived from the method arguments at both store time and recover time, so the same arguments always map to the same stored value.

---

## Default Key Generator

`DefaultKeyGenerator` converts method arguments to strings using these rules:

| Argument type | Key segment |
|---|---|
| No arguments | `NO-ARG` |
| `String`, `Number`, `Boolean`, primitive | `String.valueOf(arg)` |
| `Collection` | elements joined by `,` |
| Array | elements joined by `,` |
| Any other type | `ClassName@hexHashCode` _(warning logged)_ |

Multiple arguments are joined with `:`.

**Examples:**

| Call | Key |
|---|---|
| `findByCode("FR")` | `FR` |
| `findByIds(List.of(1, 2, 3))` | `1,2,3` |
| `findByCriteria("active", "EU")` | `active:EU` |
| `findAll()` (no args) | `NO-ARG` |

!!! warning "Custom objects as arguments"
    If a method argument is a complex object (not a String/Number/Boolean/Collection), `DefaultKeyGenerator` uses `ClassName@hashCode`. This works only if `hashCode()` is stable and consistent with `equals()`. Otherwise configure a custom `KeyGenerator`.

---

## Three-Layer Key Generation Architecture

Key generation has three distinct layers. Understanding each layer is important when configuring custom key generators or reasoning about cross-failover store sharing.

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1 — User-visible key generators                          │
│  DefaultKeyGenerator  OR  custom KeyGenerator bean              │
│  Input: method args   Output: raw string key                    │
└────────────────────────────┬────────────────────────────────────┘
                             │ raw key
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 2 — FailoverKeyGenerator  (internal, always active)      │
│  Combines: effectiveName + ":" + rawKey                         │
│  Applies:  UUID.nameUUIDFromBytes(combined, UTF-8)              │
│  Output:   fixed-length 36-character UUID string                │
└────────────────────────────┬────────────────────────────────────┘
                             │ final UUID key
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 3 — FailoverStore  (store / find / delete)               │
│  Keyed on: (FAILOVER_NAME, FAILOVER_KEY)                        │
│  FAILOVER_NAME = effectiveName  |  FAILOVER_KEY = UUID above    │
└─────────────────────────────────────────────────────────────────┘
```

**Layer 1** is what users control — either the default argument-based derivation or a custom `KeyGenerator` bean.

**Layer 2 (`FailoverKeyGenerator`)** is always injected by auto-configuration and is invisible to application code. It wraps the raw key from Layer 1 in a deterministic UUID hash, preventing column overflow and obscuring business data in the store.

**Layer 3** stores each entry under a two-part composite key: the `FAILOVER_NAME` and the `FAILOVER_KEY`. Both must match on store and recover for a hit to occur. This is why `domain` (see below) must affect both dimensions.

---

## Effective Name — `failover.name()` vs `effectiveName`

Every store operation uses an **effective name** rather than the raw annotation name directly:

```
effectiveName = domain.isBlank() ? name : domain
```

`FailoverNameResolver.effectiveName(failover)` computes this. Both `FailoverKeyGenerator` (Layer 2) and `DefaultFailoverHandler` (Layer 3) call it, keeping the namespace consistent.

| `@Failover` attribute | `effectiveName` used for UUID prefix | `FAILOVER_NAME` stored |
|---|---|---|
| `name="tp-by-id"`, no domain | `"tp-by-id"` | `"tp-by-id"` |
| `name="tp-list"`, no domain | `"tp-list"` | `"tp-list"` |
| `name="tp-by-id"`, `domain="tp"` | `"tp"` | `"tp"` |
| `name="tp-list"`, `domain="tp"` | `"tp"` | `"tp"` |

When two `@Failover` annotations share the same `domain`, they hash raw keys with the same namespace prefix and store under the same `FAILOVER_NAME`. Given the same raw key from Layer 1, they produce identical store addresses — enabling cross-failover data sharing. See [Domain Sharing](scatter-gather.md#domain-sharing) for the scatter/gather use case.

!!! note "Logging uses `name`, not `effectiveName`"
    Log messages and scanner registration always use `failover.name()` — the unique annotation name — not `effectiveName`. This keeps logs human-readable and distinguishes individual `@Failover` definitions even when they share a domain.

---

## Key Length and Stability

`FailoverKeyGenerator` always produces fixed-length UUID-based (MD5-hashed) keys. This prevents `VARCHAR(256)` overflow on JDBC stores while maintaining uniqueness.

---

## Custom Key Generator

Provide a Spring bean that implements `KeyGenerator` and reference it by name on the annotation.

```java
@Component("regionKeyGenerator")
public class RegionKeyGenerator implements KeyGenerator {

    @Override
    public String key(Failover failover, List<Object> args) {
        // normalise CSV argument order so "1,2,3" and "3,2,1" map to the same key
        String csv = (String) args.get(0);
        String sorted = Arrays.stream(csv.split(","))
                .map(String::trim)
                .sorted()
                .collect(Collectors.joining(","));
        return failover.name() + ":" + sorted;
    }
}
```

```java
@Failover(name = "entities-by-ids", keyGenerator = "regionKeyGenerator")
List<Entity> findByIds(String csvIds);
```

The `keyGenerator` attribute accepts the bean name. The custom generator completely replaces the default — no fallback to `DefaultKeyGenerator`.

---

## Key Lookup at Recovery Time

The same `KeyGenerator` is called with the same `args` at recover time, so the lookup key always matches the stored key. This means the `args` at recover time must produce the same result as at store time.

!!! tip
    If your method arguments are mutable or order-dependent (e.g. a CSV of IDs), normalise them in the key generator to avoid cache misses.
