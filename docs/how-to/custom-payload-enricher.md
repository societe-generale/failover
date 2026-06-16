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

## Example: Encode On Store, Decode On Recover

`enrichOnStore` runs **before** the payload is written and `enrichOnRecover` **after** it is read — a
natural pair for **reversible** transformation. Encode sensitive fields on store so the failover store
never holds plaintext at rest, then decode on recover so callers still get the **real value** back
(unlike masking, which is lossy and cannot be reversed). See
[Security → Sensitive data](../support/security.md#sensitive-data-pii-in-failover-stores).

```java title="EncodingPayloadEnricher.java"
@Component
public class EncodingPayloadEnricher<T> implements PayloadEnricher<T> {

    @Override
    public ReferentialPayload<T> enrichOnStore(
            Failover failover, Class<T> clazz, ReferentialPayload<T> payload) {
        if (payload.getPayload() instanceof Client c) {
            c.setAccountNumber(encode(c.getAccountNumber()));   // stored encoded, never plaintext
        }
        return payload;
    }

    @Override
    public ReferentialPayload<T> enrichOnRecover(
            Failover failover, Class<T> clazz,
            @Nullable ReferentialPayload<T> payload, Throwable cause) {
        if (payload != null && payload.getPayload() instanceof Client c) {
            c.setAccountNumber(decode(c.getAccountNumber()));   // caller gets the real value back
        }
        if (payload != null && payload.getPayload() instanceof Referential r) {
            r.setUpToDate(false);
            r.setAsOf(payload.getAsOf());
        }
        return payload;
    }

    private static String encode(String value) {
        return value == null ? null
                : Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return value == null ? null
                : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
```

!!! warning "Base64 is encoding, not encryption"
    Base64 only obscures the value at rest — anyone with the stored bytes can decode it. For real
    confidentiality, replace `encode`/`decode` with authenticated encryption (e.g. AES-GCM) using a
    key from your secrets manager. The store/recover pairing is the same; only the transform changes.

---

## Next Steps

- [Recovered Payload Handler](recovered-payload-handler.md) — handle null recovery result
- [How It Works](../concepts/how-it-works.md) — where enrichment fits in the lifecycle
