# Custom Key Generator

Use a custom `KeyGenerator` when:

- Method arguments are complex objects without stable `hashCode()`.
- You want argument-order independence (e.g. sorted CSV IDs).
- You need a composite key from multiple arguments with custom logic.

---

## Implement KeyGenerator

```java
@Component("sortedCsvKeyGenerator")
public class SortedCsvKeyGenerator implements KeyGenerator {

    @Override
    public String key(Failover failover, List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "NO-ARG";
        }
        // args[0] is a CSV of entity IDs — normalise order
        String csv = (String) args.get(0);
        String sortedKey = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
        return sortedKey;
    }
}
```

---

## Reference by Bean Name

```java
@Failover(
    name = "entities-by-ids",
    expiryDuration = 1,
    expiryUnit = ChronoUnit.HOURS,
    keyGenerator = "sortedCsvKeyGenerator"
)
List<Entity> findByIds(String csvIds);
```

The `keyGenerator` attribute accepts the Spring bean name. The custom generator completely replaces `DefaultKeyGenerator` for that method — no fallback.

---

## Multi-Argument Keys

When a method has multiple arguments, combine them explicitly:

```java
@Component("statusRegionKeyGenerator")
public class StatusRegionKeyGenerator implements KeyGenerator {

    @Override
    public String key(Failover failover, List<Object> args) {
        String status = (String) args.get(0);
        String region = (String) args.get(1);
        return status + ":" + region;
    }
}
```

---

## Testing

Test the key generator in isolation:

```java
@Test
void same_key_regardless_of_csv_order() {
    KeyGenerator gen = new SortedCsvKeyGenerator();
    Failover failover = mock(Failover.class);

    String key123 = gen.key(failover, List.of("1,2,3"));
    String key321 = gen.key(failover, List.of("3,2,1"));

    assertThat(key123).isEqualTo(key321);
}
```

---

## Default Generator Behaviour

Refer to [Key Generation](../concepts/key-generation.md) for the rules `DefaultKeyGenerator` applies when no custom generator is configured.
