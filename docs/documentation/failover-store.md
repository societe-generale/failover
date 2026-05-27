# Failover Store
---------------

We support various types of failover store as part of this lib. 
1. **INMEMORY** : With basic ConcurrentHashMap implementation. We highly recommend to ***NOT USE*** this in ***Production***
2. **CAFFEINE** : With Caffeine cache implementation.
3. **JDBC** : With Jdbc implementation. This required ***JdbcTemplate*** and ***ObjectMapper*** beans
4. **CUSTOM** : Allows each service to provide a custom store.

You can provide your own Failover store by implementing below interface. 
```java
public interface FailoverStore<T> {
    void store(ReferentialPayload<T> referentialPayload);
    void delete(ReferentialPayload<T> referentialPayload);
    Optional<ReferentialPayload<T>> find(String name, String key);
    void cleanByExpiry(LocalDateTime expiry);
}
```

---

> ## **FailoverStoreInmemory** 
To use inmemory failover store , you must provide the below configurations
```yaml
failover:
  store:
    type: inmemory
```
> NOTE : Please DO NOT use this for production!!!

---

> ## **FailoverStoreCaffeine**
To use Caffeine failover store , you must provide the below configurations
```yaml
failover:
  store:
    type: caffeine
```

> ## **FailoverStoreJdbc**
To use jdbc failover store , you need to provide the below configurations
```yaml
failover:
  store:
    type: jdbc
```
The failover information will be stored in **FAILOVER_STORE** table 
as per the above configurations.

Make sure you have the below failover store table created in your database!

```sql
-- Table name should be :  %table-prefix%FAILOVER_STORE 
-- default prefix is empty string
CREATE TABLE FAILOVER_STORE (
     FAILOVER_NAME VARCHAR(50) NOT NULL,
     FAILOVER_KEY VARCHAR(256) NOT NULL,
     AS_OF TIMESTAMP(9) NOT NULL,
     EXPIRE_ON TIMESTAMP(9) NOT NULL,
     PAYLOAD VARCHAR(2000),   -- Provide the maximum size based on your payload
     PAYLOAD_CLASS VARCHAR(256),
     PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

You can further customize the jdbc store.
For more details please see the document [customization.md](customization.md)


> ## **FailoverStoreAsync**
For any custom failover store, if you want to convert them to async, you can wrap the custom failover store with **FailoverStoreAsync**
```java
public class MyConfiguration {
    
    @Bean
    public FailoverStore myCustomFailoverStore() {
        return new FailoverStoreAsync(new CustomFailoverStore());
    }                
}
```