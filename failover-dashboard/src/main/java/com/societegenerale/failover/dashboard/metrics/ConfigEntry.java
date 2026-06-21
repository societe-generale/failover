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

/**
 * One row of the configuration view: a single {@code @Failover} point plus the global framework
 * settings echoed for convenience.
 *
 * <p>Per-annotation overrides that are left empty (e.g. {@code keyGenerator=""}) are normalised to
 * {@code "default"} by {@code DashboardConfigService} to signal "framework default" in the UI.
 * Carries only annotation attributes and global <em>types</em> — never connection strings,
 * credentials, or payload data (design doc §9, data-minimisation).
 *
 * @author Anand Manissery
 */
public record ConfigEntry(
        String name,
        String domain,
        long expiryDuration,
        String expiryUnit,
        boolean recoverAll,
        String payloadSplitter,
        String keyGenerator,
        String expiryPolicy,
        // global (same for all points, echoed for convenience):
        String storeType,
        String executionType,
        String exceptionPolicy,
        boolean asyncStore) {
}
