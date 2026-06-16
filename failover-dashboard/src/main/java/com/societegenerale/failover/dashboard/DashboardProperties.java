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

package com.societegenerale.failover.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the embedded failover dashboard.
 *
 * <p><strong>Secure-by-default:</strong> {@code enabled} defaults to {@code false}. Nothing is
 * mapped or served until the consumer explicitly sets {@code failover.dashboard.enabled=true}
 * in YAML. See the dashboard design document, section 1a.
 *
 * <p>The {@code basePath} is the single dedicated namespace under which <em>every</em> dashboard
 * surface lives — the static UI ({@code basePath/**}) and the JSON API ({@code basePath/api/**}).
 * Override it in YAML to relocate the whole dashboard:
 * <pre>
 * failover:
 *   dashboard:
 *     enabled: true
 *     base-path: /ops/failover
 * </pre>
 * It must be a dedicated, non-root prefix so the dashboard cannot collide with the consumer's own
 * services: it must start with {@code '/'}, must not be the root {@code '/'}, and must not end with
 * a trailing {@code '/'}. A misconfigured value fails the context fast with a clear message — the
 * accepted value is then used verbatim by both the resource handler and the controller mapping, so
 * the two surfaces can never drift apart.
 *
 * <p>P0/P1 scope: master switch, {@code basePath}, config view. Exposure, security, history and
 * health-threshold properties land in later phases.
 *
 * @author Anand Manissery
 */
@ConfigurationProperties(prefix = "failover.dashboard")
public record DashboardProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("/failover-dashboard") String basePath,
    @DefaultValue Health health
) {
    /** Canonical, binder-targeted constructor — validates the base path fail-fast. */
    @ConstructorBinding
    public DashboardProperties {
        if (basePath == null || basePath.isBlank() || !basePath.startsWith("/")
                || basePath.equals("/") || basePath.endsWith("/")) {
            throw new IllegalArgumentException(
                "failover.dashboard.base-path must be a dedicated, non-root path starting with '/' "
                    + "and without a trailing '/' (e.g. '/failover-dashboard'), but was '" + basePath + "'");
        }
    }

    /** Convenience constructor applying default health thresholds (used in tests/programmatic setup). */
    public DashboardProperties(boolean enabled, String basePath) {
        this(enabled, basePath, new Health(0.99, 0.90));
    }

    /**
     * Health-classification thresholds on the {@code healthyRate} (design doc §4.4):
     * {@code HEALTHY} when {@code rate >= degradedThreshold}, {@code DEGRADED} when
     * {@code rate >= unhealthyThreshold}, otherwise {@code UNHEALTHY}.
     *
     * @param degradedThreshold  healthy-rate floor for {@code HEALTHY} (default {@code 0.99})
     * @param unhealthyThreshold healthy-rate floor for {@code DEGRADED} (default {@code 0.90})
     */
    public record Health(
        @DefaultValue("0.99") double degradedThreshold,
        @DefaultValue("0.90") double unhealthyThreshold
    ) {
    }
}
