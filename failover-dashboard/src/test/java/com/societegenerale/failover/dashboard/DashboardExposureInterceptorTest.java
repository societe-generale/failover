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

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardExposureInterceptorTest {

    private DashboardExposureInterceptor interceptor(boolean api, List<String> include) {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard",
                new DashboardProperties.Exposure(true, api, include),
                new DashboardProperties.Security("FAILOVER_ADMIN", false),
                new DashboardProperties.History(false, 120, 15),
                new DashboardProperties.Health(0.99, 0.90));
        return new DashboardExposureInterceptor(props);
    }

    private boolean preHandle(DashboardExposureInterceptor i, String uri, MockHttpServletResponse res) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return i.preHandle(req, res, new Object());
    }

    @Test
    @DisplayName("adds the Content-Security-Policy header to every dashboard response")
    void addsCsp() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        preHandle(interceptor(true, List.of("config", "metrics", "health")), "/failover-dashboard/index.html", res);
        assertThat(res.getHeader("Content-Security-Policy")).contains("default-src 'self'").contains("object-src 'none'");
    }

    @Test
    @DisplayName("included endpoint passes through")
    void includedEndpointPasses() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = preHandle(interceptor(true, List.of("config", "metrics", "health")),
                "/failover-dashboard/api/metrics", res);
        assertThat(proceed).isTrue();
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("endpoint excluded from include ⇒ 404 and request blocked")
    void excludedEndpointBlocked() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = preHandle(interceptor(true, List.of("config")), "/failover-dashboard/api/metrics", res);
        assertThat(proceed).isFalse();
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("api=false ⇒ every API endpoint blocked even if listed")
    void apiOffBlocksAll() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = preHandle(interceptor(false, List.of("config", "metrics", "health")),
                "/failover-dashboard/api/config", res);
        assertThat(proceed).isFalse();
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("non-API (static) request passes regardless of include")
    void staticPasses() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = preHandle(interceptor(true, List.of()), "/failover-dashboard/app.js", res);
        assertThat(proceed).isTrue();
    }

    @Test
    @DisplayName("endpoint resolves from a sub-path (e.g. metrics/series)")
    void resolvesSubPathEndpoint() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed = preHandle(interceptor(true, List.of("metrics")),
                "/failover-dashboard/api/metrics/series", res);
        assertThat(proceed).isTrue();
    }
}
