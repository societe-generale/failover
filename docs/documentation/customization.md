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

To use Caffeine failover store , you must provide the below configurations
```yaml
failover:
  store:
    type: caffeine
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
