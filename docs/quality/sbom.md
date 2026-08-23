---
icon: material/format-list-bulleted
---

# Software Bill of Materials (SBOM)

Every Failover module emits a **CycloneDX SBOM** at build time. The SBOM is the machine-readable
manifest of every component a published artifact ships — direct and transitive dependencies, their
versions, licences and package URLs (PURLs). It is the "ingredients label" for the library.

## Why Failover ships an SBOM

- **Downstream CVE response.** When the next high-profile CVE lands, consumers can answer
  *"am I affected?"* by scanning the SBOM — no need to decompile the JAR or guess the transitive tree.
- **Durable, re-scannable artifact.** The build-time CVE gate checks dependencies *as they were at
  release*. The SBOM is re-scanned later against vulnerabilities discovered *after* release.
- **Compliance.** Satisfies SBOM obligations under the US Executive Order 14028 and the EU Cyber
  Resilience Act (CRA) — relevant for regulated consumers.
- **Licence visibility.** Enumerates dependency licences for automated OSS review.

## How it is generated

The CycloneDX Maven plugin runs once per module during the `package` phase and attaches the SBOM as
a secondary artifact (classifier `cyclonedx`), so it is published alongside each JAR.

```bash
# Build everything — each module's SBOM is written to its target/ directory
mvn clean package

# Or generate the SBOM for a single module without a full build
mvn -pl failover-core cyclonedx:makeBom
```

Output (per module):

```
<module>/target/<module>-<version>-cyclonedx.json
```

The JSON is **CycloneDX spec 1.6**, `library` project type, covering `compile`, `runtime` and
`provided` scopes. Test-scoped dependencies are excluded because they never ship to consumers.

!!! note "Authoritative source"
    The per-module `*-cyclonedx.json` files are the authoritative, always-current SBOM. The table
    below is a human-readable snapshot for convenience and may lag the build; regenerate it from the
    SBOM when in doubt.

## Dependency inventory (snapshot)

Deduplicated third-party runtime dependencies across all modules (excludes the internal
`com.societegenerale.*` reactor modules and test-only dependencies).

### Spring Boot — `4.0.6`

| Artifact | Version |
|---|---|
| org.springframework.boot:spring-boot | 4.0.6 |
| org.springframework.boot:spring-boot-actuator | 4.0.6 |
| org.springframework.boot:spring-boot-actuator-autoconfigure | 4.0.6 |
| org.springframework.boot:spring-boot-autoconfigure | 4.0.6 |
| org.springframework.boot:spring-boot-configuration-processor | 4.0.6 |
| org.springframework.boot:spring-boot-health | 4.0.6 |
| org.springframework.boot:spring-boot-http-converter | 4.0.6 |
| org.springframework.boot:spring-boot-jackson | 4.0.6 |
| org.springframework.boot:spring-boot-micrometer-metrics | 4.0.6 |
| org.springframework.boot:spring-boot-micrometer-observation | 4.0.6 |
| org.springframework.boot:spring-boot-security | 4.0.6 |
| org.springframework.boot:spring-boot-servlet | 4.0.6 |
| org.springframework.boot:spring-boot-starter | 4.0.6 |
| org.springframework.boot:spring-boot-starter-actuator | 4.0.6 |
| org.springframework.boot:spring-boot-starter-jackson | 4.0.6 |
| org.springframework.boot:spring-boot-starter-logging | 4.0.6 |
| org.springframework.boot:spring-boot-starter-micrometer-metrics | 4.0.6 |
| org.springframework.boot:spring-boot-starter-security | 4.0.6 |
| org.springframework.boot:spring-boot-starter-tomcat | 4.0.6 |
| org.springframework.boot:spring-boot-starter-tomcat-runtime | 4.0.6 |
| org.springframework.boot:spring-boot-starter-web | 4.0.6 |
| org.springframework.boot:spring-boot-tomcat | 4.0.6 |
| org.springframework.boot:spring-boot-web-server | 4.0.6 |
| org.springframework.boot:spring-boot-webmvc | 4.0.6 |

### Spring Framework — `7.0.7`

| Artifact | Version |
|---|---|
| org.springframework:spring-aop | 7.0.7 |
| org.springframework:spring-beans | 7.0.7 |
| org.springframework:spring-context | 7.0.7 |
| org.springframework:spring-core | 7.0.7 |
| org.springframework:spring-expression | 7.0.7 |
| org.springframework:spring-jdbc | 7.0.7 |
| org.springframework:spring-tx | 7.0.7 |
| org.springframework:spring-web | 7.0.7 |
| org.springframework:spring-webmvc | 7.0.7 |

### Spring Security — `7.0.5`

| Artifact | Version |
|---|---|
| org.springframework.security:spring-security-config | 7.0.5 |
| org.springframework.security:spring-security-core | 7.0.5 |
| org.springframework.security:spring-security-crypto | 7.0.5 |
| org.springframework.security:spring-security-web | 7.0.5 |

### Micrometer

| Artifact | Version |
|---|---|
| io.micrometer:context-propagation | 1.2.1 |
| io.micrometer:micrometer-commons | 1.16.5 |
| io.micrometer:micrometer-core | 1.16.5 |
| io.micrometer:micrometer-jakarta9 | 1.16.5 |
| io.micrometer:micrometer-observation | 1.16.5 |
| io.micrometer:micrometer-tracing | 1.6.5 |

### Jackson

| Artifact | Version |
|---|---|
| com.fasterxml.jackson.core:jackson-annotations | 2.21 |
| tools.jackson.core:jackson-core | 3.1.2 |
| tools.jackson.core:jackson-databind | 3.1.2 |

### Embedded Tomcat — `11.0.21`

| Artifact | Version |
|---|---|
| org.apache.tomcat.embed:tomcat-embed-core | 11.0.21 |
| org.apache.tomcat.embed:tomcat-embed-el | 11.0.21 |
| org.apache.tomcat.embed:tomcat-embed-websocket | 11.0.21 |

### Logging

| Artifact | Version |
|---|---|
| ch.qos.logback:logback-classic | 1.5.32 |
| ch.qos.logback:logback-core | 1.5.32 |
| org.apache.logging.log4j:log4j-api | 2.25.4 |
| org.apache.logging.log4j:log4j-to-slf4j | 2.25.4 |
| org.slf4j:jul-to-slf4j | 2.0.17 |
| org.slf4j:slf4j-api | 2.0.17 |
| commons-logging:commons-logging | 1.3.6 |

### AOP / annotations / utilities

| Artifact | Version |
|---|---|
| org.aspectj:aspectjweaver | 1.9.25.1 |
| aopalliance:aopalliance | 1.0 |
| jakarta.annotation:jakarta.annotation-api | 3.0.0 |
| org.jspecify:jspecify | 1.0.0 |
| org.projectlombok:lombok | 1.18.46 |
| org.yaml:snakeyaml | 2.5 |
| org.hdrhistogram:HdrHistogram | 2.2.2 |
| org.latencyutils:LatencyUtils | 2.0.3 |

!!! tip "Not every module pulls every dependency"
    This is the **union** across all 19 modules. A consumer who adds only
    `failover-spring-boot-starter` (without the dashboard) gets neither Spring Security, Spring MVC,
    nor embedded Tomcat. Use the per-module SBOM for the exact set of a given artifact.
