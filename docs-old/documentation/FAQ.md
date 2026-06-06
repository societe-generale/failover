# FAQ 
> Here are some frequently asked questions about the Failover library 

---

> #### Question 1 : <small><small>How can I configure Failover Lib to my project ?</small></small>
  **Ans :** Please look at our documentation to have a [quick start](documentation/quick-start.md)

---

> #### Question 2 : <small><small>How can I configure Failover Lib to my project with hexagonal architecture ?</small></small>
  **Ans :** 
  If you follow hexagonal architecture, please configure the **failover-domain** module with your domain to provide the failover domain classes (@Failover, Referential, ReferentialAware).
```pom.xml
    <dependency>
         <groupId>com.societegenerale.failover</groupId>
         <artifactId>failover-domain</artifactId>
         <version> <!-- add latest version --> </version>
    </dependency>
``` 

  For more details, Please look at our documentation to have a [quick start](documentation/quick-start.md)

---
