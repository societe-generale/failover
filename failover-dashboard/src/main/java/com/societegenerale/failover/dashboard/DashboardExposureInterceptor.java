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

package com.societegenerale.failover.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Enforces two dashboard policies on every {@code base-path/**} request (design doc §9):
 *
 * <ul>
 *   <li><b>Exposure narrowing</b> — an API endpoint ({@code config}/{@code metrics}/{@code health})
 *       whose name is not in {@code exposure.include} (or with {@code exposure.api=false}) returns
 *       {@code 404}, even though its controller bean is wired. Lets a consumer narrow exposure without
 *       touching beans.</li>
 *   <li><b>Content-Security-Policy</b> — a strict, static-only CSP header is added so the UI cannot
 *       load remote or inline scripts (Chart.js is vendored; no {@code eval}).</li>
 * </ul>
 *
 * @author Anand Manissery
 */
public class DashboardExposureInterceptor implements HandlerInterceptor {

    /** Static-only policy: same-origin scripts, inline styles for chart canvases, data: images. */
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
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        response.setHeader("Content-Security-Policy", CSP);
        String endpoint = endpointOf(request.getRequestURI());
        if (endpoint != null && !properties.exposure().includes(endpoint)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        return true;
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
