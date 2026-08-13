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

package com.societegenerale.failover.observable.metrics;

/**
 * One row of the configuration view: a single {@code @Failover} point plus the global framework
 * settings echoed for convenience.
 *
 * <p>Per-annotation overrides that are left empty (e.g. {@code keyGenerator=""}) are normalised to
 * {@code "default"} by {@link FailoverConfigSnapshotService} to signal "framework default" in the UI.
 * Carries only annotation attributes and global <em>types</em> — never connection strings,
 * credentials, or payload data (data-minimisation).
 *
 * <p>Lives here (rather than in the dashboard module) so both the emitting service
 * ({@code ClusterSnapshotPublisher}, for the shared-store cluster tier) and every dashboard
 * {@code MetricsSource} can share the same shape without either depending on {@code FailoverScanner}.
 *
 * @param name            failover name
 * @param domain          effective store namespace ({@code domain()} if set, else {@code name})
 * @param expiryDuration  configured expiry duration
 * @param expiryUnit      configured expiry unit (e.g. {@code HOURS})
 * @param recoverAll      whether the failover recovers a collection ({@code recoverAll()})
 * @param payloadSplitter configured payload splitter bean name, or {@code "default"}
 * @param keyGenerator    configured key generator bean name, or {@code "default"}
 * @param expiryPolicy    configured expiry policy bean name, or {@code "default"}
 * @param storeType       global {@code failover.store.type}
 * @param executionType   global {@code failover.type}
 * @param exceptionPolicy global {@code failover.exception-policy}
 * @param asyncStore      global {@code failover.store.async}
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
