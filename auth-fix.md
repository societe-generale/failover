# Dashboard Auth Flexibility — Findings & Fix Plan

## Scope correction

Earlier pass assumed a specific parent app ("moauth2") we could inspect, and traced the 401 +
wrong-redirect symptom to that app's config (a `client_credentials`-only registration pointed at
`oauth2Login()`, which needs `authorization_code`). That diagnosis is still *a* valid failure
mode, but it's the wrong frame for the actual ask: **this is a library used by unknown consumers
with unknown auth stacks** — client_credentials-only shops, resource-server/JWT-behind-a-gateway
shops, SAML shops, custom trusted-header shops, or none of the above. The fix can't be "tell this
one consumer to add a registration" — it has to be "give every consumer, regardless of their auth
model, a low-effort way to plug their model into the dashboard." That's the actual gap, and it's a
library design gap, not a config gap in someone else's app.

## What the dashboard supports today

`DashboardAutoConfiguration` (`failover-dashboard/src/main/java/com/societegenerale/failover/dashboard/config/DashboardAutoConfiguration.java`)
builds the UI/API gate as one of exactly **two hardcoded authentication mechanisms**, chosen by a
single property:

| Mechanism | Trigger | Bean | Lines |
|---|---|---|---|
| HTTP Basic (default) | `security.oauth2-client-registration-id` blank | `dashboardSecurityFilterChain` | 619-640 |
| OAuth2 login (session cookie, `authorization_code`) | `security.oauth2-client-registration-id` set | `dashboardOAuth2SecurityFilterChain` | 662-698 |

Both bake **matcher + authentication + authorization + error handling** into a single
`@Bean` method. The only escape hatch for anything else is
`@ConditionalOnMissingBean(name = {"dashboardSecurityFilterChain", "dashboardOAuth2SecurityFilterChain"})`
— a consumer can fully replace the chain by declaring their own bean under one of those exact
names, but doing so means re-implementing *everything* the built-in beans already do: the
`securityMatcher(basePath + "/**")`, the `FailoverSecurityProvider` authorization wiring, the
`DashboardAccessDeniedHandler`, the session policy. There's no seam for "keep everything, just
swap how a user authenticates." That's the concrete flexibility gap.

Two things are already correctly decoupled and worth keeping as-is:

- **Authorization is independent of authentication.** `FailoverSecurityProvider`
  (role/authority/expression checks) is its own `@ConditionalOnMissingBean`-replaceable interface
  (`security/FailoverSecurityProvider.java`) and is invoked identically regardless of which
  authentication mechanism is in front of it. Any new mechanism plugs into the same
  `auth -> failoverSecurityProvider.configure(auth, ...)` call — no duplication needed there.
- **Peer-ingest already proves the pattern this is missing for the UI.** The ingest side
  (`OAuth2IngestSecurityConfiguration`, lines 708-726) already supports a third mechanism —
  `oauth2ResourceServer().jwt()` — cleanly isolated in its own `@ConditionalOnClass`-gated
  `@Configuration` so the resource-server API is never loaded unless present. The UI/API gate has
  no equivalent, and has no "bring your own" seam at all.

## Proposed fix: a pluggable authentication seam

Add one new, small extension point — a `DashboardAuthenticationConfigurer` (or reuse Spring's own
`Customizer<HttpSecurity>` typed under a dedicated bean name to avoid ambiguity with other
`HttpSecurity` customizers a consumer might have) — that a consumer can implement to wire **any**
authentication mechanism onto the dashboard's own chain, without touching matcher, authorization,
or error handling:

```java
public interface DashboardAuthenticationConfigurer {
    void configure(HttpSecurity http, SecurityContext context) throws Exception;
}
```

Restructure `SecurityPresentConfiguration` so there is exactly **one** assembled chain (instead of
two mutually-exclusive `@Bean` methods), which always applies matcher + authorization + error
handling, and picks the authentication step by priority:

1. **Consumer-supplied `DashboardAuthenticationConfigurer` bean** — if present, call it. Covers
   literally anything: `oauth2ResourceServer().jwt()` for gateway-fronted stateless shops,
   a custom trusted-header filter for reverse-proxy SSO (e.g. oauth2-proxy / Envoy-injected
   identity headers), SAML, mTLS-derived principal, an existing enterprise
   `AuthenticationProvider`, or a `client_credentials`-only shop that decides the dashboard should
   just sit behind their gateway's own auth entirely.
2. **OAuth2 login** — existing behavior, unchanged, when `security.oauth2-client-registration-id`
   is set and no custom configurer bean exists.
3. **OAuth2 resource server (JWT)** — *new* built-in third option, mirroring the ingest side:
   activate via a new `security.oauth2-resource-server=true` (or auto-detect
   `spring.security.oauth2.resourceserver.jwt.issuer-uri`) when
   `spring-security-oauth2-resource-server` is on the classpath and no custom configurer/oauth2-login
   is configured. Directly serves the "gateway already validates SSO and forwards a JWT" shape
   without requiring a consumer to write the escape-hatch bean themselves for what is a very common
   deployment pattern.
4. **HTTP Basic** — existing default, unchanged, last in priority.

This keeps the two current behaviors and the ingest precedent working exactly as before, adds one
built-in option for the gateway/JWT shape, and gives every other auth model a one-method escape
hatch that doesn't require re-declaring the whole chain. `DashboardAuthBackingValidator`'s
fail-fast check (lines 584-617) extends naturally: skip the "needs a UserDetailsService/
AuthenticationProvider" requirement whenever a `DashboardAuthenticationConfigurer` bean or the new
resource-server option is present, same as it already skips for `oauth2-client-registration-id`.

## Secondary gaps (still worth fixing, independent of the above)

### `app.js` has no 401/session-expiry handling

`fetchJson` (`failover-dashboard/src/main/resources/failover-dashboard/app.js:846-849`) throws a
bare `HTTP 401` into a notice div with no recovery path:

```js
async function fetchJson(path) {
    const res = await fetch(path, { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}
```

Whatever authentication mechanism is in front (session cookie, JWT, custom), a `401` mid-session
should give the operator a way back in rather than a permanently dead tile. Concretely: on `401`,
reload the page against `basePath/` and let whichever mechanism is active re-trigger its own
login/redirect (works for `oauth2Login`; for resource-server/custom mechanisms behind a gateway,
the gateway is what needs to re-auth on reload, which it naturally does since the request just
looks unauthenticated again). This doesn't need mechanism-specific JS — it needs to *stop
swallowing* the 401 as a dead end.

### No forwarded-headers guidance

Nowhere in the codebase or docs (`grep -rn "forward-headers\|X-Forwarded\|ForwardedHeaderFilter"`
returns nothing). Whenever `oauth2Login()` runs behind a reverse proxy/ingress that terminates TLS,
Spring's default `redirect-uri` template (`{baseUrl}/login/oauth2/code/{registrationId}`) is
computed from the incoming request's scheme/host/port — wrong without
`server.forward-headers-strategy=framework` (or equivalent), producing exactly a "redirects to the
wrong URI" symptom. This is mechanism-independent guidance (applies to any consumer using the
built-in `oauth2Login()` option, or a resource-server configurer that also needs correct
`baseUrl` for any redirect it issues) and belongs in the docs regardless of which auth model a
given consumer picks.

### Filter-chain ordering, when a consumer's own app has endpoints outside `base-path`

Already documented in `docs/modules/dashboard.md:571-627` for the case where the *consumer's*
endpoints need their own chain. Worth adding the reverse note explicitly: if a consumer's own
broader `SecurityFilterChain` doesn't narrow its `securityMatcher` away from `basePath/**`, or
outranks the dashboard's `@Order(0)`/`@Order(-10)`, it will shadow the dashboard's chain — this
applies regardless of which authentication mechanism either side uses, and is worth calling out
next to the new `DashboardAuthenticationConfigurer` docs since consumers most likely to need a
custom configurer are exactly the ones most likely to already have their own broad chain.

## Fix / improvement plan

- **P0 — Ship the `DashboardAuthenticationConfigurer` seam** (new interface, restructured
  `SecurityPresentConfiguration`, updated `DashboardAuthBackingValidator` condition). This is the
  actual "be flexible for any consumer" fix.
- **P0 — Add the built-in `oauth2ResourceServer` option** for the UI/API gate, mirroring the
  ingest side. Covers the single most common "our SSO isn't authorization_code" shape
  (gateway-terminated JWT) without requiring every such consumer to write their own configurer.
- **P1 — Fix `app.js`'s 401 handling** (reload-and-let-the-active-mechanism-reauth), mechanism
  agnostic, small change, real UX improvement regardless of which option above is chosen.
- **P1 — Document forwarded-headers and chain-ordering requirements** next to the new extension
  point in `dashboard.md`, framed as "things every consumer must get right regardless of which
  authentication option they pick," not as advice for one specific setup.
- **P2 — Worked examples in `dashboard.md`** for at least: (a) trusted-header/gateway-SSO
  configurer, (b) resource-server/JWT built-in option, (c) a from-scratch custom
  `AuthenticationProvider` configurer — so "any auth" isn't just a claim, there's a copy-pasteable
  example close to whatever a new consumer actually has.

No further action is blocked on external info — this plan doesn't depend on knowing what any
particular consumer's auth stack looks like, which was the point.
