package com.societegenerale.failover.dashboard.security;

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Strategy interface used to contribute dashboard-specific authorization rules.
 * <p>Implementations customize Spring Security request authorization for the failover dashboard endpoints, based on dashboard security properties.
 */
public interface FailoverSecurityProvider {

    /**
     * Applies authorization rules to the provided request matcher registry.
     *
     * @param auth the authorization registry used to declare request matchers and access rules
     * @param security dashboard security configuration properties
     */
    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth, DashboardProperties.Security security);
}
