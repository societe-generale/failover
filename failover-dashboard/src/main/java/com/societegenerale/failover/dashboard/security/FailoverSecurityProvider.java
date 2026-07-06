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
 *   <li>{@link DashboardProperties.SecurityType#ROLE ROLE}: Checks the configured {@code role} using
 *       {@code hasRole(security.role())}. Suitable for role-based access control (RBAC).</li>
 *   <li>{@link DashboardProperties.SecurityType#AUTHORITY AUTHORITY} (default): Checks the configured
 *       {@code authority} using {@code hasAuthority(security.authority())}. Suitable for fine-grained
 *       authority or permission-based access control.</li>
 * </ul>
 */
public interface FailoverSecurityProvider {

    /**
     * Applies authorization rules to the provided request matcher registry.
     *
     * <p>Implementations must inspect {@link DashboardProperties.Security#type()} to determine whether
     * to apply role-based ({@code hasRole}) or authority-based ({@code hasAuthority}) checks to the registry.
     *
     * @param auth the authorization registry used to declare request matchers and access rules
     * @param security dashboard security configuration properties, including the security type
     *                  ({@link DashboardProperties.SecurityType#ROLE ROLE} or
     *                  {@link DashboardProperties.SecurityType#AUTHORITY AUTHORITY}),
     *                  and the corresponding role or authority value
     */
    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth, DashboardProperties.Security security);
}
