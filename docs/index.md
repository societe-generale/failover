---
hide:
  - navigation
  - toc
template: home.html
---

<p class="fo-section-eyebrow">THE PROBLEM → THE SOLUTION</p>

## Replace fragile try/catch with one annotation

Every team reinvents the same resilience wheel. Failover removes it entirely.

<div class="fo-compare">
<div class="fo-compare-panel before">
<div class="fo-compare-header">❌ Without Failover — bespoke, brittle, repeated everywhere</div>

```java
public Country findByCode(String code) {
    try {
        Country c = upstream.findByCode(code);
        localRepo.save(c, computeExpiry());
        return c;
    } catch (Exception e) {
        log.warn("upstream failed, trying local cache");
        Country cached = localRepo.findByCode(code);
        if (cached == null || isExpired(cached)) {
            throw e;
        }
        cached.setUpToDate(false);
        return cached;
    }
}
```

</div>
<div class="fo-compare-panel after">
<div class="fo-compare-header">✅ With Failover — declarative, consistent, zero boilerplate</div>

```java
@Failover(
    name = "country-by-code",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
Country findByCode(String code);
```

</div>
</div>

<p class="fo-section-eyebrow">INTERNALS</p>

## How it works

Spring AOP intercepts every annotated method. The rest is automatic.

<div class="fo-flow-wrap">
<p class="fo-diagram-label">Call flow</p>

```mermaid
flowchart LR
    C([Your Code]) --> A{FailoverAspect}
    A -->|invoke| U([Upstream API])
    U -- "✅ success" --> ST[Store payload + TTL]
    ST --> R1([return upToDate=true])
    U -- "❌ failure" --> Q[Query store]
    Q -- "fresh entry" --> R2([return upToDate=false])
    Q -- "expired / missing" --> R3([re-throw])
```

</div>

<div class="fo-flow-wrap">
<p class="fo-diagram-label">Entry lifecycle</p>

```mermaid
stateDiagram-v2
    direction LR
    [*]     --> Live    : first successful call
    Live    --> Live    : success — TTL refreshed
    Live    --> Stale   : upstream fails, entry still fresh
    Stale   --> Live    : upstream recovers
    Stale   --> Expired : TTL exceeded
    Expired --> [*]     : re-throw / null
```

</div>
    
<div class="fo-flow-wrap">
<p class="fo-diagram-label">Sequence diagram</p>

```mermaid
sequenceDiagram
    participant C as Caller
    participant A as FailoverAspect
    participant H as FailoverHandler
    participant K as KeyGenerator
    participant E as ExpiryPolicy
    participant S as FailoverStore
    participant U as Upstream

    C->>A: invoke @Failover method(args)
    A->>U: call upstream
    alt Upstream succeeds
        U-->>A: result
        A->>K: key(failover, args)
        K-->>A: storeKey
        A->>E: computeExpiry(failover)
        E-->>A: expireOn
        A->>S: store(name, key, result, expireOn)
        A-->>C: result (upToDate=true)
    else Upstream throws
        U-->>A: exception
        A->>K: key(failover, args)
        K-->>A: lookupKey
        A->>S: find(name, lookupKey)
        alt Found and not expired
            S-->>A: ReferentialPayload
            A-->>C: payload (upToDate=false, asOf=storedTime)
        else Not found or expired
            S-->>A: empty / expired
            A-->>C: null or rethrow (per ExceptionPolicy)
        end
    end
```

</div>

<div class="fo-flow-wrap">
<div class="fo-flow-caption">
  <div class="fo-flow-item">
    <div class="fo-flow-dot success"></div>
    <p><strong>On success</strong> — result persisted under the derived key with the configured TTL. <code>upToDate=true</code> set on the returned object.</p>
  </div>
  <div class="fo-flow-item">
    <div class="fo-flow-dot failure"></div>
    <p><strong>On failure</strong> — last stored result returned. If none or expired: re-throw (default) or return <code>null</code> via <code>exception-policy: never_throw</code>.</p>
  </div>
</div>
</div>
