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

| Type | Meaning                                                              |
|------|----------------------------------------------------------------------|
| `T`  | Composite type — what the annotated method returns (`List<Country>`) |
| `R`  | Slice type — what is stored per entity (`Country`)                   |

---

## Two ways to implement

| Approach                                                  | Use when                                                                                                                                                |
|-----------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Extend `AbstractListPayloadSplitter<T>`** (recommended) | Method returns `List<T>`. You implement only `keyArgsForSlice` (the slice key); for id-based methods also override `keyArgsToRecover`.         |
| **Extend `AbstractPayloadSplitter<T, R>`**                | Composite is not a `List` (e.g. a wrapper object holding the collection). You control the store split and merge but still skip the context plumbing.    |
| **Implement `PayloadSplitter<T, R>` directly**            | You need full control of `splitOnStore` / `splitOnRecover` / `merge` — see [Implementing the Interface Directly](#implementing-the-interface-directly). |

---

## Base Classes — `AbstractListPayloadSplitter` / `AbstractPayloadSplitter`

`AbstractListPayloadSplitter<T>` collapses a full splitter down to the one thing that differs per use
case: **the slice key**. It fixes `T = List<T>`, `R = T` and supplies working defaults:

```java
public abstract class AbstractListPayloadSplitter<T>
        extends AbstractPayloadSplitter<List<T>, T> { ... }
```

| Hook                                       | Default                                             | Override when                          |
|--------------------------------------------|-----------------------------------------------------|----------------------------------------|
| `keyArgsForSlice(slice, ctx)`                  | **abstract**                                        | always — the slice key                 |
| `splitIntoSlices(list)`              | identity — each element is a slice                  | almost never                           |
| `keyArgsToRecover(args, ctx)` | single group `List.of(args)` — **`findAll()` only** | every id-based method                  |
| `mergeSlices(payloads, args)`    | slices as-is (nulls kept), args flattened           | to dedup / drop nulls / reject partial |

`merge` returns the merged result via a small [`MergeResult<T>`](../reference/javadocs.md) value object
(`payload` + aggregated `args`).

!!! warning "The slice-key contract"
    Args from `keyArgsForSlice` (store) and from `keyArgsToRecover` (recover) **must derive
    the same store key for the same entity**. If they diverge, a slice is stored under one key and
    looked up under another, so recovery silently returns nothing.

The entity used in every example below — slice key is always the id:

```java
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
public class ThirdParty extends Referential {
    private Long id;
    private String name;
    private int score;
}
```

### Scenario 1 — `findAll()` with zero args

No args to split. The default `keyArgsToRecover` returns a single placeholder group, so
the delegate recovers **all** slices by name. Implement **only** `keyArgsForSlice`.

```java title="ThirdPartyListSplitter.java"
@Component("thirdPartyListSplitter")
public class ThirdPartyListSplitter extends AbstractListPayloadSplitter<ThirdParty> {

    public ThirdPartyListSplitter() {
        super(ThirdParty.class);
    }

    @Override
    protected List<Object> keyArgsForSlice(ThirdParty payload, StoreContext<List<ThirdParty>> context) {
        return List.of(payload.getId());   // slice key = id
    }
}
```

```java
@Failover(
    name = "all-third-parties",
    domain = "third-party",
    payloadSplitter = "thirdPartyListSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<ThirdParty> findAll();
```

No `recoverAll = true` needed — empty args take the recover-all path automatically.

!!! warning "Read args defensively"
    `keyArgsToRecover` runs on the recovery path. Never blindly call `args.get(0)` or cast
    it — that throws `IndexOutOfBoundsException` on empty args and `NullPointerException` /
    `ClassCastException` on a null or unexpected arg, and the splitter then fails for *every* recovery.
    Instead guard with the `instanceof` pattern (handles null and wrong type in one check) and **return
    an empty list when there is nothing to recover** — the framework treats that as "no slices",
    logs a warning, and skips recovery rather than throwing. The examples below follow this idiom.

### Scenario 2 — `findAllByIdsIn(List<Long> ids)`

`args.get(0)` is the `List<Long>`. Override `keyArgsToRecover` to emit **one group per
id** so each id recovers independently (true partial recovery).

```java title="ThirdPartyByIdsSplitter.java"
@Component("thirdPartyByIdsSplitter")
public class ThirdPartyByIdsSplitter extends AbstractListPayloadSplitter<ThirdParty> {

    public ThirdPartyByIdsSplitter() {
        super(ThirdParty.class);
    }

    @Override
    protected List<Object> keyArgsForSlice(ThirdParty payload, StoreContext<List<ThirdParty>> context) {
        return List.of(payload.getId());
    }

    @Override
    protected List<List<Object>> keyArgsToRecover(
            List<Object> args, RecoverContext<List<ThirdParty>> context) {
        // guard: empty args, null arg, or wrong type → nothing to recover (no throw)
        if (args.isEmpty() || !(args.get(0) instanceof List<?> ids) || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)              // skip null ids
                .map(id -> List.<Object>of(id))        // one group per id → key matches keyArgsForSlice
                .toList();
    }
}
```

```java
@Failover(
    name = "third-parties-by-ids",
    domain = "third-party",
    payloadSplitter = "thirdPartyByIdsSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<ThirdParty> findAllByIdsIn(List<Long> ids);
```

Recovering `[1, 2, 3]` issues three independent lookups; if id `2` is missing, the default merge keeps
a `null` at that position (override `mergeSlices` to drop it — see [Overriding the merge](#overriding-the-merge-with-the-base-classes)).

### Scenario 3 — `findAllByIdsInAndActiveAndRegion(List<Long> ids, Boolean active, String region)`

Same as Scenario 2 plus filter args. `active` / `region` are **filters, not entity identity** — keep
the slice key as the id and **ignore** the filters on recover.

```java title="ThirdPartyByIdsAndFiltersSplitter.java"
@Component("thirdPartyByIdsAndFiltersSplitter")
public class ThirdPartyByIdsAndFiltersSplitter extends AbstractListPayloadSplitter<ThirdParty> {

    public ThirdPartyByIdsAndFiltersSplitter() {
        super(ThirdParty.class);
    }

    @Override
    protected List<Object> keyArgsForSlice(ThirdParty payload, StoreContext<List<ThirdParty>> context) {
        return List.of(payload.getId());   // key = id only; filters are NOT part of the key
    }

    @Override
    protected List<List<Object>> keyArgsToRecover(
            List<Object> args, RecoverContext<List<ThirdParty>> context) {
        // args.get(1)=active, args.get(2)=region are filters — ignored for the key
        if (args.isEmpty() || !(args.get(0) instanceof List<?> ids) || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .map(id -> List.<Object>of(id))
                .toList();
    }
}
```

```java
@Failover(
    name = "third-parties-by-ids-filtered",
    domain = "third-party",
    payloadSplitter = "thirdPartyByIdsAndFiltersSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<ThirdParty> findAllByIdsInAndActiveAndRegion(List<Long> ids, Boolean active, String region);
```

!!! tip "Why drop the filters from the key?"
    Keying by id only lets this method share store entries with `findAll()` and `findAllByIdsIn` under
    `domain = "third-party"`. Keying by `id + active + region` would store the same entity under many
    keys and recovery from a different filter combination would miss. Include a filter in the key only
    if it genuinely produces a *different stored value* for the same id.

### Scenario 4 — `findAllByStringIdsIn(String commaSeparatedIds)`

`args.get(0)` is a CSV like `"1,2,3"`. Split it, then emit one group per id parsed to the type the key
generator expects (`Long`, matching `payload.getId()`).

```java title="ThirdPartyByStringIdsSplitter.java"
@Component("thirdPartyByStringIdsSplitter")
public class ThirdPartyByStringIdsSplitter extends AbstractListPayloadSplitter<ThirdParty> {

    public ThirdPartyByStringIdsSplitter() {
        super(ThirdParty.class);
    }

    @Override
    protected List<Object> keyArgsForSlice(ThirdParty payload, StoreContext<List<ThirdParty>> context) {
        return List.of(payload.getId());
    }

    @Override
    protected List<List<Object>> keyArgsToRecover(
            List<Object> args, RecoverContext<List<ThirdParty>> context) {
        // guard: empty args, null arg, wrong type, or blank CSV → nothing to recover
        if (args.isEmpty() || !(args.get(0) instanceof String csv) || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())    // skip empty tokens ("1,,2")
                .map(Long::valueOf)                   // parse to Long so the key matches keyArgsForSlice
                .map(id -> List.<Object>of(id))
                .toList();
    }
}
```

```java
@Failover(
    name = "third-parties-by-string-ids",
    domain = "third-party",
    payloadSplitter = "thirdPartyByStringIdsSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<ThirdParty> findAllByStringIdsIn(String commaSeparatedIds);
```

!!! warning "Parse the CSV to the stored key type"
    On store the key is `List.of(payload.getId())` — a `Long`. Leaving CSV tokens as `String`
    (`List.of("1")`) makes the recover key differ from the stored key. Parse to `Long`. A malformed
    token makes `Long::valueOf` throw `NumberFormatException`; that propagates as a
    `PayloadSplitterExecutionException` which the failover boundary catches and logs (recovery yields
    nothing — the caller is never crashed). If you prefer to skip bad tokens instead, parse leniently
    (e.g. filter on a `\\d+` regex before `Long::valueOf`).

### Scenario 5 — `findAllByStringIdsInAndActiveAndRegion(String commaSeparatedIds, Boolean active, String region)`

CSV ids plus filters: combine Scenario 4 (split the CSV) with Scenario 3 (ignore the filters).

```java title="ThirdPartyByStringIdsAndFiltersSplitter.java"
@Component("thirdPartyByStringIdsAndFiltersSplitter")
public class ThirdPartyByStringIdsAndFiltersSplitter extends AbstractListPayloadSplitter<ThirdParty> {

    public ThirdPartyByStringIdsAndFiltersSplitter() {
        super(ThirdParty.class);
    }

    @Override
    protected List<Object> keyArgsForSlice(ThirdParty payload, StoreContext<List<ThirdParty>> context) {
        return List.of(payload.getId());
    }

    @Override
    protected List<List<Object>> keyArgsToRecover(
            List<Object> args, RecoverContext<List<ThirdParty>> context) {
        // args.get(1)=active, args.get(2)=region are filters — ignored for the key
        if (args.isEmpty() || !(args.get(0) instanceof String csv) || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(Long::valueOf)
                .map(id -> List.<Object>of(id))
                .toList();
    }
}
```

```java
@Failover(
    name = "third-parties-by-string-ids-filtered",
    domain = "third-party",
    payloadSplitter = "thirdPartyByStringIdsAndFiltersSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<ThirdParty> findAllByStringIdsInAndActiveAndRegion(
        String commaSeparatedIds, Boolean active, String region);
```

### Cheat sheet

| Scenario | Method                                        | Override `keyArgsToRecover`? | Recover-key source                        |
|----------|-----------------------------------------------|-------------------------------------------|-------------------------------------------|
| 1        | `findAll()`                                   | No (default single group)                 | — (recover all by name)                   |
| 2        | `findAllByIdsIn(List<Long>)`                  | Yes                                       | `args.get(0)` (the id list)               |
| 3        | `findAllByIdsInAndActiveAndRegion(...)`       | Yes                                       | `args.get(0)`; filters ignored            |
| 4        | `findAllByStringIdsIn(String)`                | Yes                                       | CSV `args.get(0)` parsed to `Long`        |
| 5        | `findAllByStringIdsInAndActiveAndRegion(...)` | Yes                                       | CSV `args.get(0)` parsed; filters ignored |

In all five `keyArgsForSlice` is identical (`List.of(payload.getId())`) and all share
`domain = "third-party"`, so a successful call on any of them populates the store entries the others
recover from.

### `AbstractPayloadSplitter` — non-`List` composite

When the method returns a wrapper (not a bare `List`), extend `AbstractPayloadSplitter<T, R>` and
implement all four hooks — including `splitIntoSlices` to pull the collection out of the wrapper
and `mergeSlices` to put it back.

```java title="ThirdPartiesResult.java"
@Data
@EqualsAndHashCode(callSuper = true)
public class ThirdPartiesResult extends Referential {
    private List<ThirdParty> thirdParties;
}
```

```java title="ThirdPartiesResultSplitter.java"
@Component("thirdPartiesResultSplitter")
public class ThirdPartiesResultSplitter
        extends AbstractPayloadSplitter<ThirdPartiesResult, ThirdParty> {

    public ThirdPartiesResultSplitter() {
        super(ThirdPartiesResult.class, ThirdParty.class);
    }

    @Override
    protected List<Object> keyArgsForSlice(ThirdParty payload, StoreContext<ThirdPartiesResult> ctx) {
        return List.of(payload.getId());
    }

    @Override
    protected List<ThirdParty> splitIntoSlices(ThirdPartiesResult payload) {
        // unwrap the collection; tolerate a null wrapper or null inner list
        if (payload == null || payload.getThirdParties() == null) {
            return List.of();
        }
        return payload.getThirdParties().stream().filter(Objects::nonNull).toList();
    }

    @Override
    protected List<List<Object>> keyArgsToRecover(
            List<Object> args, RecoverContext<ThirdPartiesResult> ctx) {
        return List.of(args);                        // findAll-style; override per scenario as above
    }

    @Override
    protected MergeResult<ThirdPartiesResult> mergeSlices(
            List<ThirdParty> payloads, List<List<Object>> args) {
        var result = new ThirdPartiesResult();
        result.setThirdParties(payloads.stream().filter(Objects::nonNull).toList());  // re-wrap
        return MergeResult.<ThirdPartiesResult>builder()
                .payload(result)
                .args(args.stream().flatMap(Collection::stream).toList())
                .build();
    }
}
```

### Overriding the merge with the base classes

The default `mergeSlices` keeps slice payloads as-is, including `null`s for missing slices.
To drop missing slices, deduplicate, or reject a partial recovery, override it and return a
`MergeResult`:

```java
@Override
protected MergeResult<List<ThirdParty>> mergeSlices(
        List<ThirdParty> payloads, List<List<Object>> args) {
    List<ThirdParty> recovered = payloads.stream()
            .filter(Objects::nonNull)          // drop missing slices
            .toList();
    return MergeResult.<List<ThirdParty>>builder()
            .payload(recovered)
            .args(args.stream().flatMap(Collection::stream).toList())
            .build();
}
```

The trade-offs (keep positional nulls vs. compact vs. reject-on-any-miss) are the same as for the raw
interface — see [Partial Recovery — Null Policy](#partial-recovery-null-policy-in-merge).

---

## Implementing the Interface Directly

When you need full control — or the composite/key shape does not fit the base classes — implement
`PayloadSplitter<T, R>` yourself. The example below uses a `Country` keyed by string `code`.

### Step 1 — Implement PayloadSplitter

```java title="CountrySplitter.java"
@Component("countrySplitter")
public class CountrySplitter implements PayloadSplitter<List<Country>, Country> {

    @Override
    public List<StoreContext<Country>> splitOnStore(StoreContext<List<Country>> ctx) {
        List<Country> countries = ctx.getPayload();
        if (countries == null || countries.isEmpty()) {
            return List.of();
        }
        // Derive the key from each ENTITY, never by zipping with the input CSV by index:
        // the result may be a different size or order than the requested codes.
        return countries.stream()
            .filter(Objects::nonNull)
            .map(country -> StoreContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(country.getCode()))   // single-code args → key derivation
                .payload(country)
                .build())
            .toList();
    }

    @Override
    public List<RecoverContext<Country>> splitOnRecover(RecoverContext<List<Country>> ctx) {
        List<Object> args = ctx.getArgs();
        // guard: empty args, null arg, wrong type, or blank CSV → nothing to recover (no throw)
        if (args.isEmpty() || !(args.get(0) instanceof String csv) || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(code -> !code.isEmpty())
            .map(code -> RecoverContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(code))
                .clazz(Country.class)
                .cause(ctx.getCause())
                .build())
            .toList();
    }

    @Override
    public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
        // The framework never calls merge with an empty list, but guard anyway.
        if (contexts.isEmpty()) {
            return RecoverContext.<List<Country>>builder()
                .clazz((Class) List.class)
                .payload(List.of())
                .build();
        }
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

The `args` list in each split `StoreContext` must produce the same key as a direct `findByCode("FR")`
call so that domain sharing works correctly. Deriving it from `country.getCode()` (not the positional
CSV token) keeps store and recover keys aligned even when the upstream returns fewer, extra, or
reordered entries.

---

### Step 2 — Wire to the Annotation

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

## Partial Recovery — Null Policy in `merge`

When several keys are recovered at once (e.g. `findByCodes("FR,DE,US")`), some keys may have a stored
entry and others may not — a **partial recovery**. Each unrecovered slice arrives at `merge` as a
`RecoverContext` with a **`null` payload** (a cache miss, an expired entry, and a timed-out slice are
all indistinguishable here — all `null`). The framework does **not** decide what to do with those
nulls; **your `merge` implementation owns the policy**. There are two sensible choices, and the right
one depends on whether the caller needs to know *which* entries are missing.

### How the slices line up

`splitOnRecover` returns one `RecoverContext` per key, in order; the gather recovers each independently
and passes the **same-ordered** list to `merge` — slot *i* in `merge`'s input corresponds to key *i*
from `splitOnRecover`. So position carries meaning: index 1 is always `"DE"`'s result, recovered or not.

```
splitOnRecover("FR,DE,US") → [ ctx(FR), ctx(DE), ctx(US) ]
recover each slice          →   FR=hit   DE=miss   US=hit
merge receives              → [ ctx(FR, payload=fr), ctx(DE, payload=null), ctx(US, payload=us) ]
```

### Option A — Keep `null` at the missing positions (positional)

Preserve the slot so the caller can see *exactly which* keys are missing. The returned list stays the
same length and order as the requested keys.

```java
@Override
public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
    List<Country> recovered = contexts.stream()
        .map(RecoverContext::getPayload)   // keep nulls — null at index i = key i not recovered
        .toList();
    return contexts.get(0).toBuilder()
        .clazz((Class) List.class)
        .payload(recovered)                // e.g. [Country(FR), null, Country(US)]
        .build();
}
```

Use when the caller maps results back to the requested keys by index (e.g. `codes[i] → result[i]`)
and must distinguish "missing" from "present". The caller **must** be null-tolerant.

### Option B — Drop the `null`s (compact)

Return only what was recovered. The list is shorter than the requested keys; positional mapping is
lost.

```java
@Override
public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
    List<Country> recovered = contexts.stream()
        .map(RecoverContext::getPayload)
        .filter(Objects::nonNull)          // drop missing slices
        .toList();
    return contexts.get(0).toBuilder()
        .clazz((Class) List.class)
        .payload(recovered)                // e.g. [Country(FR), Country(US)]
        .build();
}
```

Use when the caller just wants "whatever is available" and does not correlate results to input
positions. This is what the [Step 1 example](#step-1-implement-payloadsplitter) does.

### Option C — Reject the whole composite on any miss

If a partial list is unsafe (the caller cannot tell it is incomplete and might act on it), return a
`null` payload when **any** slice is missing, so the whole recovery is treated as a non-recovery
(subject to your [`ExceptionPolicy`](exception-policy.md)) rather than silently returning a short list:

```java
@Override
public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
    boolean anyMissing = contexts.stream().anyMatch(c -> c.getPayload() == null);
    if (anyMissing) {
        return contexts.get(0).toBuilder().clazz((Class) List.class).payload(null).build();
    }
    List<Country> all = contexts.stream().map(RecoverContext::getPayload).toList();
    return contexts.get(0).toBuilder().clazz((Class) List.class).payload(all).build();
}
```

!!! note "The framework only signals partial recovery — it does not decide"
    The gather logs a `WARN` ("PARTIAL recovery, N of M slices missing") so it is never silent, but the
    *policy* is yours: keep positional nulls (A), compact (B), or reject (C). Pick the one that matches
    how your callers consume the list. If **every** slice is empty, `merge` is not called at all — the
    recovery returns `null` directly. See [Scatter/Gather — Partial Recovery Behaviour](../concepts/scatter-gather.md#partial-recovery-behaviour).

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

## findAll() Splitter — No-ID-Args Pattern

When the annotated method has no arguments (a pure `findAll()`), standard scatter/gather cannot split args into per-entity keys. Use a **dedicated splitter** for this case whose `splitOnRecover` returns a single placeholder context pointing at the slice class.

### Implementation

```java title="CountryAllSplitter.java"
@Component("countryAllSplitter")
public class CountryAllSplitter implements PayloadSplitter<List<Country>, Country> {

    // Store path: identical to CountrySplitter — split the result into per-entity slices
    @Override
    public List<StoreContext<Country>> splitOnStore(StoreContext<List<Country>> ctx) {
        List<Country> countries = ctx.getPayload();
        if (countries == null || countries.isEmpty()) {
            return List.of();
        }
        return countries.stream()
            .filter(Objects::nonNull)
            .map(country -> StoreContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(country.getCode()))   // entity-identity key
                .payload(country)
                .build())
            .toList();
    }

    // Recover path: return ONE placeholder — args are not entity IDs here
    @Override
    public List<RecoverContext<Country>> splitOnRecover(RecoverContext<List<Country>> ctx) {
        return List.of(RecoverContext.<Country>builder()
            .failover(ctx.getFailover())
            .args(ctx.getArgs())           // pass-through (empty for findAll)
            .clazz(Country.class)          // REQUIRED — tells delegateR what type to recover
            .cause(ctx.getCause())
            .build());
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

### Wire to the Annotation

```java
@Failover(
    name = "all-countries",
    domain = "country",
    payloadSplitter = "countryAllSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findAll();
```

No `recoverAll = true` needed — empty args trigger the recover-all path automatically.

---

## recoverAll = true — Filter-Args Pattern

When the method has arguments that are **filters** (not entity IDs), add `recoverAll = true` to force the recover-all path even though args are non-empty.

```java
@Failover(
    name = "countries-by-status",
    domain = "country",
    payloadSplitter = "countryAllSplitter",   // same as findAll — ignores args on recover
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS,
    recoverAll = true
)
List<Country> findByStatus(String status, String region);
```

`CountryAllSplitter.splitOnRecover` receives `args = ["active", "EU"]`. It ignores them and returns the single placeholder. `DefaultFailoverHandler.recoverAll` fetches all slices by name under `domain = "country"`.

!!! tip
    Do NOT reuse the ID-based `countrySplitter` here — its `splitOnRecover` reads `args.get(0)` as a CSV of entity IDs. With filter args that splitter would produce wrong keys and recover nothing.

---

## Two-Splitter Pattern — Batch Fetch + findAll on the Same Domain

Share store entries between a batch endpoint and a `findAll` endpoint by assigning both to the same `domain`:

```java
// Batch by IDs: splits CSV into per-entity keys
@Failover(
    name = "countries-by-ids",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByIds(String csvIds);

// FindAll: no args, uses a splitter whose splitOnRecover returns one placeholder
@Failover(
    name = "all-countries",
    domain = "country",
    payloadSplitter = "countryAllSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findAll();
```

A successful `findByIds("FR,DE,US")` stores three slices. On failure, `findAll()` recovers all three from the same `"country"` store partition.

---

## Splitter failures stay inside the failover boundary

You do **not** need to catch anything. Any exception thrown inside `splitOnStore`,
`splitOnRecover`, or `merge` is wrapped in `PayloadSplitterExecutionException` and handled entirely
within the failover boundary — it never propagates to the caller of the annotated method:

- **On store** (success path): the exception is caught and logged at `ERROR`
  (`"Ignoring Failover Exception !! ... 'store' ..."`); the real business result is returned unchanged.
  A splitter bug never breaks a working upstream call.
- **On recover/merge** (failure path): the exception is caught and logged at `ERROR`
  (`"Ignoring Failover Exception !! ... 'recover' ..."`); recovery yields `null`, which is then passed
  to your [`RecoveredPayloadHandler`](recovered-payload-handler.md) and finally to the
  [`ExceptionPolicy`](exception-policy.md) — exactly as a normal "nothing recovered" outcome.

The wrapped exception carries full diagnostic context — splitter name, operation
(`splitOnStore` / `splitOnRecover` / `merge`), failover name, expiry config, domain, and the original
cause — so a splitter bug is visible in the logs and metrics without a debugger. The end user does not
write any try/catch around it.

---

## Next Steps

- [Annotation Reference](../reference/annotation.md) — `recoverAll`, `domain`, full attribute table
- [Scatter / Gather Concepts](../concepts/scatter-gather.md) — findAll path, dedup in merge, PayloadSplitterExecutionException
- [Domain Grouping](../concepts/domain.md) — sharing store entries across failovers
- [Context Propagation](context-propagation.md) — thread-local context across parallel slices
