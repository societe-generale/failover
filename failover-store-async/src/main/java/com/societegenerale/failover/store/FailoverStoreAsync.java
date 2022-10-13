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
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @author Anand Manissery
 */
@Slf4j
@AllArgsConstructor
public class FailoverStoreAsync <T> implements FailoverStore<T> {

    @Getter
    private final FailoverStore<T> failoverStore;

    @Async
    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        log.info("Failover Store : Async call for storing information on '{}' for failover. ReferentialPayload : {{}}", referentialPayload.getName(), referentialPayload);
        getFailoverStore().store(referentialPayload);
    }

    @Async
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        log.info("Failover Store : Async call for deleting the expired payload on '{}' from failover store. ReferentialPayload : {{}}", referentialPayload.getName(), referentialPayload);
        getFailoverStore().delete(referentialPayload);
    }

    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        return getFailoverStore().find(name, key);
    }

    @Async
    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        failoverStore.cleanByExpiry(expiry);
    }
}
