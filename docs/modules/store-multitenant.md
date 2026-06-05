# Multi-Tenant Store Module

`failover-store-multitenant` adds tenant routing on top of any backing store. Each request is routed to the correct tenant store based on the current thread's tenant ID.

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-multitenant</artifactId>
    <version>3.0.0</version>
</dependency>
```

## Key Classes

### TenantResolver

Implement this interface and declare it as a Spring bean. The framework calls it on every store/find/delete/clean operation to determine the active tenant.

```java
@Component
public class MyTenantResolver implements TenantResolver {
    @Override
    public String resolve() {
        return TenantContext.get();   // return null to fall back to default-tenant
    }
}
```

### TenantContext

Thread-local holder for the current tenant ID. Provided for convenience — you are not required to use it.

```java
TenantContext.set("acme");
try {
    // all failover operations in this scope use tenant "acme"
} finally {
    TenantContext.clear();
}
```

### TenantContextPropagator

A `ContextPropagator` implementation that captures the tenant on the calling thread and restores it on executor threads. Declare it as a Spring bean to enable automatic propagation in async and scatter/gather contexts:

```java
@Bean
public ContextPropagator tenantContextPropagator() {
    return new TenantContextPropagator();
}
```

### MultiTenantFailoverStore

Routes store operations to the appropriate per-tenant `FailoverStore` instance. Auto-configured when `failover.store.multitenant.enabled=true`.

### TenantStoreFactory

Factory that creates a `FailoverStore` per tenant. The auto-configured implementation supports `TABLE_PREFIX` strategy. For `SCHEMA` strategy, provide a custom `TenantStoreFactory` bean:

```java
@Bean
public TenantStoreFactory<Object> tenantStoreFactory(
        Map<String, DataSource> tenantDataSources,
        FailoverProperties props) {
    return tenantId -> {
        DataSource ds = tenantDataSources.get(tenantId);
        String table = props.getStore().getJdbc().getTablePrefix() + "FAILOVER_STORE";
        return new FailoverStoreJdbc<>(new JdbcTemplate(ds), table);
    };
}
```

## Configuration Example

See [Multi-Tenant Configuration](../configuration/multi-tenant.md) for a complete YAML example and strategy comparison.
