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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.it.domain.ThirdPartiesResult;
import com.societegenerale.failover.it.domain.ThirdParty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;

/**
 * Controllable implementation of {@link ThirdPartyService} for integration tests.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>All 10 referential entries are pre-loaded in an immutable map. Tests never change this
 *       data; they only control whether the primary call succeeds or fails via
 *       {@link ThirdPartyServiceController}.</li>
 *   <li>Every method returns a <em>copy</em> of the stored entry, so the failover framework
 *       can freely mutate the returned object (setting {@code upToDate}, {@code asOf},
 *       {@code metadata}) without corrupting the internal reference data.</li>
 *   <li>Spring AOP (CGLIB proxy) intercepts {@code @Failover} annotations on the concrete
 *       class method, so each override repeats the annotation from the interface declaration.</li>
 * </ul>
 *
 * @author Anand Manissery
 */
@Service
@RequiredArgsConstructor
public class ThirdPartyServiceImpl implements ThirdPartyService {

    /**
     * Immutable referential: IDs 1–10, names "ThirdParty-1".."ThirdParty-10",
     * scores 100, 200, …, 1000.
     */

    private final RemoteThirdPartyService remoteService;
    // ── Service methods ───────────────────────────────────────────────────────

    @Override
    @Failover(name = "it-tp-single", expiryDuration = 1, expiryUnit = ChronoUnit.HOURS)
    public ThirdParty fetchOne(String id) {
        return remoteService.fetchOne(id);
    }

    @Override
    @Failover(name = "it-tp-expired", expiryDuration = 1, expiryUnit = ChronoUnit.HOURS,
              expiryPolicy = "immediatelyExpiredExpiryPolicy")
    public ThirdParty fetchOneImmediatelyExpired(String id) {
        return remoteService.fetchOne(id);
    }

    @Override
    @Failover(name = "it-tp-fixed-key", expiryDuration = 1, expiryUnit = ChronoUnit.HOURS,
              keyGenerator = "fixedKeyGenerator")
    public ThirdParty fetchOneWithFixedKey(String id) {
        return remoteService.fetchOne(id);
    }

    @Override
    @Failover(name = "it-tp-scatter", expiryDuration = 1, expiryUnit = ChronoUnit.HOURS,
              payloadSplitter = "itThirdPartyPayloadSplitter")
    public ThirdPartiesResult fetchAll(String status, String csvIds, String region) {
        return remoteService.fetchAllIn(csvIds);
    }
}