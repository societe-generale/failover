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

package com.societegenerale.failover.dashboard.web;

/**
 * Marker for controllers that receive peer-to-peer pushes ({@code cluster.mode=shared-store}) rather than
 * serving a UI-facing read. Implemented by {@link ClusterSnapshotController} and
 * {@link ClusterHeartbeatController}.
 *
 * <p>{@link DashboardExposureInterceptor} exempts any handler implementing this interface from
 * {@code exposure.include} narrowing — that allow-list governs the dashboard's read API, not peer ingest,
 * which already has its own dedicated access control (see {@code DashboardAutoConfiguration}'s ingest
 * filter chains). Implement this on any future ingest endpoint to get the same exemption automatically.
 *
 * @author Anand Manissery
 */
public interface PeerIngestEndpoint {
}
