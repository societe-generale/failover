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

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Anand Manissery
 */
public class FailoverStoreInmemory<T> implements FailoverStore<T> {

    private final Map<String, ReferentialPayload<T>> store = new ConcurrentHashMap<>();

    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        store.put(storeKey(referentialPayload.getName(), referentialPayload.getKey()), referentialPayload);
    }

    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        store.remove(storeKey(referentialPayload.getName(), referentialPayload.getKey()));
    }

    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        return Optional.ofNullable(store.get(storeKey(name, key)));
    }

    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        store.entrySet().removeIf(entry -> expiry.isAfter(entry.getValue().getExpireOn()));
    }

    private String storeKey(String name, String key) {
        return name + "##" + key;
    }
}
