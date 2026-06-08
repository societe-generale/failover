---
icon: material/key-variant
---

# Custom Key Generator

Replace the default argument-based key derivation with your own logic. Use this when method arguments are complex objects, need normalisation, or when you want a composite key from multiple sources.

---

## Interface

```java
public interface KeyGenerator {
    String key(Failover failover, List<Object> args);
}
```

The returned string is further processed by `FailoverKeyGenerator` (Layer 2), which prefixes it with `effectiveName` and converts the combined value to a type-3 UUID. You do not need to handle prefix or hashing yourself.

---

## When to Use

- Method argument is a complex object with no stable `toString()`
- CSV argument order is non-deterministic (`"1,2,3"` vs `"3,2,1"` should map to the same key)
- Key should be derived from a request header or security context, not the method arguments

---

## Step 1 — Implement KeyGenerator

```java title="SortedCsvKeyGenerator.java"
@Component("sortedCsvKeyGenerator")
public class SortedCsvKeyGenerator implements KeyGenerator {

    @Override
    public String key(Failover failover, List<Object> args) {
        String csv = (String) args.get(0);
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .sorted()
                .collect(Collectors.joining(","));
    }
}
```

!!! tip "Be idempotent"
    The key generator is called identically at store time and at recover time. Always produce the same output for the same logical input.

---

## Step 2 — Wire to the Annotation

```java
@Failover(
    name = "entities-by-ids",
    keyGenerator = "sortedCsvKeyGenerator",
    expiryDuration = 6,
    expiryUnit = ChronoUnit.HOURS
)
List<Entity> findByIds(String csvIds);
```

The `keyGenerator` attribute accepts the Spring bean name. The custom generator completely replaces `DefaultKeyGenerator`.

---

## Example: Multi-Arg Composite Key

```java title="UserContextKeyGenerator.java"
@Component("userContextKeyGenerator")
public class UserContextKeyGenerator implements KeyGenerator {

    @Override
    public String key(Failover failover, List<Object> args) {
        String entityId = String.valueOf(args.get(0));
        String region   = String.valueOf(args.get(1));
        return region + ":" + entityId;
    }
}
```

---

## Next Steps

- [Key Generation Concepts](../concepts/key-generation.md) — three-layer architecture explained
- [Domain Grouping](../concepts/domain.md) — how `effectiveName` affects key hashing
