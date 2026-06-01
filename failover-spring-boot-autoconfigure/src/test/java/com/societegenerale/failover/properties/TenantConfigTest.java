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

package com.societegenerale.failover.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantConfigTest {

    private final TenantConfig tenantConfig = new TenantConfig();

    @Test
    @DisplayName("tablePrefix defaults to empty string")
    void tablePrefixDefaultsToEmptyString() {
        assertThat(tenantConfig.getTablePrefix()).isEmpty();
    }

    @Test
    @DisplayName("schema defaults to null")
    void schemaDefaultsToNull() {
        assertThat(tenantConfig.getSchema()).isNull();
    }

    @Test
    @DisplayName("tablePrefix can be set")
    void tablePrefixCanBeSet() {
        tenantConfig.setTablePrefix("ACME_");
        assertThat(tenantConfig.getTablePrefix()).isEqualTo("ACME_");
    }

    @Test
    @DisplayName("schema can be set")
    void schemaCanBeSet() {
        tenantConfig.setSchema("acme_schema");
        assertThat(tenantConfig.getSchema()).isEqualTo("acme_schema");
    }
}
