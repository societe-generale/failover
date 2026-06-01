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

package com.societegenerale.failover.store.multitenant;

import com.societegenerale.failover.core.store.FailoverStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.societegenerale.failover.store.multitenant.TenantStoreFactory.SINGLE_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TenantStoreFactoryTest {

    @Test
    @DisplayName("SINGLE_TENANT_ID sentinel constant is '_single_'")
    void singleTenantIdSentinelValue() {
        assertThat(SINGLE_TENANT_ID).isEqualTo("_single_");
    }

    @Test
    @DisplayName("factory as lambda creates distinct store instances per tenant")
    void lambdaFactoryCreatesDistinctInstances() {
        TenantStoreFactory<Object> factory = tenantId -> mock(FailoverStore.class);

        FailoverStore<Object> storeA = factory.create("acme");
        FailoverStore<Object> storeB = factory.create("globex");

        assertThat(storeA).isNotSameAs(storeB);
    }

    @Test
    @DisplayName("factory create with SINGLE_TENANT_ID does not throw")
    void singleTenantIdCreateDoesNotThrow() {
        TenantStoreFactory<Object> factory = tenantId -> mock(FailoverStore.class);
        assertThat(factory.create(SINGLE_TENANT_ID)).isNotNull();
    }

    @Test
    @DisplayName("factory as lambda can return the same instance per tenant (shared store)")
    void lambdaFactoryCanReturnSharedInstance() {
        FailoverStore<Object> shared = mock(FailoverStore.class);
        TenantStoreFactory<Object> factory = tenantId -> shared;

        assertThat(factory.create("acme")).isSameAs(shared);
        assertThat(factory.create("globex")).isSameAs(shared);
    }
}