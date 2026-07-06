package com.societegenerale.failover.dashboard.security;

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

import static com.societegenerale.failover.dashboard.config.DashboardProperties.SecurityType.ROLE;

/**
 * Default implementation of {@link FailoverSecurityProvider} for dashboard endpoints.
 *
 * <p>This provider configures Spring Security authorization based on the dashboard's
 * {@link DashboardProperties.Security} configuration. It applies a single authorization rule
 * to all dashboard requests, choosing between role-based and authority-based access control:
 * <ul>
 *   <li>When {@link DashboardProperties.Security#type()} is {@code ROLE}: requires
 *       {@code hasRole(security.role())}</li>
 *   <li>When {@link DashboardProperties.Security#type()} is {@code AUTHORITY} (default): requires
 *       {@code hasAuthority(security.authority())}</li>
 * </ul>
 *
 * <p>The {@code role} property is used for RBAC scenarios (e.g., "ADMIN", "OPERATOR"), while
 * {@code authority} is typically used for permission-based or fine-grained access control
 * (e.g., "FAILOVER_ADMIN", "FAILOVER_READ").
 *
 * <p>Example configurations:
 * <pre>
 * # Role-based (requires role ADMIN)
 * failover.dashboard.security.type: ROLE
 * failover.dashboard.security.role: ADMIN
 *
 * # Authority-based (requires authority FAILOVER_ADMIN)
 * failover.dashboard.security.type: AUTHORITY
 * failover.dashboard.security.authority: FAILOVER_ADMIN
 * </pre>
 */
public class DefaultFailoverSecurityProvider implements FailoverSecurityProvider {

    /**
     * Configures authorization for all dashboard endpoints based on the security type.
     *
     * <p>Implementation logic:
     * <ul>
     *   <li>If type is {@code ROLE}: applies {@code hasRole(security.role())} to all requests</li>
     *   <li>Otherwise (type is {@code AUTHORITY}): applies {@code hasAuthority(security.authority())} to all requests</li>
     * </ul>
     *
     * @param auth the authorization registry used to declare request matchers and access rules
     * @param security dashboard security configuration with security type (ROLE or AUTHORITY)
     *                  and the corresponding role/authority name
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
