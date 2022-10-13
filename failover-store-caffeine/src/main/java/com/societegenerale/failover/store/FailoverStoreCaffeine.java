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

package com.societegenerale.failover.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.FailoverStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.societegenerale.failover.store.TimeUnitConverter.convert;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * @author Anand Manissery
 */
@AllArgsConstructor
@Slf4j
public class FailoverStoreCaffeine<T> implements FailoverStore<T> {

    private final Map<String, Cache<String, ReferentialPayload<T>>> store = new ConcurrentHashMap<>();

    private final FailoverScanner failoverScanner;

    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        Failover failover = failoverScanner.findFailoverByName(referentialPayload.getName());
        Cache<String, ReferentialPayload<T>> cache = store.computeIfAbsent(referentialPayload.getName(),
                name -> Caffeine.newBuilder().expireAfterWrite(failover.expiryDuration(), convert(failover.expiryUnit())).build());
        cache.put(storeKey(referentialPayload.getName(), referentialPayload.getKey()), referentialPayload);
    }

    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        if(store.containsKey(referentialPayload.getName())) {
            store.get(referentialPayload.getName()).invalidate(storeKey(referentialPayload.getName(), referentialPayload.getKey()));
        }
    }

    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        if(store.containsKey(name)) {
            return ofNullable(store.get(name).get(storeKey(name, key), k -> null));
        }
        return empty();
    }

    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        log.debug("Ignoring the clean up as the expiry is already managed by Caffeine Cache with 'expireAfterWrite'");
    }

    private String storeKey(String name, String key) {
        return name + "##" + key;
    }
}