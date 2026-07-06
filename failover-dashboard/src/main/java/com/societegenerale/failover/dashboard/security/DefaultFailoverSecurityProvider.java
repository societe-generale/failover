package com.societegenerale.failover.dashboard.security;

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

import static com.societegenerale.failover.dashboard.config.DashboardProperties.SecurityType.ROLE;

/**
 * Default implementation of {@link FailoverSecurityProvider} for dashboard endpoints.
 *
 * <p>This provider applies a single authorization rule to all dashboard requests based on
 * {@link DashboardProperties.Security}: either `hasRole(...)` when the security type is {@code ROLE},
 *  or `hasAuthority(...)` otherwise, if security.allowInsecure() not true.
 */
public class DefaultFailoverSecurityProvider implements FailoverSecurityProvider {

    /**
     * Configures access control for dashboard endpoints from the provided security settings.
     *
     * @param auth the authorization registry used to declare request matchers and access rules
     * @param security dashboard security configuration properties
     */
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth, DashboardProperties.Security security) {
        if(security.allowInsecure()) {
            auth.anyRequest().permitAll();
        } else {
            if (security.type() == ROLE) {
                auth.anyRequest().hasRole(security.role());
            } else {
                auth.anyRequest().hasAuthority(security.authority());
            }
        }
    }
}
