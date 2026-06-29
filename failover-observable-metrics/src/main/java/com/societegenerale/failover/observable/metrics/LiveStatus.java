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
 * Whether a cluster instance is currently reachable, as determined by the optional heartbeat
 * mechanism. {@code UNKNOWN} is the default when liveness tracking is disabled — the last-known
 * metrics are still shown, without any up/down classification.
 *
 * @author Anand Manissery
 */
public enum LiveStatus {

    /** Liveness tracking is disabled on the dashboard side — status is not determined. */
    UNKNOWN,

    /** Instance is actively sending heartbeats within the configured liveness window. */
    LIVE,

    /** No heartbeat received within the liveness window — instance is considered down. */
    DOWN
}
