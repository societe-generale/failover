/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.it.service;

import com.societegenerale.failover.it.domain.ThirdPartiesResult;
import com.societegenerale.failover.it.domain.ThirdParty;

/**
 * Service interface exercised by the failover integration tests.
 *
 * <p>Each method covers a distinct failover configuration. The {@code @Failover} annotations
 * are intentionally placed on the implementation class ({@link ThirdPartyServiceImpl}) rather
 * than here, because Spring AOP (CGLIB proxy) intercepts annotations on the concrete class
 * method — not on the interface declaration.
 *
 * <ul>
 *   <li>{@link #fetchOne} — single-key, default expiry, default key generator</li>
 *   <li>{@link #fetchOneImmediatelyExpired} — single-key, custom expiry policy that sets expiry in the past</li>
 *   <li>{@link #fetchOneWithFixedKey} — single-key, custom key generator that always returns the same key</li>
 *   <li>{@link #fetchAll} — scatter/gather with custom payload splitter</li>
 * </ul>
 *
 * @author Anand Manissery
 */
public interface ThirdPartyService {

    /**
     * Single-key failover — default expiry (1 hour) and default key generator.
     * Exercises: store-on-success, recover-on-failure, expiry via DB manipulation.
     */
    ThirdParty fetchOne(String id);

    /**
     * Single-key failover — custom {@code immediatelyExpiredExpiryPolicy} stores entries
     * with an expiry timestamp already in the past.
     * Exercises: recovery returns {@code null} for immediately-expired entries.
     */
    ThirdParty fetchOneImmediatelyExpired(String id);

    /**
     * Single-key failover — custom {@code fixedKeyGenerator} always returns the same key
     * regardless of method arguments.
     * Exercises: all argument variants map to one database row.
     */
    ThirdParty fetchOneWithFixedKey(String id);

    /**
     * Scatter-gather failover — {@code itThirdPartyPayloadSplitter} splits the composite result
     * into individual per-ID slices (args shape: {@code ["status", "csvIds", "region"]}).
     * Exercises: store scatters N rows; recover gathers N slices and merges; partial recovery.
     */
    ThirdPartiesResult fetchAll(String status, String csvIds, String region);
}