![logo](images/failover-icon.png)

# **Failover** [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.societegenerale.failover/failover/badge.svg?style=plastic)](https://maven-badges.herokuapp.com/maven-central/com.societegenerale.failover/failover)


> ***Failover library - To manage the failover on referential systems***

**"This library is to help to enable a failover to handle the failures on external services by keeping a local store for such api responses"**

- <small>Support </small>**Failover**<small> needs for your domain services</small>
- <small>Simple to use by simply annotating with </small>**@Failover(name="client-by-id")**
- <small>Support for various failover store</small> **Inmemory**, **Caffeine**, **Jdbc** etc
- <small>Support for various failover execution</small> **Basic**, **Resilience** etc
- <small>Easy to </small>**customize**<small>  and use by providing your own</small> **Expiry Policy**, **Failover Store**, **RecoveredPayloadHandler**<small> or many other providers</small>

[GitHub](https://github.com/societe-generale/failover)
[Getting Started](/README.md)
