---
icon: material/book-open-outline
---

# Guides

Step-by-step guides for extending and customising Failover behaviour via its SPI interfaces.

| Guide                                                             | Description                                                                                                           |
|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| [Key Generator](custom-key-generator.md)                          | Implement `KeyGenerator` to derive store keys from method arguments your own way                                      |
| [Expiry Policy](custom-expiry-policy.md)                          | Implement `ExpiryPolicy` to compute TTL from the stored payload at runtime                                            |
| [Exception Policy](exception-policy.md)                           | Implement `MethodExceptionPolicy` to control rethrow behaviour after recovery is attempted                            |
| [Recovered Payload Handler](recovered-payload-handler.md)         | Implement `RecoveredPayloadHandler` to enrich, transform, or default the recovered value before it reaches the caller |
| [Payload Enricher](custom-payload-enricher.md)                    | Implement `PayloadEnricher` to attach additional metadata on store and recover                                        |
| [Payload Splitter](payload-splitter.md)                           | Implement `PayloadSplitter` to scatter collection results into per-entity slices and gather them on recovery          |
| [Context Propagation](context-propagation.md)                     | Implement `ContextPropagator` to carry thread-local state into async executor threads                                 |
| [Payload Column Resolver](payload-column-resolver.md)             | Implement `PayloadColumnResolver` to map the `PAYLOAD` column to `CLOB`, `TEXT`, or `JSONB` types                     |
| [Database Resolver](database-resolver.md)                         | Implement `DatabaseResolver` to control which SQL merge dialect the JDBC store selects                                |
| [Failover Store Query Resolver](failover-store-query-resolver.md) | Implement `FailoverStoreQueryResolver` to customise all JDBC SQL, column layout, and parameter binding                |
| [Observability](reporting.md)                                     | Use `ObservablePublisher` to publish failover metrics to custom backends                                              |

Each guide follows the same structure: interface contract → minimal implementation → Spring bean registration.
