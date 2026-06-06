---
icon: material/book-open-outline
---

# Guides

Step-by-step guides for extending and customising Failover behaviour via its SPI interfaces.

| Guide | Description |
|---|---|
| [Custom Key Generator](custom-key-generator.md) | Implement `KeyGenerator` to derive store keys from method arguments your own way |
| [Custom Expiry Policy](custom-expiry-policy.md) | Implement `ExpiryPolicy` to compute TTL from the stored payload at runtime |
| [Custom Payload Enricher](custom-payload-enricher.md) | Implement `PayloadEnricher` to attach additional metadata on store and recover |
| [Context Propagation](context-propagation.md) | Implement `ContextPropagator` to carry thread-local state into async executor threads |
| [Reporting](reporting.md) | Use `FailoverReporter` to publish store/recover events to custom backends |

Each guide follows the same structure: interface contract → minimal implementation → Spring bean registration.
