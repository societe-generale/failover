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

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Extension point for plugging an arbitrary authentication mechanism onto the dashboard's own
 * {@code HttpSecurity} chain, without re-implementing the parts every mechanism shares: the
 * {@code securityMatcher(basePath + "/**")}, the {@link FailoverSecurityProvider} authorization
 * wiring, and the {@link DashboardAccessDeniedHandler}.
 *
 * <p>Declare a bean of this type to secure the dashboard UI/API with anything the built-in
 * mechanisms (HTTP Basic, OAuth2 login, OAuth2 resource server) don't cover — a trusted-header
 * identity forwarded by a reverse proxy (e.g. oauth2-proxy, Envoy), SAML, mTLS-derived principals,
 * an existing enterprise {@code AuthenticationProvider}, or a {@code client_credentials}-only shop
 * that wants the dashboard to sit behind its own gateway's authentication entirely. Takes priority
 * over every built-in mechanism when a bean of this type is present.
 *
 * <p>{@code configure} is called after {@code securityMatcher} and {@code authorizeHttpRequests}
 * are already applied to the given {@code http} — implementations add only the authentication step
 * (e.g. {@code http.oauth2ResourceServer(...)}, {@code http.addFilterBefore(...)}, {@code http.x509(...)})
 * and must not call {@code securityMatcher} or {@code authorizeHttpRequests} again.
 *
 * @author Anand Manissery
 */
public interface DashboardAuthenticationConfigurer {

    /**
     * Applies the authentication mechanism to the dashboard's {@code HttpSecurity} chain.
     *
     * @param http    the dashboard's {@code HttpSecurity}, already scoped to {@code basePath + "/**"}
     *                with authorization rules applied
     * @param context the dashboard's base path and security configuration (informational — request
     *                 matching is already applied by the caller)
     * @throws Exception as {@code HttpSecurity} configuration methods themselves declare
     */
    void configure(HttpSecurity http, SecurityContext context) throws Exception;
}
