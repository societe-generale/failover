---
icon: material/book-open-outline
---

# Guides

Step-by-step guides for extending and customising Failover behaviour via its SPI interfaces.

| Guide                                                 | Description                                                                                                  |
|-------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| [Key Generator](custom-key-generator.md)       | Implement `KeyGenerator` to derive store keys from method arguments your own way                             |
| [Expiry Policy](custom-expiry-policy.md)       | Implement `ExpiryPolicy` to compute TTL from the stored payload at runtime                                   |
| [Payload Enricher](custom-payload-enricher.md) | Implement `PayloadEnricher` to attach additional metadata on store and recover                               |
| [Payload Splitter](payload-splitter.md)               | Implement `PayloadSplitter` to scatter collection results into per-entity slices and gather them on recovery |
| [Context Propagation](context-propagation.md)         | Implement `ContextPropagator` to carry thread-local state into async executor threads                        |
| [Reporting](reporting.md)                             | Use `FailoverReporter` to publish store/recover events to custom backends                                    |

Each guide follows the same structure: interface contract → minimal implementation → Spring bean registration.
