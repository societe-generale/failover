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

package com.societegenerale.failover.store.multitenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedTenantResolverTest {

    @Test
    @DisplayName("always returns the same tenant ID")
    void alwaysReturnsSameTenantId() {
        var resolver = new FixedTenantResolver("acme");
        assertThat(resolver.resolve()).isEqualTo("acme");
        assertThat(resolver.resolve()).isEqualTo("acme");
    }

    @Test
    @DisplayName("returns different tenants for different instances")
    void differentInstancesDifferentTenants() {
        assertThat(new FixedTenantResolver("acme").resolve()).isEqualTo("acme");
        assertThat(new FixedTenantResolver("globex").resolve()).isEqualTo("globex");
    }

    @Test
    @DisplayName("can hold null tenant — useful for testing default-tenant fallback")
    void canHoldNullTenant() {
        assertThat(new FixedTenantResolver(null).resolve()).isNull();
    }
}