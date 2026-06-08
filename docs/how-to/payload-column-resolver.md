---
icon: material/table-column
---

# Payload Column Resolver

Controls how the JDBC store serialises and deserialises the `PAYLOAD` column. Override it when the default Jackson JSON serialisation is insufficient — for example, to add type information, use a custom `ObjectMapper`, or store payloads in a different format.

---

## Interface

```java
public interface PayloadColumnResolver<T> {
    String resolve(ReferentialPayload<T> payload);
    T deserialize(String raw, Class<T> clazz);
}
```

- `resolve` — converts the payload to the string written to `PAYLOAD`.
- `deserialize` — converts the `PAYLOAD` string back to `T` on read.

---

## Default Behaviour

The default resolver uses a plain `ObjectMapper` without type information. This works for most use cases but fails when:

- `T` is an interface or abstract class — deserialisation cannot determine the concrete type.
- The payload uses polymorphic types.
- You need a custom `ObjectMapper` (e.g. with JSR-310 module, custom naming strategy).

---

## Step 1 — Implement PayloadColumnResolver

```java title="TypedPayloadColumnResolver.java"
@Component
public class TypedPayloadColumnResolver<T> implements PayloadColumnResolver<T> {

    private final ObjectMapper mapper;

    public TypedPayloadColumnResolver() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .activateDefaultTyping(
                    BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                    ObjectMapper.DefaultTyping.NON_FINAL
                );
    }

    @Override
    public String resolve(ReferentialPayload<T> payload) {
        try {
            return mapper.writeValueAsString(payload.getPayload());
        } catch (JsonProcessingException e) {
            throw new FailoverStoreException("Failed to serialise payload", e);
        }
    }

    @Override
    public T deserialize(String raw, Class<T> clazz) {
        try {
            return mapper.readValue(raw, clazz);
        } catch (JsonProcessingException e) {
            throw new FailoverStoreException("Failed to deserialise payload", e);
        }
    }
}
```

---

## Next Steps

- [Store Types](../configuration/store-types.md) — JDBC store configuration
- [Failover Store Query Resolver](failover-store-query-resolver.md) — override the SQL queries
