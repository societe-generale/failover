# Caffeine Store Module

`failover-store-caffeine` provides an in-process cache-backed `FailoverStore` using [Caffeine](https://github.com/ben-manes/caffeine). Entries survive method failures but are lost on application restart.

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-caffeine</artifactId>
    <version>3.0.0</version>
</dependency>
```

Also requires Caffeine and Spring Cache on the classpath:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

## Configuration

```yaml
failover:
  store:
    type: caffeine
```

Caffeine eviction settings are configured via Spring Boot's standard cache properties:

```yaml
spring:
  cache:
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=2h
```

!!! note
    Caffeine's own TTL and the Failover expiry policy are independent. Failover checks its own `EXPIRE_ON` field on every recovery. Set Caffeine's `expireAfterWrite` to be longer than your longest `@Failover` expiry to avoid premature eviction.

## When to Use

| Scenario | Recommendation |
|---|---|
| Single-instance application | Caffeine is a good fit |
| Multi-instance application | Use JDBC — Caffeine does not share state across instances |
| No database available | Caffeine is the best persistent-within-process option |
| Test / dev environment | InMemory is simpler; Caffeine if you need realistic eviction behaviour |
