---
icon: material/scissors-cutting
---

# Payload Splitter

Implement `PayloadSplitter` to enable scatter/gather mode — stores each entity in a collection individually under its own key and recovers them independently, enabling partial recovery.

---

## Interface

```java
public interface PayloadSplitter<T, R> {
    List<StoreContext<R>> splitOnStore(StoreContext<T> context);
    List<RecoverContext<R>> splitOnRecover(RecoverContext<T> context);
    RecoverContext<T> merge(List<RecoverContext<R>> contexts);
}
```

| Type | Meaning |
|---|---|
| `T` | Composite type — what the annotated method returns (`List<Country>`) |
| `R` | Slice type — what is stored per entity (`Country`) |

---

## Step 1 — Implement PayloadSplitter

```java title="CountrySplitter.java"
@Component("countrySplitter")
public class CountrySplitter implements PayloadSplitter<List<Country>, Country> {

    @Override
    public List<StoreContext<Country>> splitOnStore(StoreContext<List<Country>> ctx) {
        String[] codes = ((String) ctx.getArgs().get(0)).split(",");
        List<Country> countries = ctx.getPayload();

        return IntStream.range(0, countries.size())
            .mapToObj(i -> StoreContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(codes[i].trim()))  // single-code args → key derivation
                .payload(countries.get(i))
                .build())
            .toList();
    }

    @Override
    public List<RecoverContext<Country>> splitOnRecover(RecoverContext<List<Country>> ctx) {
        String csv = (String) ctx.getArgs().get(0);
        return Arrays.stream(csv.split(","))
            .map(code -> RecoverContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(code.trim()))
                .clazz(Country.class)
                .cause(ctx.getCause())
                .build())
            .toList();
    }

    @Override
    public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
        List<Country> recovered = contexts.stream()
            .map(RecoverContext::getPayload)
            .filter(Objects::nonNull)
            .toList();
        return contexts.get(0).toBuilder()
            .clazz((Class) List.class)
            .payload(recovered)
            .build();
    }
}
```

The `args` list in each split `StoreContext` must produce the same key as a direct `findByCode("FR")` call so that domain sharing works correctly.

---

## Step 2 — Wire to the Annotation

```java
@Failover(
    name = "countries-by-codes",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByCodes(@RequestParam String codes);
```

---

## Parallel Dispatch

By default, each slice's store/recover runs on a virtual-thread executor in parallel:

```yaml title="application.yml"
failover:
  scatter:
    parallel: true
```

If your operation uses thread-local state (tenant context, MDC), implement a `ContextPropagator` to propagate it across threads — see [Context Propagation](context-propagation.md).

---

## Next Steps

- [Scatter / Gather Concepts](../concepts/scatter-gather.md) — how the split/merge lifecycle works
- [Domain Grouping](../concepts/domain.md) — sharing store entries across failovers
- [Context Propagation](context-propagation.md) — thread-local context across parallel slices
