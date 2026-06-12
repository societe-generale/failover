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
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static com.societegenerale.failover.core.util.FailoverNameResolver.effectiveName;

/**
 * Default {@link FailoverHandler} that persists payloads to the failover store on success
 * and retrieves them on failure, applying expiry checks and payload enrichment.
 *
 * @param <T> the type of the payload managed by this handler
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
        if (payload == null) {
            log.debug("Failover store skipped for '{}': method returned null payload", failover.name());
            return null;
        }
        Class<T> clazz = cast(payload.getClass());
        var referentialPayload = payloadEnricher.enrichOnStore(failover, clazz, new ReferentialPayload<>(effectiveName(failover), keyGenerator.key(failover, args), true, clock.now(), expiryPolicy.computeExpiry(failover), payload));
        failoverStore.store(referentialPayload);
        log.info("Failover : Storing information on '{}' for failover. ReferentialPayload : {{}}", failover.name(), referentialPayload);
        return referentialPayload.getPayload();
    }

    @Override
    public @Nullable T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        log.info("Failover Recovery : Recovering information on '{}' from failover store due to exception {}", failover.name(), cause.getMessage());
        log.debug("Failover Recovery : Recovering information on '{}' from failover store", failover.name(), cause);
        var optionalReferential = failoverStore.find(effectiveName(failover), keyGenerator.key(failover, args));
        return doRecover(failover, clazz, cause, optionalReferential.orElse(null));
    }

    private T doRecover(Failover failover, Class<T> clazz, Throwable cause, ReferentialPayload<T> referentialPayload) {
        if(referentialPayload!=null) {
            if(!expiryPolicy.isExpired(failover, referentialPayload)) {
                log.info("Failover Recovery : Successfully recovered the information on '{}' from failover store. ReferentialPayload : {{}}", failover.name(), referentialPayload);
                return payloadEnricher.enrichOnRecover(failover, clazz, referentialPayload, cause).getPayload();
            }
            log.info("Failover Recovery : Deleting the expired payload on '{}' from failover store. ReferentialPayload : {{}}", failover.name(), referentialPayload);
            failoverStore.delete(referentialPayload);
        }
        log.warn("Failover Recovery : Could not recover information on '{}' from failover store, Either not found or expired for the given key!", failover.name());
        return payloadEnricher.enrichOnRecover(failover, clazz, null, cause).getPayload();
    }

    /**
     * Recovers every stored entry for the failover's referential via {@link FailoverStore#findAll},
     * applying the same expiry check and enrichment as {@link #recover}.
     *
     * <p>This is the live recover-all path: it is invoked at the slice level by
     * {@link ScatterGatherFailoverHandler} on its slice delegate (the no-ID-args / recover-all
     * scenario routed through {@code recover}). It is not a top-level execution entry point.
     *
     * @param failover annotation metadata for the failover point
     * @param args     unused for recover-all; the whole referential is recovered
     * @param clazz    expected payload type
     * @param cause    the exception that triggered recovery
     * @return the recovered payloads (expired entries are skipped/deleted)
     */
    @Override
    public List<T> recoverAll(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        List<ReferentialPayload<T>> referentialPayloads = failoverStore.findAll(effectiveName(failover));
        return referentialPayloads.stream().map(payload-> this.doRecover(failover, clazz, cause, payload)).toList();
    }

    @Override
    public void clean() {
        Instant cleanExpiry = clock.now();
        log.info("Failover : Executing the clean up on expired referential on {} ...", cleanExpiry);
        failoverStore.cleanByExpiry(cleanExpiry);
    }
}
