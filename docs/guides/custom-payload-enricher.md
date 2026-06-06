---
icon: material/code-braces
---

# Custom Payload Enricher

`PayloadEnricher` populates metadata fields on domain objects. The default implementation sets `upToDate`, `asOf`, and `metadata` on types that implement `Referential` or `ReferentialAware`.

Override the enricher when:

- You need to set additional fields beyond `upToDate`/`asOf` (e.g. `recoveredFrom`, `sourceVersion`).
- You need to transform or sanitise the payload during recovery.
- You have domain types that implement metadata tracking via a different mechanism.

---

## Implement PayloadEnricher

```java
@Primary
@Component
public class AuditingPayloadEnricher<T> implements PayloadEnricher<T> {

    private final PayloadEnricher<T> delegate;

    public AuditingPayloadEnricher(DefaultPayloadEnricher<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ReferentialPayload<T> enrichOnStore(
            Failover failover, Class<T> clazz, ReferentialPayload<T> payload) {
        // delegate standard enrichment first
        ReferentialPayload<T> enriched = delegate.enrichOnStore(failover, clazz, payload);
        // add custom audit info
        if (enriched.getPayload() instanceof Auditable auditable) {
            auditable.setStoredAt(Instant.now());
        }
        return enriched;
    }

    @Override
    public ReferentialPayload<T> enrichOnRecover(
            Failover failover, Class<T> clazz,
            ReferentialPayload<T> payload, Throwable cause) {
        ReferentialPayload<T> enriched = delegate.enrichOnRecover(failover, clazz, payload, cause);
        if (enriched != null && enriched.getPayload() instanceof Auditable auditable) {
            auditable.setRecoveredDueTo(cause.getClass().getSimpleName());
        }
        return enriched;
    }
}
```

Mark with `@Primary` to replace the default enricher, or declare as `@Bean` — the auto-configuration is `@ConditionalOnMissingBean`.

---

## RecoveredPayloadHandler

For simpler cases — returning a default object instead of `null` when recovery fails — use `RecoveredPayloadHandler` instead of a custom enricher:

```java
@Component
public class DefaultCountryHandler implements RecoveredPayloadHandler {

    @Override
    public <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload) {
        if (payload == null && clazz == Country.class) {
            return clazz.cast(Country.unknown());
        }
        return payload;
    }
}
```

`RecoveredPayloadHandler` is called after the enricher completes. It receives the (possibly `null`) enriched payload and returns the final value to the caller.
