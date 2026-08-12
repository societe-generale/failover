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

/**
 * Strategy interface used to contribute dashboard-specific authorization rules.
 *
 * <p>Implementations customize Spring Security request authorization for the failover dashboard endpoints
 * based on {@link DashboardProperties.Security} configuration. This allows declarative or programmatic
 * access control over dashboard surfaces (UI and API).
 *
 * <p><strong>Security Type Behavior:</strong>
 * <ul>
 *   <li>{@link DashboardProperties.SecurityType#AUTHORITY AUTHORITY} (default): Checks the configured
 *       {@code authority} using {@code hasAuthority(security.authority())}. Suitable for fine-grained
 *       authority or permission-based access control.</li>
 *   <li>{@link DashboardProperties.SecurityType#ROLE ROLE}: Checks the configured {@code role} using
 *       {@code hasRole(security.role())}. Suitable for role-based access control (RBAC).</li>
 *   <li>{@link DashboardProperties.SecurityType#EXPRESSION EXPRESSION}: Evaluates the configured SpEL
 *       {@code expression} (e.g. {@code "hasAnyRole('ADMIN') or hasAnyAuthority('WRITE_PRIVILEGE')"})
 *       via {@code WebExpressionAuthorizationManager}. Suitable for composite access rules.</li>
 * </ul>
 */
public interface FailoverSecurityProvider {

    /**
     * Applies authorization rules to the provided request matcher registry.
     *
     * <p>Implementations must inspect {@link DashboardProperties.Security#type()} to determine whether
     * to apply role-based ({@code hasRole}), authority-based ({@code hasAuthority}), or SpEL
     * expression-based checks to the registry.
     *
     * <p>Request matching for {@code context.basePath() + "/**"} is already applied by the caller
     * (via {@code HttpSecurity.securityMatcher}) before this method runs — implementations must not
     * re-match on {@link SecurityContext#basePath()}; it is provided for informational purposes only
     * (e.g. logging or path-aware SpEL expressions).
     *
     * @param auth    the authorization registry used to declare request matchers and access rules
     * @param context the dashboard's base path and security configuration (type, role, authority,
     *                expression, allowInsecure)
     */
    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth, SecurityContext context);
}
