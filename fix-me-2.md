# Improvements: dashboard UI auth gate (`dashboardSecurityFilterChain`)

Two separate issues found while wiring GitHub OAuth login onto the dashboard UI.
Distinct from the `exposure.include` bug in `fix-me.md`.

---

## Issue 1: silent unsatisfiable login — no startup diagnostic

### Symptom

With Spring Security on the classpath, `failover.dashboard.security.type=ROLE`
(the default posture once `allow-insecure` is off) and no `UserDetailsService` /
`AuthenticationManager` bean defined anywhere in the app, the app boots clean —
no warning, no error. Hitting the dashboard triggers a native browser Basic-Auth
prompt. **Every credential fails**, including deliberately wrong ones:

```bash
curl -is "http://localhost:8080/<ctx>/failover-dashboard/"                    # 401
curl -is -u "user:wrongpass" "http://localhost:8080/<ctx>/failover-dashboard/" # 401, identical response
```

There is no correct password to find — none exists. `UserDetailsServiceAutoConfiguration`'s
default generated-user fallback (the one that normally prints
`Using generated security password: <uuid>` to the console) doesn't fire either,
because having `spring-boot-starter-oauth2-client` and any custom
`SecurityFilterChain` bean on the classpath makes Spring Boot treat the app as
"already secured" and skip it. The dashboard's Basic-Auth gate is permanently
dead as configured, with zero signal telling the operator why.

### Root cause

`DashboardAutoConfiguration.SecurityPresentConfiguration.dashboardSecurityFilterChain`
unconditionally calls `.httpBasic(Customizer.withDefaults())` and relies on
whatever `AuthenticationManager` happens to be in the application context. There
is no check that one actually exists and is capable of authenticating anyone.

Compare this to `SecurityAbsentConfiguration`, which *does* fail fast (or warn
loudly) when Spring Security is missing from the classpath entirely:

```java
@ConditionalOnMissingClass("org.springframework.security.web.SecurityFilterChain")
static class SecurityAbsentConfiguration {
    SecurityAbsentConfiguration(DashboardProperties props, Environment environment) {
        if (!props.security().allowInsecure()) {
            throw new IllegalStateException(/* ... */);
        }
        // ...
        log.warn("Failover dashboard is running WITHOUT an access-control gate ...");
    }
}
```

The symmetric case — Security *present*, role/authority type selected, but no
backing `UserDetailsService`/`AuthenticationManager`/`AuthenticationProvider` —
has no equivalent guard. The ingest endpoint gets a loud WARN for its own
insecure escape hatch (`allow-insecure-ingest=true`); the dashboard UI gate gets
nothing for a strictly worse failure mode: not "insecure," but "permanently
inaccessible," discovered only by a confused human staring at a login prompt.

### Recommended fix

Add a startup check alongside `dashboardSecurityFilterChain`, in the same
`SecurityPresentConfiguration` class, that inspects whether any bean capable of
backing `httpBasic()` exists, and warns (or fails fast, matching the
`SecurityAbsentConfiguration` philosophy) if not:

```java
@Bean
@ConditionalOnMissingBean(name = "dashboardSecurityFilterChain")
DashboardAuthBackingValidator dashboardAuthBackingValidator(
        DashboardProperties props,
        ObjectProvider<UserDetailsService> userDetailsServices,
        ObjectProvider<AuthenticationProvider> authenticationProviders,
        ObjectProvider<AuthenticationManager> authenticationManagers) {

    boolean hasBackingAuth = userDetailsServices.stream().findAny().isPresent()
            || authenticationProviders.stream().findAny().isPresent()
            || authenticationManagers.stream().findAny().isPresent();

    if (!props.security().allowInsecure() && !hasBackingAuth) {
        throw new IllegalStateException(
                "Failover dashboard security.type=" + props.security().type()
                        + " requires a UserDetailsService/AuthenticationProvider/AuthenticationManager "
                        + "bean to authenticate against, but none was found. httpBasic() would prompt "
                        + "for credentials that can never succeed. Either define one of those beans "
                        + "(e.g. spring.security.user.name/password for a quick dev user), override the "
                        + "'dashboardSecurityFilterChain' bean with your own authentication mechanism "
                        + "(see Issue 2), or set failover.dashboard.security.allow-insecure=true for "
                        + "trusted-network/dev use.");
    }
    return new DashboardAuthBackingValidator();
}
```

Note the `@ConditionalOnMissingBean(name = "dashboardSecurityFilterChain")` on
the validator itself — it must only run when the starter's own default chain is
actually the one in effect. A consumer who overrides
`dashboardSecurityFilterChain` (Issue 2's workaround, or any custom mechanism)
has already taken responsibility for authentication and shouldn't be blocked by
this check.

### Priority

High — this is a trap with no way out short of reading the starter's source
(which is what happened here). A fail-fast exception at startup, mirroring
`SecurityAbsentConfiguration`'s existing pattern, turns a multi-hour dead-end
into an immediate, actionable error message.

---

## Issue 2: no OAuth2 login option for the dashboard UI gate

### Symptom / gap

The snapshot **ingest** endpoint already supports three pluggable auth
mechanisms via config alone:

```yaml
failover.dashboard.cluster.snapshot.username: ...            # HTTP Basic
failover.dashboard.cluster.snapshot.allow-insecure-ingest: true  # permit-all
failover.dashboard.cluster.snapshot.oauth2-client-registration-id: ...  # OAuth2 JWT
```

The **dashboard UI** gate (`dashboardSecurityFilterChain`) supports exactly one:
`httpBasic()`, hardcoded into the same bean method that also carries the
role/authority/expression authorization logic from `FailoverSecurityProvider`.
For a browser-facing UI, Basic Auth is a dated fit (no logout, credentials
cached indefinitely by the browser, no SSO/identity-provider integration) —
OAuth2 login is the more natural mechanism, and the library already has all the
`spring-security-oauth2-client` plumbing for it (used on the ingest side).

To get OAuth2 login on the dashboard today, a consumer must fully override the
`dashboardSecurityFilterChain` bean by name — the starter conveniently backs off
via `@ConditionalOnMissingBean(name = "dashboardSecurityFilterChain")` (this is
the correct, sanctioned extension point, and it's how we solved this locally —
see `SecurityConfig.java` in this repo). But doing so means **reimplementing**
`FailoverSecurityProvider`'s role/authority/expression authorization logic from
scratch, since that logic lives inside the same bean method being replaced.
Consumers lose the configurable authorization posture just to swap the
authentication mechanism.

### Recommended fix

Mirror the ingest endpoint's pattern: add a config-driven OAuth2 login variant
that composes with the existing `FailoverSecurityProvider`, so consumers get
OAuth2 authentication *and* keep configurable role/authority/expression
authorization, without overriding anything:

```java
/**
 * Dashboard UI secured with OAuth2 login instead of HTTP Basic. Activated when
 * failover.dashboard.security.oauth2-client-registration-id is set. Authorization
 * still goes through FailoverSecurityProvider, same as the httpBasic variant —
 * only the authentication mechanism differs.
 */
@Bean
@Order(0)
@ConditionalOnProperty(prefix = "failover.dashboard.security", name = "oauth2-client-registration-id")
@ConditionalOnMissingBean(name = "dashboardSecurityFilterChain")
SecurityFilterChain dashboardOAuth2SecurityFilterChain(
        HttpSecurity http, FailoverSecurityProvider failoverSecurityProvider, DashboardProperties props) throws Exception {
    http.securityMatcher(props.basePath() + "/**")
            .authorizeHttpRequests(auth -> failoverSecurityProvider.configure(auth,
                    new SecurityContext(props.basePath(), props.security())))
            .oauth2Login(Customizer.withDefaults());
    log.info("Failover dashboard secured: '{}/**' via OAuth2 login (registration '{}'), requires role '{}'.",
            props.basePath(), props.security().oauth2ClientRegistrationId(), props.security().role());
    return http.build();
}
```

The existing `httpBasic()` bean then needs
`@ConditionalOnMissingBean(name = {"dashboardSecurityFilterChain", "dashboardOAuth2SecurityFilterChain"})`
added, same three-tier pattern already used for the ingest chains.

This still leaves open how GitHub/OIDC claims map to `FAILOVER_ADMIN` — that's a
`GrantedAuthoritiesMapper` bean the consumer supplies (standard Spring Security
extension point), not something the starter needs to invent. Worth a line of
Javadoc pointing consumers at it, since `hasRole()`/`hasAuthority()` will
otherwise always deny an OAuth2-authenticated user with no mapped authorities.

### Priority

Medium — the `@ConditionalOnMissingBean(name=...)` override we used works today
and is the correct extension point, so this isn't a hard blocker like Issue 1.
It's a rougher-than-necessary path for what's likely a common ask (browser UI +
corporate/GitHub SSO instead of Basic Auth).

---

## Reference: local workaround (already applied in `failover-dashboard-demo`)

Until the library adds config-driven OAuth2 login support, we override the bean
by name in `SecurityConfig.java`:

```java
@Bean(name = "dashboardSecurityFilterChain")
@Order(0)
public SecurityFilterChain dashboardSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/failover-dashboard/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2.defaultSuccessUrl("/failover-dashboard", true));
    return http.build();
}
```

This drops `FailoverSecurityProvider`'s role/authority/expression authorization
entirely in favor of a plain `authenticated()` check — acceptable for a local
security demo, not a substitute for Issue 2's fix in a real deployment that
needs `FAILOVER_ADMIN`-gated access control on top of OAuth2 identity.
