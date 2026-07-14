/*
 * Copyright 2022-2026, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.dashboard.web;

import com.societegenerale.failover.dashboard.config.DashboardProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Enforces two dashboard policies on every {@code base-path/**} request (design doc §9):
 *
 * <ul>
 *   <li><b>Exposure narrowing</b> — a read API endpoint ({@code config}/{@code metrics}/{@code health})
 *       whose name is not in {@code exposure.include} (or with {@code exposure.api=false}) returns
 *       {@code 404}, even though its controller bean is wired. Lets a consumer narrow exposure without
 *       touching beans. Does not apply to any {@link PeerIngestEndpoint} (currently
 *       {@link ClusterSnapshotController}, {@link ClusterHeartbeatController}): those endpoints are peer
 *       ingest, not a UI-facing read, already gated by their own dedicated access control
 *       ({@code allow-insecure-ingest} / {@code snapshot.username+password} / OAuth2 — see
 *       {@code DashboardAutoConfiguration}), and must never be silently 404'd by narrowing
 *       {@code exposure.include} for the UI (that would break shared-store cluster aggregation with
 *       no diagnostic trail on either side).</li>
 *   <li><b>Content-Security-Policy</b> — a strict, static-only CSP header is added so the UI cannot
 *       load remote or inline scripts (Chart.js is vendored; no {@code eval}).</li>
 * </ul>
 *
 * @author Anand Manissery
 */
@Slf4j
public class DashboardExposureInterceptor implements HandlerInterceptor {

    /** Static-only policy: same-origin scripts, inline styles for chart canvases, data: images. */
    static final String CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'";

    private final DashboardProperties properties;
    private final String apiPrefix;

    /**
     * Creates a new interceptor.
     *
     * @param properties the bound {@code failover.dashboard.*} properties
     */
    public DashboardExposureInterceptor(DashboardProperties properties) {
        this.properties = properties;
        this.apiPrefix = properties.basePath() + "/api/";
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, HttpServletResponse response, @NonNull Object handler)
            throws IOException {
        response.setHeader("Content-Security-Policy", CSP);
        if (isPeerIngest(handler)) {
            return true; // peer ingest — gated by its own dedicated access control, not UI exposure
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

    private boolean isPeerIngest(Object handler) {
        return handler instanceof HandlerMethod hm && hm.getBean() instanceof PeerIngestEndpoint;
    }

    /** @return the API endpoint name ({@code config}/{@code metrics}/{@code health}) the URI targets, or {@code null}. */
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
