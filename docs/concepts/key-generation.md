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

## Key Length and Stability

Starting from version 2.x, the key generator produces fixed-length UUID-based (MD5-hashed) keys for arguments that exceed a threshold. This prevents `VARCHAR(256)` overflow on JDBC stores while maintaining uniqueness.

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
