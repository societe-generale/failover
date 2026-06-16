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
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the embedded failover dashboard.
 *
 * <p><strong>Secure-by-default:</strong> {@code enabled} defaults to {@code false}. Nothing is
 * mapped or served until the consumer explicitly sets {@code failover.dashboard.enabled=true}
 * in YAML. See the dashboard design document, section 1a.
 *
 * <p>P0 scope: only the master switch and {@code basePath} are wired. Exposure, security, history
 * and health-threshold properties land in later phases.
 *
 * @author Anand Manissery
 */
@ConfigurationProperties(prefix = "failover.dashboard")
public record DashboardProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("/failover-dashboard") String basePath
) {
}
