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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

import static com.societegenerale.failover.dashboard.config.DashboardProperties.SecurityType.EXPRESSION;
import static com.societegenerale.failover.dashboard.config.DashboardProperties.SecurityType.ROLE;

/**
 * Default implementation of {@link FailoverSecurityProvider} for dashboard endpoints.
 *
 * <p>This provider configures Spring Security authorization based on the dashboard's
 * {@link DashboardProperties.Security} configuration. It applies a single authorization rule
 * to all dashboard requests, choosing between role-based, authority-based, and SpEL-expression
 * access control:
 * <ul>
 *   <li>When {@link DashboardProperties.Security#type()} is {@code AUTHORITY} (default): requires
 *       {@code hasAuthority(security.authority())}</li>
 *   <li>When {@link DashboardProperties.Security#type()} is {@code ROLE}: requires
 *       {@code hasRole(security.role())}</li>
 *   <li>When {@link DashboardProperties.Security#type()} is {@code EXPRESSION}: evaluates the
 *       configured SpEL web-security expression via {@link WebExpressionAuthorizationManager}</li>
 * </ul>
 *
 * <p>The {@code role} property is used for RBAC scenarios (e.g., "ADMIN", "OPERATOR"), while
 * {@code authority} is typically used for permission-based or fine-grained access control
 * (e.g., "FAILOVER_ADMIN", "FAILOVER_READ").
 *
 * <p>Example configurations:
 * <pre>
 * # Authority-based (default; requires authority FAILOVER_ADMIN)
 * failover.dashboard.security.type: AUTHORITY
 * failover.dashboard.security.authority: FAILOVER_ADMIN
 *
 * # Role-based (requires role ADMIN)
 * failover.dashboard.security.type: ROLE
 * failover.dashboard.security.role: ADMIN
 *
 * # Expression-based (SpEL)
 * failover.dashboard.security.type: EXPRESSION
 * failover.dashboard.security.expression: "hasAnyRole('ADMIN') or hasAnyAuthority('WRITE_PRIVILEGE')"
 * </pre>
 */
public class DefaultFailoverSecurityProvider implements FailoverSecurityProvider {

    /**
     * Configures authorization for all dashboard endpoints based on the security type.
     *
     * <p>Implementation logic:
     * <ul>
     *   <li>If type is {@code ROLE}: applies {@code hasRole(security.role())} to all requests</li>
     *   <li>If type is {@code AUTHORITY}: applies {@code hasAuthority(security.authority())} to all requests</li>
     *   <li>If type is {@code EXPRESSION}: evaluates {@code security.expression()} via
     *       {@link WebExpressionAuthorizationManager} for all requests</li>
     * </ul>
     *
     * @param auth    the authorization registry used to declare request matchers and access rules
     * @param context the dashboard's base path (unused here — matching is already scoped by the
     *                caller) and security configuration, with security type (ROLE, AUTHORITY, or
     *                EXPRESSION) and the corresponding role/authority/expression
     */
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth, SecurityContext context) {
        DashboardProperties.Security security = context.security();
        if(security.allowInsecure()) {
            auth.anyRequest().permitAll();
        } else if (security.type() == ROLE) {
            auth.anyRequest().hasRole(security.role());
        } else if (security.type() == EXPRESSION) {
            auth.anyRequest().access(new WebExpressionAuthorizationManager(security.expression()));
        } else {
            auth.anyRequest().hasAuthority(security.authority());
        }
    }
}
