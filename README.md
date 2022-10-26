![logo](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-icon.png)

# **Failover** <small>***1.0.x***</small>

![CI](https://github.com/societe-generale/failover/actions/workflows/java-maven-ci.yml/badge.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.societegenerale.failover/failover/badge.svg?style=plastic)](https://maven-badges.herokuapp.com/maven-central/com.societegenerale.failover/failover)

> ***Failover library - To manage the failover on referential systems***

**"This library is to help to enable a failover to handle the failures on external services by keeping a local store for such api responses"**

- <small>Support </small>**Failover**<small> needs for your domain services</small>
- <small>Simple to use by simply annotating with</small> **@Failover(name="client-by-id")**
- <small>Support for various failover store</small> **Inmemory**, **Caffeine**, **Jdbc** etc
- <small>Support for various failover execution</small> **Basic**, **Resilience** etc
- <small>Easy to </small>**customize**<small>  and use by providing your own</small> **Expiry Policy**, **Failover Store**, **RecoveredPayloadHandler**<small> or many other providers</small>

---

## Spring Boot Starter Dependency

You can configure the failover module with your project by adding the below starter dependency and the configurations
 
```pom.xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-spring-boot-starter</artifactId>
        <version> <!-- add latest version --> </version>
    </dependency>
```

For more details, please go to [Getting Started](https://societe-generale.github.io/failover/#/documentation/quick-start)

---

![failover](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover.png)

---

## Key Features  

![failover key features](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-key-features-list.png)  

- **A light framework ( Domain and Core modules )** : No external frameworks  ( Just by @Failover Annotation )
  - Easy integration with any jvm base project with no spring framework. 
  - **Spring Boot Starter** : Spring boot starter for easy spring integration
- **Failover Execution Strategy** :  ( Eliminate tightly coupling with other frameworks )
  - With simple Try Catch  ( No heavy framework )
  - Support for resilience4j-circuitbreaker 	
  - Easily pluggable architecture for custom Failover Execution Strategy 
- **Failover Store** :  
  - In-memory : Not recommended for production 
  - Cache : With caffeine cache ( for very small-scale use case )
  - JDBC : For any database support ( recommended for most common use cases )
  - CUSTOM : For any other custom failover store
- **Failover Expiry Policy** :
  - With simple time duration ( SECONDS, MINUTES, HOURS, DAYS, etc. )
  - Custom expiry policy : Team can configure any specific custom expiry policy for their need. Ex: For not to expire on weekends
- **Monitoring** : Various failover metrics are available for effective monitoring
  - **Failover Configuration Dashboard** : Shows all configurations on failover
  - **Failover Rates** : Shows overall failover on external service call 
  - **Failover Recovery Rates** : Shows recovery on failover  
  - **Failover NonRecovery Rates** : Shows non recovery on failover ( actual impact or exception on application )
---
 
## Use case 

### Context
In microservice architecture, many types of microservices are present in a platofrm. In this example, we have 3 category of microservices.

1. **Internal Microservices** :  
   - Full ownership is with the application teams. 
   - If there is any issue in Internal Services, the team has full control of it and easy to improve. 
2. **Transversal Services** :
   - Ownership is not with application team, but managed by other teams in the organization. 
   - If there is any issue in such service, the application team has to escalate the issues with the respective owners/teams and wait for the resolution. Most of the time this take some time. 
3. **External Service** : 
   - Ownership is with external organization
   - If there is any issue in such service, the application team has to escalate the issues with the respective teams in other organization and wait for the resolution. Most of the time this take some time. 

![failover use case](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-challenges.png)

### Scenario 
**When a referential services having some issues where the application teams does not have a control ?**
- In such condition, the impact will be cascaded to each applications. 
- If referential service is down, the application will have some exception. ( *500 : Internal Server Error* )
- If the referential service is slow, our application will also have the slowness. 

### Solution 
- Apply **failover** 
- Define the expiry for each referential **with the acceptance of business**
- Define **acceptable expiry policy**

![failover solution](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-solution.png)

### Benefit
The solution **will not eliminate such failures completely (not 100%)**. 
However, this will help us to **reduce the impact** on the business on a large scale.

![failover solution](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-user-experience.png)

---

## MONITORING

    The failover lib comes with good monitoring metrics which help the teams to understand the various insights of overall failures on the application

### Failover Configuration Dashboard
![failover solution](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-monitoring-dashboard.png)

### Failover Rates
![failover solution](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-monitoring-failover-rate.png)

### Failover Recovery Rates
![failover solution](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-monitoring-failover-recovery-rate.png)

### Failover NonRecovery Rates 
![failover solution](https://raw.githubusercontent.com/societe-generale/failover/main/docs/images/failover-monitoring-failover-non-recovery-rate.png)

---

## Code Owners
- [Anand MANISSERY](https://github.com/anandmnair)

## Thanks and acknowledgement 
- [Vincent FUCHS](https://github.com/vincent-fuchs) 
- Patrice FRICARD
- Igor LOVICH
- Abilash TITUS
---
