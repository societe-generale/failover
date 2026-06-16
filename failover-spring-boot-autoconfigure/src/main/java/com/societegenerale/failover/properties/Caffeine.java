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

/**
 * Configuration properties for {@code failover.store.caffeine.*}.
 *
 * <p>The Caffeine store is bounded primarily by per-entry expiry. For workloads with very high key
 * cardinality, set {@link #maxSize} to also cap the entry count and bound heap use (audit I-15).
 *
 * @author Anand Manissery
 */
@Data
public class Caffeine {

    /**
     * Maximum number of entries Caffeine retains. Once exceeded, Caffeine evicts entries by its
     * size-based (Window TinyLFU) policy. Default {@code 10000} (matching the in-memory store's
     * {@code max-entries}), which caps heap use while comfortably holding typical referential datasets.
     * Set {@code 0} (or negative) for an unbounded cache, limited only by per-entry expiry.
     */
    private long maxSize = 10_000;
}
