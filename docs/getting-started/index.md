---
icon: material/rocket-launch
---

# Getting Started

Add transparent failover to any Spring Boot service in two steps — one dependency and one annotation.

```mermaid
flowchart LR
    A["① Add starter\ndependency"] --> B["② Annotate your\nSpring bean method"]
    B --> C(["✅ Failover active"])
```

| Step | Page | Time |
|---|---|---|
| Add the Maven/Gradle dependency | [Installation](installation.md) | 2 min |
| Annotate a method and verify | [Quickstart](quickstart.md) | 5 min |

!!! note "Spring beans only"
    `@Failover` intercepts calls through the Spring AOP proxy. Annotate methods on `@Service`, `@Component`, `@FeignClient`, or any other Spring-managed bean. Self-invocation (calling a `@Failover` method from within the same class) bypasses the proxy and has no effect.

---

## Next Steps

- [Installation](installation.md) — Maven and Gradle coordinates for every module
- [Quickstart](quickstart.md) — working end-to-end example in 5 minutes
- [How It Works](../concepts/how-it-works.md) — store/recover lifecycle in detail
