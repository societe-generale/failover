---
icon: material/information-outline
---

# About

**Failover** is an open-source Spring Boot library built at [Société Générale](https://www.societegenerale.com) to provide transparent failover for referential data.

---

## Origins

The library was born from a recurring resilience problem across Société Générale's platforms: dozens of services share dependencies on the same small set of referential systems — currency tables, country lists, client profiles — that change slowly but are queried constantly.

A single referential outage cascades into a full-platform incident. Rather than solve this problem N times with bespoke try/catch logic, the team built a reusable, annotation-driven solution.

See [ADR 1](adr/index.md) for the founding decision.

---

## Design Philosophy

- **Minimal integration** — one annotation on any Spring-proxied method. No framework coupling, no bespoke code per service.
- **Business-driven expiry** — TTL is a business decision. The framework enforces it; teams configure it.
- **Observable by default** — every store/recover event emits structured logs and Micrometer metrics. No extra instrumentation code required.
- **Pluggable at every seam** — store, key generation, expiry policy, payload enrichment, exception policy, context propagation — all replaceable without modifying the framework.

---

## Maintainers

| Name | Role |
|---|---|
| Anand Manissery | Creator & Lead Maintainer |

---

## License

Apache License 2.0 — see [LICENSE](https://github.com/societegenerale/failover/blob/main/LICENSE).
