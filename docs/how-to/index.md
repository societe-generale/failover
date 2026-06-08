---
icon: material/book-open-page-variant
---

# How-to Guides

Step-by-step guides for extending Failover with custom behaviour. Every extension point is a Spring `@Bean` — declare your bean and auto-configuration detects it via `@ConditionalOnMissingBean`.

---

| Guide | When you need it |
|---|---|
| [Custom Key Generator](custom-key-generator.md) | Method args are complex objects, need normalisation, or you want composite keys |
| [Custom Expiry Policy](custom-expiry-policy.md) | Business-calendar TTL, payload-driven expiry, or per-service SLA |
| [Exception Policy](exception-policy.md) | Control whether exceptions are rethrown or swallowed on failed recovery |
| [Recovered Payload Handler](recovered-payload-handler.md) | Return empty list / default value instead of null when recovery finds nothing |
| [Custom Payload Enricher](custom-payload-enricher.md) | Inject custom metadata into stored or recovered payloads |
| [Payload Splitter](payload-splitter.md) | Scatter/gather — store collection entries individually for partial recovery |
| [Context Propagation](context-propagation.md) | Propagate MDC / tenant / security context across parallel scatter slices |
| [Payload Column Resolver](payload-column-resolver.md) | Control how the JDBC store serialises/deserialises the `PAYLOAD` column |
| [Database Resolver](database-resolver.md) | Route JDBC operations to different DataSources per tenant |
| [Failover Store Query Resolver](failover-store-query-resolver.md) | Override the SQL queries used by the JDBC store |
| [Observability](observability.md) | Wire Micrometer metrics, health indicators, and custom log levels |

---

## Next Steps

- [Concepts](../concepts/index.md) — understand the core model before extending it
- [Properties Reference](../configuration/properties-reference.md) — all configuration options
