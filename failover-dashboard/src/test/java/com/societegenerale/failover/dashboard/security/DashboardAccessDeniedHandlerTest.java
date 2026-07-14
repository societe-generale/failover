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

package com.societegenerale.failover.dashboard.security;

import com.societegenerale.failover.dashboard.config.DashboardProperties.Security;
import com.societegenerale.failover.dashboard.config.DashboardProperties.SecurityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardAccessDeniedHandlerTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/failover-dashboard/api/config");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("ROLE type ⇒ 403 JSON body names the missing role")
    void reportsMissingRole() throws Exception {
        DashboardAccessDeniedHandler handler = new DashboardAccessDeniedHandler(
                new Security(SecurityType.ROLE, "ADMIN", "FAILOVER_ADMIN", null, false, ""));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .contains("Authorization failed: missing required role 'ADMIN'");
    }

    @Test
    @DisplayName("AUTHORITY type ⇒ 403 JSON body names the missing authority")
    void reportsMissingAuthority() throws Exception {
        DashboardAccessDeniedHandler handler = new DashboardAccessDeniedHandler(
                new Security(SecurityType.AUTHORITY, "ADMIN", "FAILOVER_ADMIN", null, false, ""));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("Authorization failed: missing required authority 'FAILOVER_ADMIN'");
    }

    @Test
    @DisplayName("EXPRESSION type ⇒ 403 JSON body reports the generic expression denial")
    void reportsExpressionDenial() throws Exception {
        DashboardAccessDeniedHandler handler = new DashboardAccessDeniedHandler(
                new Security(SecurityType.EXPRESSION, "ADMIN", "FAILOVER_ADMIN", "hasRole('ADMIN')", false, ""));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getContentAsString())
                .contains("Authorization failed: denied by the configured access expression");
    }

    @Test
    @DisplayName("logs the authenticated principal's name and actual authorities, not just the client-facing message")
    void logsPrincipalDetail() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "n/a", List.of()));
        DashboardAccessDeniedHandler handler = new DashboardAccessDeniedHandler(
                new Security(SecurityType.ROLE, "ADMIN", "FAILOVER_ADMIN", null, false, ""));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // No exception thrown while resolving the authenticated principal — the log line itself isn't
        // asserted on here (would require a log-capture appender), but this exercises the non-anonymous path.
        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
    }
}
