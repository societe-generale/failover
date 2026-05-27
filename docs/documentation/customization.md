# Customization
---------------

> ## Customize Maven Dependency 

> ### 1. **Failover Annotations and Domain**
  
  The basic annotations and domain classes are present in **failover-domain** module. This module has no other dependencies
  
```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-domain</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
```

> ### 2. **Failover Core**  

  The core components of failover library is present in **failover-core** module. This module has all the major components of failover module, and does not have any big frameworks or libs
      
```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-core</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
```  

> ### 3. **Failover Store Inmemory**
This module contains inmemory implementation of failover store. Please do not use this in the production mode.

To use inmemory failover store , you must provide the below configurations
```yaml
failover:
  store:
    type: inmemory
```

The below are the dependency for failover-store-inmemory
```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-store-inmemory</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
```
> NOTE:  Please DO NOT use inmemory failover store in production


> ### 4. **Failover Store Caffeine**  
This module contains Caffeine Cache implementation of failover store.

To use Caffeine failover store , you must provide the below configurations
```yaml
failover:
  store:
    type: caffeine
```

The below are the dependency for failover-store-caffeine
```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-store-caffeine</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>

    <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>-->
        <version> <!-- add latest version --> </version>
    </dependency>
```

> ### 5. **Failover Store Jdbc**
This module contains jdbc implementation of failover store. You must provide the DataSource and JdbcTemplate beans

To use jdbc failover store , you must provide the below configurations
```yaml
failover:
  store:
    type: jdbc
```

The below are the dependency for failover-store-jdbc

```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-store-jdbc</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
     
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>  
    
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>
```

You also need to provide **spring-jdbc** dependency for the same.

We require the ObjectMapper bean from jackson , JdbcTemplate bean from Spring for Failover Store Jdbc

---

> ## Failover Execution
* We have provided below Failover Execution
1. BASIC : Basic failover execution with a simple try catch.

For BASIC failover execution, you must provide the below yml configuration
```yaml
failover:  
  type: basic
```

2. RESILIENCE : failover execution with resilience4j implementation. We highly recommend ***NOT TO CLUB*** this with other resilience or retry solutions.

For RESILIENCE failover execution, you must provide the below yml configuration
```yaml
failover:
  type: resilience
```

And you also need to provide the resilience4j dependency
```pom.xml
       <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
            <version> <!-- add latest version --> </version>
       </dependency>  
```

3. CUSTOM : Allows each service to provide a custom Failover Execution.

For CUSTOM failover execution, you must provide the below yml configuration

```yaml
failover:
  type: custom
```

You must provide a implementation for failover execution.
```java

public interface FailoverExecution<T> {
    T execute(Failover failover, Supplier<T> supplier, Method method, List<Object> args);
}

public class CustomFailoverExecution<Object> implements FailoverExecution<Object> {
    @Override
    public T execute(Failover failover, Supplier<T> supplier, Method method, List<Object> args) {
        //  implementation
    }
}

```

---

> ## ExpiryPolicy
You can provide a custom ExpiryPolicy for managing failover recovery expiry

```java
public interface ExpiryPolicy<T> {

    LocalDateTime computeExpiry(Failover failover);

    boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload);
}
```

By default, we provided a DefaultExpiryPolicy.
```java
public class DefaultExpiryPolicy<T> implements ExpiryPolicy<T> {

    private final FailoverClock clock;

    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        return clock.now().plus(failover.expiryDuration(), failover.expiryUnit());
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload) {
        return clock.now().isAfter(referentialPayload.getExpireOn());
    }
}
```

By default, the **DefaultExpiryPolicy** is used.

In case if you want to override the default expiry policy, please provide an expiry policy bean with name ***"defaultExpiryPolicy"*** 

You can provide a custom expiry policy for each failover if needed by configuring the expiry policy bean name in @Failover annotation as below :

```java
@FeignClient(value = "client", url = "http://localhost:9090")
public interface FeignClientReferential extends ClientReferential {

    // Failover with custom expiry policy
    @Failover(name = "client-by-id", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES, expiryPolicy = "custom-expiry-policy")   // Failover configuration
    @GetMapping(value = "/api/v1/clients/{id}", produces = "application/json")
    @Override
    Client findClientById(@PathVariable("id") Long id);
}
```

```java
// CustomExpiryPolicy implementation
public class CustomExpiryPolicy<T> implements ExpiryPolicy<T> {
    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        // compute expiry logic
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload) {
        // expiry check logic
    }
}

// CustomExpiryPolicy Bean configuration
@Configuration
public class ExpiryPolicyConfigurations {

    // The name must match with the @Failover configuration expiry policy 
    @Bean(name = "custom-expiry-policy")
    public ExpiryPolicy<Object> customExpiryPolicy() {
        return new CustomExpiryPolicy<>();
    }
}
```
In case if we did not find the bean with the given expiry policy name, an exception will be thrown.

---

> ## RecoveredPayloadHandler
You can provide a custom RecoveredPayloadHandler for managing failover recovered payload

```java
public interface RecoveredPayloadHandler {
    <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload);
}
```

* By default, we provided PassThroughRecoveredPayloadHandler which does nothing, just pass through the same data.
```java
public class PassThroughRecoveredPayloadHandler implements RecoveredPayloadHandler {

    @Override
    public <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload) {
        return payload;
    }
}
```
* Each team can plug their own RecoveredPayloadHandler to handle all the recovered data ( in case of returning null or non-null data )

* Example of a custom RecoveredPayloadHandler as below :
```java
    public class CustomRecoveredPayloadHandler implements RecoveredPayloadHandler {
        public <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload) {
            if(payload==null) {
                if(Client.class.isAssignableFrom(clazz)) {
                    Client client = new Client(0L, "NA", 0);
                    client.setUpToDate(false);
                    client.setAsOf(LocalDateTime.now());
                    return (T) client;
                }
            }
            return null;
        }        
    }
```

---
> ## MethodExceptionPolicy

`MethodExceptionPolicy` controls what happens after a primary call fails and failover recovery has been attempted.
The decision point is: _should the original exception be propagated, or should the caller receive the recovered data (or `null`)?_

```java
@FunctionalInterface
public interface MethodExceptionPolicy {
    <T> T handle(MethodExceptionContext<T> context);
}
```

`MethodExceptionContext<T>` carries everything available at the decision point:

```java
public record MethodExceptionContext<T>(
        Failover failover,      // the @Failover annotation
        Method method,          // the intercepted method
        List<Object> args,      // original call arguments
        T recoveredResult,      // null if recovery failed or store was empty
        Throwable cause         // the original exception from the primary call
) {}
```

### Built-in policies

Three implementations are provided out of the box:

#### 1. `RethrowIfNoRecoveryMethodExceptionPolicy` _(default)_
Returns `recoveredResult` when the store had data; rethrows the original exception when recovery produced `null`.

```yaml
# no configuration needed — this is the default when exception-policy is absent
```
or
```yaml
failover:
  exception-policy: rethrow
```

#### 2. `NeverRethrowMethodExceptionPolicy`
Always returns `recoveredResult` or `null`. The original exception is never propagated — useful for pure degraded-mode services.

```yaml
failover:
  exception-policy: never_throw
```

#### 3. Custom policy
Register a `MethodExceptionPolicy` Spring bean. `@ConditionalOnMissingBean` ensures the auto-configured default is skipped.

```yaml
failover:
  exception-policy: custom   # documents intent; the bean presence is what actually matters
```

```java
@Configuration
public class FailoverExceptionPolicyConfig {

    @Bean
    public MethodExceptionPolicy methodExceptionPolicy() {
        return new CustomMethodExceptionPolicy();
    }
}
```

Example: rethrow for unexpected exception types, return recovered data for known transient failures:

```java
public class CustomMethodExceptionPolicy implements MethodExceptionPolicy {

    @Override
    public <T> T handle(MethodExceptionContext<T> context) {
        if (context.recoveredResult() != null) {
            return context.recoveredResult();
        }
        if (context.cause() instanceof TimeoutException) {
            // transient — serve null gracefully
            return null;
        }
        // unexpected failure — propagate
        throw new RuntimeException("Failover: no recovery available for " + context.failover().name(), context.cause());
    }
}
```

> ## Scheduler
We have two schedulers
1. **Report publisher** : This is to publish the failover configuration reports for monitoring. The default value is **daily**
2. **Referential Cleanup** : This is to cleanup the expired referential data from the store. The default value is **hourly**  

However, you can configure these with any cron expressions as below

```yml
failover:
  scheduler:
    report-cron: 0 0 0 * * *    #default is daily
    cleanup-cron: 0 0 * * * *   #default is hourly
```

---

> ## Key Generator

By default, the **DefaultKeyGenerator** is used. 

In case if you want to override the default key generator, please provide a key generatorbean with name "defaultKeyGenerator"


You can provide a custom key generator for each failover if needed by configuring the key generator bean name in @Failover annotation as below: 

```java
@FeignClient(value = "client", url = "http://localhost:9090")
public interface FeignClientReferential extends ClientReferential {

    // Failover with custom key generator
    @Failover(name = "client-by-id", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES, keyGenerator = "custom-key-generator")   // Failover configuration
    @GetMapping(value = "/api/v1/clients/{id}", produces = "application/json")
    @Override
    Client findClientById(@PathVariable("id") Long id);
}
```

```java
// CustomKeyGenerator implementation
public class CustomKeyGenerator implements KeyGenerator {
    @Override
    public String key(Failover failover, List<Object> args) {
        // generate and return the key
    }
}

// CustomKeyGenerator Bean configuration
@Configuration
public class KeyGeneratorConfigurations {

    // The name must match with the @Failover configuration key generator 
    @Bean(name = "custom-key-generator")
    public KeyGenerator customKeyGenerator() {
        return new CustomKeyGenerator();
    }
}
```
In case if we did not find the bean with the given key generator name, an exception will be thrown.  

---

> ## **Customization FailoverStoreJdbc**

> ### **Customization of FailoverStoreJdbc table with prefix**
> 
To use jdbc failover store with custom prefix, you need to provide the below configurations
```yaml
failover:
  store:
    type: jdbc
    jdbc:
      table-prefix: DEMO_
```
The failover information will be stored in **DEMO_FAILOVER_STORE** table as per the above configurations.

Make sure you have the below failover store table created in your database!

```sql
-- Table name should be :  %table-prefix%FAILOVER_STORE 
CREATE TABLE DEMO_FAILOVER_STORE (
     FAILOVER_NAME VARCHAR(50) NOT NULL,
     FAILOVER_KEY VARCHAR(256) NOT NULL,
     AS_OF TIMESTAMP(9) NOT NULL,
     EXPIRE_ON TIMESTAMP(9) NOT NULL,
     PAYLOAD VARCHAR(2000),               -- Provide the maximum size based on your payload
     PAYLOAD_CLASS VARCHAR(256),
     PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

> ### **Customization of payload column in FailoverStoreJdbc**
In case if FailoverStoreJdbc, Most of the time, for a simple referential use case the size of serialized (by JSON) payload will be with in varchar 2000.
However, for more complex use case we may need a higher capacity to hold this data based on the database type we use.
We are introducing **PayloadColumnHandler** to address this issue, now users can customize the payload column based on their needs (ex: to use TEXT or CLOB instead of default VARCHAR(2000))
```java
public interface PayloadColumnHandler {

    /**
     * @return the type of payload column ( refer java.sql.Types class for more details )
     */
    int payloadType();

    /**
     * @param resultSet : result set of payload row
     * @param payloadColumn : payload column name
     * @return : the payload as String from payload column
     */
    String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException;
}
```

we have provided a default implementation to support varchar type as below :
```java
public class VarcharPayloadColumnHandler implements PayloadColumnHandler {

    @Override
    public int payloadType() {
        return Types.VARCHAR;
    }

    @Override
    public String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException {
        return resultSet.getString(payloadColumn);
    }
}
```

> Users can provide their own custom PayloadColumnHandler in case if they choose to use the column type as TEXT or CLOB instead of default VARCHAR
