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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTenantResolverTest {

    private final TenantContextTenantResolver resolver = new TenantContextTenantResolver();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("returns null when TenantContext is empty")
    void returnsNullWhenContextIsEmpty() {
        assertThat(resolver.resolve()).isNull();
    }

    @Test
    @DisplayName("returns tenantId set in TenantContext")
    void returnsTenantIdWhenContextIsSet() {
        TenantContext.set("acme");
        assertThat(resolver.resolve()).isEqualTo("acme");
    }

    @Test
    @DisplayName("returns null after TenantContext is cleared")
    void returnsNullAfterContextCleared() {
        TenantContext.set("acme");
        TenantContext.clear();
        assertThat(resolver.resolve()).isNull();
    }

    @Test
    @DisplayName("reflects latest TenantContext value after update")
    void reflectsLatestContextValue() {
        TenantContext.set("acme");
        assertThat(resolver.resolve()).isEqualTo("acme");
        TenantContext.set("globex");
        assertThat(resolver.resolve()).isEqualTo("globex");
    }
}