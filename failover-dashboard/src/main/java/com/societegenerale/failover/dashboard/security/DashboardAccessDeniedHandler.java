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

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Reports {@code 403} denials from the dashboard's main UI/API gate with a specific, actionable reason
 * instead of Spring Security's default blank/generic response — "authorization failed: missing role
 * 'FAILOVER_ADMIN'" tells an operator exactly what to grant, rather than leaving them to guess why an
 * authenticated user still can't get in.
 *
 * <p>Scoped to the main gate only (not the peer-ingest chains, which only check {@code authenticated()} —
 * there's no role/authority to be missing there). The client-facing message names the configured
 * requirement (role/authority) but not the principal's actual authorities, to avoid handing out more
 * detail than necessary to a request that's already been identified as unauthorized; the full picture
 * (principal name + actual authorities) is logged server-side for operator diagnosis.
 *
 * @author Anand Manissery
 */
@Slf4j
public class DashboardAccessDeniedHandler implements AccessDeniedHandler {

    private final DashboardProperties.Security security;

    /**
     * Creates a new handler.
     *
     * @param security the bound {@code failover.dashboard.security.*} configuration
     */
    public DashboardAccessDeniedHandler(DashboardProperties.Security security) {
        this.security = security;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, @NonNull AccessDeniedException ex)
            throws IOException {
        String reason = reason();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication != null ? authentication.getName() : "anonymous";
        log.info("Authorization failed for '{}' on {} {} — {}. Principal authorities: {}.",
                principal, request.getMethod(), request.getRequestURI(), reason,
                authentication != null ? authentication.getAuthorities() : "none");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"Forbidden\",\"message\":\"Authorization failed: " + reason + "\"}");
    }

    private String reason() {
        return switch (security.type()) {
            case ROLE -> "missing required role '" + security.role() + "'";
            case AUTHORITY -> "missing required authority '" + security.authority() + "'";
            case EXPRESSION -> "denied by the configured access expression";
        };
    }
}
