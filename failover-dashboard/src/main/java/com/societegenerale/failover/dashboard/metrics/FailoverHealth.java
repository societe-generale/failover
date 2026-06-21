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

package com.societegenerale.failover.dashboard.metrics;

import java.util.Map;

/**
 * Actuator-style overall failover health, mirroring the {@code /actuator/health/failover} contributor:
 * {@code UP} when at least one {@code @Failover} is registered, {@code DOWN} when none are discovered
 * (a strong misconfiguration signal). {@code details} carries the global configuration (types and flags
 * only — never connection strings or payload data, design doc §9).
 *
 * @param status  {@code UP} | {@code DOWN}
 * @param details ordered config key/values (e.g. {@code enabled}, {@code type}, {@code store.type})
 */
public record FailoverHealth(
        String status,
        Map<String, String> details) {
}
