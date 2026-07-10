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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DefaultFailoverSecurityProviderTest {

    private final DefaultFailoverSecurityProvider provider = new DefaultFailoverSecurityProvider();

    private SecurityContext context(Security security) {
        return new SecurityContext("/failover-dashboard", security);
    }

    @SuppressWarnings("unchecked")
    private AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry() {
        return mock(AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry.class);
    }

    private AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizedUrl authorizedUrl(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizedUrl authorizedUrl = mock(AuthorizeHttpRequestsConfigurer.AuthorizedUrl.class);
        when(registry.anyRequest()).thenReturn(authorizedUrl);
        return authorizedUrl;
    }

    @Test
    @DisplayName("type=ROLE ⇒ hasRole(security.role())")
    void roleType() {
        var registry = registry();
        var authorizedUrl = authorizedUrl(registry);

        provider.configure(registry, context(new Security(SecurityType.ROLE, "ADMIN", "FAILOVER_ADMIN", null, false)));

        verify(authorizedUrl).hasRole("ADMIN");
        verifyNoMoreInteractions(authorizedUrl);
    }

    @Test
    @DisplayName("type=AUTHORITY ⇒ hasAuthority(security.authority())")
    void authorityType() {
        var registry = registry();
        var authorizedUrl = authorizedUrl(registry);

        provider.configure(registry, context(new Security(SecurityType.AUTHORITY, "ADMIN", "FAILOVER_ADMIN", null, false)));

        verify(authorizedUrl).hasAuthority("FAILOVER_ADMIN");
        verifyNoMoreInteractions(authorizedUrl);
    }

    @Test
    @DisplayName("type=EXPRESSION ⇒ access(...) evaluates the configured SpEL expression")
    void expressionType() {
        var registry = registry();
        var authorizedUrl = authorizedUrl(registry);
        String expression = "hasAnyRole('ADMIN') or hasAnyAuthority('WRITE_PRIVILEGE')";

        provider.configure(registry, context(new Security(SecurityType.EXPRESSION, "ADMIN", "FAILOVER_ADMIN", expression, false)));

        verify(authorizedUrl).access(any(AuthorizationManager.class));
        verifyNoMoreInteractions(authorizedUrl);
    }

    @Test
    @DisplayName("allowInsecure=true ⇒ permitAll(), regardless of type")
    void allowInsecure() {
        var registry = registry();
        var authorizedUrl = authorizedUrl(registry);

        provider.configure(registry, context(new Security(SecurityType.EXPRESSION, "ADMIN", "FAILOVER_ADMIN", "hasRole('ADMIN')", true)));

        verify(authorizedUrl).permitAll();
        verifyNoMoreInteractions(authorizedUrl);
    }

    @Test
    @DisplayName("type=EXPRESSION with blank expression fails fast at construction")
    void blankExpressionRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new Security(SecurityType.EXPRESSION, "ADMIN", "FAILOVER_ADMIN", "  ", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failover.dashboard.security.expression");
    }
}
