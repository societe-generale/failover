---
icon: material/pencil-plus-outline
---

# Custom Payload Enricher

`PayloadEnricher` is called on both the store path and the recover path, letting you inject custom metadata into the `ReferentialPayload` envelope.

---

## Interface

```java
public interface PayloadEnricher<T> {

    ReferentialPayload<T> enrichOnStore(
        Failover failover,
        Class<T> clazz,
        ReferentialPayload<T> payload
    );

    ReferentialPayload<T> enrichOnRecover(
        Failover failover,
        Class<T> clazz,
        @Nullable ReferentialPayload<T> payload,
        Throwable cause
    );
}
```

- `enrichOnStore` — called after the upstream result is wrapped but before it is written to the store.
- `enrichOnRecover` — called after the stored entry is retrieved (or when recovery returns `null`). Sets `upToDate=false` and `asOf` on the payload object.

---

## When to Use

- Inject request-scoped metadata (trace ID, user ID) into stored payloads
- Override `expireOn` based on payload content (e.g. a `validUntil` field)
- Add custom fields to `Metadata` on the returned object

---

## Default Enricher Behaviour

The default `DefaultPayloadEnricher`:

- **On store**: sets `upToDate=true`, `asOf=now()`, `expireOn=computed by ExpiryPolicy`
- **On recover**: sets `upToDate=false`, `asOf=storedAt` on the `Referential` / `ReferentialAware` object; handles `null` payload via `RecoveredPayloadHandler`

---

## Example: Inject Trace ID into Metadata

```java title="TraceEnricher.java"
@Component
public class TraceEnricher<T> implements PayloadEnricher<T> {

    private final Tracer tracer;

    @Override
    public ReferentialPayload<T> enrichOnStore(
            Failover failover, Class<T> clazz, ReferentialPayload<T> payload) {
        // inject current trace ID into metadata
        if (payload.getPayload() instanceof Referential r) {
            r.getMetadata().add("traceId", tracer.currentSpan().context().traceId());
        }
        return payload;
    }

    @Override
    public ReferentialPayload<T> enrichOnRecover(
            Failover failover, Class<T> clazz,
            @Nullable ReferentialPayload<T> payload, Throwable cause) {
        if (payload != null && payload.getPayload() instanceof Referential r) {
            r.setUpToDate(false);
            r.setAsOf(payload.getAsOf());
        }
        return payload;
    }
}
```

---

## Next Steps

- [Recovered Payload Handler](recovered-payload-handler.md) — handle null recovery result
- [How It Works](../concepts/how-it-works.md) — where enrichment fits in the lifecycle
