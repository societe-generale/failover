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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author Anand Manissery
 */
@Slf4j
@AllArgsConstructor
public class DefaultFailoverHandler<T> implements FailoverHandler<T> {

    private final KeyGenerator keyGenerator;

    private final FailoverClock clock;

    private final FailoverStore<T> failoverStore;

    private final ExpiryPolicy<T> expiryPolicy;

    private final PayloadEnricher<T> payloadEnricher;

    @Override
    public T store(Failover failover, List<Object> args, T payload) {
        ReferentialPayload<T> referentialPayload = payloadEnricher.enrich(failover, new ReferentialPayload<>(failover.name(), keyGenerator.key(failover, args), true, clock.now(), expiryPolicy.computeExpiry(failover), payload));
        failoverStore.store(referentialPayload);
        log.info("Failover : Storing information on '{}' for failover. ReferentialPayload : {{}}", failover.name(), referentialPayload);
        return referentialPayload.getPayload();
    }

    @Override
    public T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        log.debug("Failover Recovery : Recovering information on '{}' from failover store due to exception : ", failover.name(), cause);
        log.info("Failover Recovery : Recovering information on '{}' from failover store due to exception {}", failover.name(), cause.getMessage());
        Optional<ReferentialPayload<T>> optionalReferential = failoverStore.find(failover.name(), keyGenerator.key(failover, args));
        if(optionalReferential.isPresent()) {
            ReferentialPayload<T> referentialPayload =  optionalReferential.get();
            referentialPayload.setUpToDate(false);
            if(!expiryPolicy.isExpired(failover, referentialPayload)) {
                log.info("Failover Recovery : Successfully recovered the information on '{}' from failover store. ReferentialPayload : {{}}", failover.name(), referentialPayload);
                return payloadEnricher.enrich(failover, referentialPayload).getPayload();
            }
            log.info("Failover Recovery : Deleting the expired payload on '{}' from failover store. ReferentialPayload : {{}}", failover.name(), referentialPayload);
            failoverStore.delete(referentialPayload);
        }
        log.warn("Failover Recovery : Could not recover information on '{}' from failover store, Either not found or expired for the given key!", failover.name());
        return null;
    }

    @Override
    public void clean() {
        LocalDateTime cleanExpiry = clock.now();
        log.info("Failover : Executing the clean up on expired referential on {} ...", cleanExpiry);
        failoverStore.cleanByExpiry(cleanExpiry);
    }
}
