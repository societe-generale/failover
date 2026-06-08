---
icon: material/book-open-page-variant
---

# How-to Guides

Step-by-step guides for extending Failover with custom behaviour. Every extension point is a Spring `@Bean` — declare your bean and auto-configuration detects it via `@ConditionalOnMissingBean`.

<div class="grid cards" markdown>

-   :material-key:{ .lg .middle } **Custom Key Generator**

    ---

    Method args are complex objects, need normalisation, or you want composite keys.

    [:octicons-arrow-right-24: Customise keys](custom-key-generator.md)

-   :material-timer-edit-outline:{ .lg .middle } **Custom Expiry Policy**

    ---

    Business-calendar TTL, payload-driven expiry, or per-service SLA.

    [:octicons-arrow-right-24: Customise expiry](custom-expiry-policy.md)

-   :material-alert-circle-outline:{ .lg .middle } **Exception Policy**

    ---

    Control whether exceptions are rethrown or swallowed when recovery finds nothing.

    [:octicons-arrow-right-24: Configure exceptions](exception-policy.md)

-   :material-package-variant:{ .lg .middle } **Recovered Payload Handler**

    ---

    Return an empty list or default value instead of `null` when recovery finds nothing.

    [:octicons-arrow-right-24: Handle null recovery](recovered-payload-handler.md)

-   :material-tag-plus-outline:{ .lg .middle } **Custom Payload Enricher**

    ---

    Inject custom metadata into stored or recovered payloads at interception time.

    [:octicons-arrow-right-24: Enrich payloads](custom-payload-enricher.md)

-   :material-call-split:{ .lg .middle } **Payload Splitter**

    ---

    Scatter/gather — split collection entries into individual store entries for partial recovery.

    [:octicons-arrow-right-24: Configure scatter/gather](payload-splitter.md)

-   :material-transit-connection-variant:{ .lg .middle } **Context Propagation**

    ---

    Propagate MDC, tenant, or security context across parallel scatter slices.

    [:octicons-arrow-right-24: Propagate context](context-propagation.md)

-   :material-database-edit-outline:{ .lg .middle } **Payload Column Resolver**

    ---

    Control how the JDBC store serialises and deserialises the `PAYLOAD` column.

    [:octicons-arrow-right-24: Customise serialisation](payload-column-resolver.md)

-   :material-database-arrow-right-outline:{ .lg .middle } **Database Resolver**

    ---

    Route JDBC operations to different `DataSource` instances per tenant.

    [:octicons-arrow-right-24: Configure routing](database-resolver.md)

-   :material-database-search-outline:{ .lg .middle } **Store Query Resolver**

    ---

    Override the SQL queries used by the JDBC store for custom schemas or dialects.

    [:octicons-arrow-right-24: Override queries](failover-store-query-resolver.md)

-   :material-chart-line:{ .lg .middle } **Observability**

    ---

    Wire Micrometer metrics, health indicators, and custom log levels for your setup.

    [:octicons-arrow-right-24: Add observability](observability.md)

</div>

---

## Next Steps

- [Concepts](../concepts/index.md) — understand the core model before extending it
- [Properties Reference](../configuration/properties-reference.md) — all configuration options
