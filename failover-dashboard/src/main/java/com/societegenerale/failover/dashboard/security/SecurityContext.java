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

/**
 * Context passed to {@link FailoverSecurityProvider#configure} — the dashboard's base path plus its
 * {@link DashboardProperties.Security} configuration.
 *
 * <p>{@code basePath} is informational (e.g. for logging or path-aware SpEL expressions) — request
 * matching for {@code basePath/**} is already applied by the caller via {@code HttpSecurity.securityMatcher}
 * before {@code configure} runs, so implementations must not re-match on it.
 *
 * @param basePath the dashboard's dedicated base path (e.g. {@code /failover-dashboard})
 * @param security access-control configuration (type, role, authority, expression, allowInsecure)
 */
public record SecurityContext(String basePath, DashboardProperties.Security security) {
}
