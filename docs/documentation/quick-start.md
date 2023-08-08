# Quick Start
-------------

> ## Maven Dependency

You can configure the failover module with your project by adding the below starter dependency and the configurations
 
```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-spring-boot-starter</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
```

> ## Configuration

You can configure the failover module with the below configurations
```yaml
failover:
  enabled: true  #default is true
```

You can configure the failover module with jdbc store with the below configurations

```yaml
failover:
  enabled: true
  package-to-scan: <your base package>   #A mandatory field to mention your base package where @Failover annotations are present.
  type: basic
  store:
    type: jdbc
    jdbc:
      table-prefix: DEMO_       #table prefix for jdbc store
  scheduler:
    report-cron: 0 0 0 * * *    #default is daily
    cleanup-cron: 0 0 * * * *   #default is hourly
```

For jdbc failover store, Make sure you have the below failover store table created in your database!

```sql
-- Table name should be :  %table-prefix%FAILOVER_STORE 
CREATE TABLE DEMO_FAILOVER_STORE (
     FAILOVER_NAME VARCHAR(50) NOT NULL,
     FAILOVER_KEY VARCHAR(256) NOT NULL,
     AS_OF TIMESTAMP(9) NOT NULL,
     EXPIRE_ON TIMESTAMP(9) NOT NULL,
     PAYLOAD VARCHAR(1000),
     PAYLOAD_CLASS VARCHAR(256),
     PRIMARY KEY(FAILOVER_NAME, FAILOVER_KEY)
);
```

> ## Code Configuration

### Failover Configuration

- Add the **failover-domain** dependency to your project

```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-domain</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
```

- Add the failover annotations (**@Failover**) to your referential caller
- Specify the **expiry** by **consulting with business**

```java
@FeignClient(value = "client", url = "http://localhost:9090")
public interface FeignClientReferential extends ClientReferential {

    @Failover(name = "client-by-id", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES)   // Failover configuration
    @GetMapping(value = "/api/v1/clients/{id}", produces = "application/json")
    @Override
    Client findClientById(@PathVariable("id")Long id);



    @Failover(name = "client-all", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES)    // Failover configuration
    @GetMapping(value = "/api/v1/clients", produces = "application/json")
    @Override
    List<Client> findAllClients();
}

```

### Failover Metadata configuration 

If you need the failover metadata ( isUpToDate , asOf ) part of your referential objects, you can follow below configures ( Its optioanl ) 

if you follow hexagonal architecture, you can use below dependency for getting the **failover annotation** dependencies:

```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-domain</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
```

* For failover metadata part of your referential, please extend the referential object with **Referential** class  or implement the **ReferentialAware** interface 


- Metadata by extending the referential object with **Referential** class

```java
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
public class Client extends Referential {
    private Long id;
    private String name;
    private Integer score;
    public Client(long id, String name, int score) {
        this.id = id;
        this.name = name;
        this. score = score;
    }
}
```
- Metadata by implementing the referential object with **ReferentialAware** interface

```java
@Data
@AllArgsConstructor
public class Client implements ReferentialAware {
    private Long id;
    private String name;
    private int score;

    private Boolean isUpToDate;
    private LocalDateTime asOf;

    public Client(Long id, String name, int score) {
        this.id = id;
        this.name = name;
        this.score = score;
    }

    @Override
    public void setUpToDate(Boolean upToDate) {
        this.isUpToDate = upToDate;
    }

    @Override
    public void setAsOf(LocalDateTime asOf) {
        this.asOf = asOf;
    }
}

``` 

### Failover with Cacheable 

You can use @Failover with @Cacheable. There will be two scenarios where you could use both these annotations.

1. **@Cacheable for caching the remote call & failover recovery call**  
If you use @Cacheable, by default @Cacheable aspect has the highest order and hence this will apply on top of all other annotations.

```java
@FeignClient(value = "client", url = "http://localhost:9090")
public interface FeignClientReferential extends ClientReferential {

    @Cacheable(value = "client") // Cache the result ( in both the case : On successful remote call OR On failure of remote call with failover recovery )
    @Failover(name = "client-by-id", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES)  // Failover configuration
    @GetMapping(value = "/api/v1/clients/{id}", produces = "application/json")
    @Override
    Client findClientById(@PathVariable("id")Long id);
}
```

2. **@Cacheable for caching only the remote call , not the failover recovery**  
If you want to cache the result only for the successful remote call, then you can configure the @Cacheable with unless option as below :  
> NOTE : This will work only if you extend the referential with Referential or implement ReferentialAware on the returned object

```java
@FeignClient(value = "client", url = "http://localhost:9090")
public interface FeignClientReferential extends ClientReferential {

    @Cacheable(value = "client", unless = "#result == null or #result.upToDate == false") // Cache the result only when the remote call is success and the result is not null ( This will work only if you extend the referential with Referential or implement ReferentialAware on the returned object )
    @Failover(name = "client-by-id", expiryDuration = 1, expiryUnit = ChronoUnit.MINUTES)  // Failover configuration
    @GetMapping(value = "/api/v1/clients/{id}", produces = "application/json")
    @Override
    Client findClientById(@PathVariable("id")Long id);
}
```
