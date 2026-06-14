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

package com.societegenerale.failover.properties;

import lombok.Data;

import java.time.Duration;

/**
 * Scatter/gather configuration for the failover framework.
 *
 * <p>When a {@code payloadSplitter} is specified on a {@code @Failover}-annotated method,
 * the framework splits the composite payload into per-slice store entries and gathers
 * them back on recovery. This property controls whether the slice operations run in parallel.
 *
 * @author Anand Manissery
 */
@Data
public class Scatter {

    /**
     * Whether to dispatch scatter slices in parallel using virtual threads.
     *
     * <p>When {@code true} (default), each slice is submitted to the {@code scatterGatherExecutor}
     * concurrently. When {@code false}, slices are processed sequentially on the calling thread.
     */
    private boolean parallel = true;

    /**
     * Per-slice timeout for the parallel scatter path (ignored when {@link #parallel} is {@code false}).
     *
     * <p>A hung slice (e.g. JDBC connection-pool exhaustion on a slice store) would otherwise block
     * the business thread indefinitely on {@code join()}. On timeout a recover slice is treated as
     * not recovered (no data from that slice) rather than hanging the caller; a store slice surfaces
     * the timeout, which is already isolated by the failover execution layer.
     *
     * <p>Default {@code 10s}. Set to {@code null}/empty to wait indefinitely (legacy behaviour).
     */
    private Duration timeout = Duration.ofSeconds(10);
}
