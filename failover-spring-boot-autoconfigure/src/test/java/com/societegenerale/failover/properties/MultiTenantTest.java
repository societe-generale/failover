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

package com.societegenerale.failover.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTenantTest {

    private final MultiTenant multiTenant = new MultiTenant();

    @Test
    @DisplayName("enabled defaults to false")
    void enabledDefaultsToFalse() {
        assertThat(multiTenant.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("strategy defaults to TABLE_PREFIX")
    void strategyDefaultsToTablePrefix() {
        assertThat(multiTenant.getStrategy()).isEqualTo(MultiTenant.JdbcMultiTenantStrategy.TABLE_PREFIX);
    }

    @Test
    @DisplayName("defaultTenant defaults to null")
    void defaultTenantDefaultsToNull() {
        assertThat(multiTenant.getDefaultTenant()).isNull();
    }

    @Test
    @DisplayName("tenants defaults to empty map")
    void tenantsDefaultsToEmptyMap() {
        assertThat(multiTenant.getTenants()).isEmpty();
    }

    @Test
    @DisplayName("tenants map accepts new entries")
    void tenantsAcceptsEntries() {
        var config = new TenantConfig();
        config.setTablePrefix("ACME_");
        multiTenant.getTenants().put("acme", config);
        assertThat(multiTenant.getTenants()).containsKey("acme");
        assertThat(multiTenant.getTenants().get("acme").getTablePrefix()).isEqualTo("ACME_");
    }

    @Test
    @DisplayName("JdbcMultiTenantStrategy has exactly 2 values: TABLE_PREFIX, SCHEMA")
    void jdbcStrategyHasExpectedValues() {
        assertThat(MultiTenant.JdbcMultiTenantStrategy.values())
                .containsExactly(
                        MultiTenant.JdbcMultiTenantStrategy.TABLE_PREFIX,
                        MultiTenant.JdbcMultiTenantStrategy.SCHEMA);
    }

    @Test
    @DisplayName("enabled can be set to true")
    void enabledCanBeSetToTrue() {
        multiTenant.setEnabled(true);
        assertThat(multiTenant.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("strategy can be changed to SCHEMA")
    void strategyCanBeChangedToSchema() {
        multiTenant.setStrategy(MultiTenant.JdbcMultiTenantStrategy.SCHEMA);
        assertThat(multiTenant.getStrategy()).isEqualTo(MultiTenant.JdbcMultiTenantStrategy.SCHEMA);
    }

    @Test
    @DisplayName("defaultTenant can be set")
    void defaultTenantCanBeSet() {
        multiTenant.setDefaultTenant("acme");
        assertThat(multiTenant.getDefaultTenant()).isEqualTo("acme");
    }
}
