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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverStoreAsyncTest {

    @Mock
    private ReferentialPayload<String> referentialPayload;

    @Mock
    private FailoverStore<String> failoverStore;

    private FailoverStoreAsync<String> failoverStoreAsync;

    @BeforeEach
    void setUp() {
        failoverStoreAsync = new FailoverStoreAsync<>(failoverStore);
    }

    @Test
    void shouldCallStore() {
        failoverStoreAsync.store(referentialPayload);
        verify(failoverStore).store(referentialPayload);
    }

    @Test
    void shouldCallDelete() {
        failoverStoreAsync.delete(referentialPayload);
        verify(failoverStore).delete(referentialPayload);
    }

    @Test
    void shouldCallFind() {
        failoverStoreAsync.find("name", "key");
        verify(failoverStore).find("name", "key");
    }

    @Test
    void shouldCallCleanByExpiry() {
        LocalDateTime now = LocalDateTime.now();
        failoverStoreAsync.cleanByExpiry(now);
        verify(failoverStore).cleanByExpiry(now);
    }
}