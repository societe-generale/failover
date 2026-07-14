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

/**
 * Marker bean confirming the dashboard's UI security gate has a way to authenticate someone.
 * Its presence in the context means the startup check in {@code DashboardAutoConfiguration}'s
 * {@code dashboardAuthBackingValidator} bean ran and either found a {@code UserDetailsService} /
 * {@code AuthenticationProvider} to back {@code httpBasic()}, or the posture doesn't require one
 * ({@code allow-insecure=true}, {@code security.type=EXPRESSION}, or an OAuth2 login / custom
 * {@code dashboardSecurityFilterChain} override already handling authentication itself).
 *
 * @author Anand Manissery
 */
public final class DashboardAuthBackingValidator {
}
