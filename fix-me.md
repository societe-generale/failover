# Fix: cluster snapshot ingest silently 404s under `exposure.include`

## Symptom

A peer instance pushes its snapshot via `ClusterSnapshotPublisher` to
`POST {base-path}/api/cluster/snapshot`. The dashboard returns a response, but the
push never shows up: no debug log from `ClusterSnapshotController`, no data in the
cluster view, nothing in `SnapshotStoreInmemory`. Looks like the request never
arrived, or like a security/auth problem.

## Reproduction

Minimal repro against any app using `failover-dashboard-spring-boot-starter` with
`cluster.mode=shared-store` and an `exposure.include` list that doesn't mention
`cluster`:

```yaml
failover:
  dashboard:
    enabled: true
    base-path: /failover-dashboard
    exposure:
      ui: true
      api: true
      include: [ config, failover-health, metrics, health ]   # <-- no "cluster"
    cluster:
      mode: shared-store
      shared-store:
        store: inmemory
      snapshot:
        allow-insecure-ingest: true
```

```bash
curl -is -X POST "http://localhost:8080/<ctx>/failover-dashboard/api/cluster/snapshot" \
  -H "Content-Type: application/json" \
  -d '{"instanceId":"curl-dummy-instance","summary":{"overall":null,"perApi":[],"topExceptions":[],"timestamp":0},"configEntries":[]}'
```

Response:

```
HTTP/1.1 404
Content-Type: application/json

{"timestamp":"2026-07-14T04:25:30.463Z","status":404,"error":"Not Found",
 "message":"No message available","path":".../failover-dashboard/api/cluster/snapshot"}
```

No stack trace anywhere in the app log. Nothing from `ClusterSnapshotController`
even with `logging.level.com.societegenerale.failover.dashboard: DEBUG` set.

## Diagnostic trail (why this took a while to pin down)

The symptom is a bare 404 with no exception, which is easy to misattribute.
Steps that ruled out the wrong causes, in order:

1. **Suspected Spring Security first**, since the consuming app also had a
   custom `SecurityFilterChain` for GitHub OAuth login on the dashboard UI.
   Turning on `logging.level.org.springframework.security=DEBUG` showed the
   request *did* pass the security filter chain cleanly:

   ```
   FilterChainProxy   : Securing POST /failover-dashboard/api/cluster/snapshot
   AnonymousAuthenticationFilter : Set SecurityContextHolder to anonymous SecurityContext
   FilterChainProxy   : Secured POST /failover-dashboard/api/cluster/snapshot
   ```

   So security was not rejecting the request — it was in fact the starter's
   own dedicated (unauthenticated) filter chain for that exact path, confirmed
   by this boot-time log line:

   ```
   WARN DashboardAutoConfiguration : Failover dashboard ingest '/failover-dashboard/api/cluster/snapshot'
        is running WITHOUT an access-control gate. (allow-insecure-ingest=true) ...
   ```

2. Immediately after "Secured POST ...", the same request thread logged a
   forward to `GET /error`, which — because the *consuming app's own*
   `SecurityConfig` had `anyRequest().authenticated()` with no exemption for
   `/error` — got redirected to `/oauth2/authorization/github`. This is what
   made the failure look like an OAuth/security problem from the outside (a
   302 to a GitHub login URL), even though security had already let the real
   request through cleanly. **This masking is itself worth fixing in any
   consuming app**, independent of the interceptor bug — see the note at the
   bottom.

3. Turning on `logging.level.org.springframework.web=DEBUG` exposed the real
   sequence for the original request, with no exception anywhere in between:

   ```
   DispatcherServlet            : POST "/<ctx>/failover-dashboard/api/cluster/snapshot", parameters={}
   RequestMappingHandlerMapping : Mapped to ...ClusterSnapshotController#ingest(ClusterSnapshot)
   DispatcherServlet            : Completed 404 NOT_FOUND
   ```

   "Mapped to X" followed immediately by "Completed 404" with **no** controller
   invocation log and **no** exception is the signature of a `HandlerInterceptor`
   short-circuiting the request in `preHandle()` via `response.sendError()` —
   Spring Security's `AuthorizationFilter` had already passed the request, so
   the only thing left that could produce this shape is a Spring MVC
   interceptor. That pointed straight at `DashboardExposureInterceptor`
   (grep for `Interceptor` in the starter's sources confirmed it's the only one
   registered on `{base-path}/**`).

4. Reading `DashboardExposureInterceptor.preHandle()` confirmed it: for URI
   `.../api/cluster/snapshot`, `endpointOf()` extracts `"cluster"` (the path
   segment right after `{base-path}/api/`), and `properties.exposure().includes("cluster")`
   was `false` because `cluster` wasn't in the configured `include` list.

## Root cause

`DashboardExposureInterceptor` runs `preHandle` on every request under
`{base-path}/api/**`. It extracts the path segment right after `/api/` and checks
it against `failover.dashboard.exposure.include`:

```java
String endpoint = endpointOf(request.getRequestURI());   // "cluster" for .../api/cluster/snapshot
if (endpoint != null && !properties.exposure().includes(endpoint)) {
    response.sendError(HttpServletResponse.SC_NOT_FOUND);
    return false;
}
```

If a consumer configures `exposure.include` to narrow the dashboard's **read** API
(e.g. `[config, metrics, health]`) without knowing `cluster` also needs to be
listed, every peer snapshot push gets a silent `404` — **before**
`ClusterSnapshotController.ingest()` is ever invoked. No exception is thrown, no
log line is written by the interceptor itself, and the controller's own debug
logs never fire because the controller is never reached.

## Why this needs fixing, not just documenting

- `exposure.include` is meant to narrow the **UI-facing read** surface
  (`config` / `metrics` / `health`). The snapshot ingest endpoint is not a read
  API — it's the write path that makes `cluster.mode=shared-store` work at all.
  Gating it behind the same allow-list means a legitimate, unrelated
  configuration change (tightening the UI's read exposure) can silently break
  cluster aggregation.
- The endpoint already has its own dedicated access control
  (`allow-insecure-ingest` / `snapshot.username+password` /
  `snapshot.oauth2-client-registration-id`), so gating it a second time via the
  exposure allow-list is redundant.
- There is zero diagnostic signal on rejection — no log line, nothing. Someone
  debugging this has to reason through security filters, interceptors, and
  bytecode to find it (as we just did, over a multi-hour session).

## Recommended fix

**Exempt the snapshot ingest endpoint from `DashboardExposureInterceptor`'s
exposure check entirely.** Key off the actual handler, not a path string, so it
doesn't regress if `base-path` changes. Also add a debug log on any rejection —
this alone would have cut the diagnosis time from an hour to reading one log
line.

```java
package com.societegenerale.failover.dashboard.web;

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Slf4j
public class DashboardExposureInterceptor implements HandlerInterceptor {

    static final String CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'";

    private final DashboardProperties properties;
    private final String apiPrefix;

    public DashboardExposureInterceptor(DashboardProperties properties) {
        this.properties = properties;
        this.apiPrefix = properties.basePath() + "/api/";
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, @NonNull Object handler)
            throws IOException {
        response.setHeader("Content-Security-Policy", CSP);

        if (isIngestEndpoint(handler)) {
            // Peer-to-peer snapshot push has its own access-control gate
            // (allow-insecure-ingest / snapshot.username+password / snapshot.oauth2-client-registration-id).
            // It is not a UI-facing read API and must not be governed by exposure.include.
            return true;
        }

        String endpoint = endpointOf(request.getRequestURI());
        if (endpoint != null && !properties.exposure().includes(endpoint)) {
            log.debug("Rejecting {} — endpoint '{}' not in exposure.include={}",
                    request.getRequestURI(), endpoint, properties.exposure().include());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        return true;
    }

    private boolean isIngestEndpoint(Object handler) {
        return handler instanceof HandlerMethod hm
                && hm.getBeanType() == ClusterSnapshotController.class;
    }

    private String endpointOf(String uri) {
        int idx = uri.indexOf(apiPrefix);
        if (idx < 0) {
            return null;
        }
        String rest = uri.substring(idx + apiPrefix.length());
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }
}
```

## Belt-and-braces: startup validation

`DashboardAutoConfiguration` already warns at boot about `allow-insecure-ingest`.
Add a sibling check so misconfiguration is caught even without the interceptor
fix — useful if maintainers prefer to keep exposure.include governing
everything under `/api/**` uniformly instead of exempting ingest:

```java
if (properties.cluster().mode() == ClusterMode.SHARED_STORE
        && !properties.exposure().includes("cluster")) {
    log.warn("cluster.mode=shared-store is enabled but 'cluster' is missing from exposure.include; "
            + "peer snapshot pushes to {}/api/cluster/snapshot will be rejected with 404.",
            properties.basePath());
}
```

## Priority

Lead with the interceptor fix — it removes the trap at its source. The startup
warning is a reasonable fallback/addition regardless.

## Reference: local workaround (not an upstream fix)

Until the library is patched, consumers can work around this by explicitly
listing `cluster` in their own `application.yaml`:

```yaml
failover:
  dashboard:
    exposure:
      include: [ config, failover-health, metrics, health, cluster ]
```

This was the fix applied in `failover-dashboard-demo` to unblock local testing.
