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

class JdbcTest {

    private final Jdbc jdbc = new Jdbc();

    @Test
    @DisplayName("tablePrefix defaults to empty string")
    void tablePrefixDefaultsToEmptyString() {
        assertThat(jdbc.getTablePrefix()).isEmpty();
    }

    @Test
    @DisplayName("tablePrefix can be set")
    void tablePrefixCanBeSet() {
        jdbc.setTablePrefix("DEMO_");
        assertThat(jdbc.getTablePrefix()).isEqualTo("DEMO_");
    }

    @Test
    @DisplayName("allowedPayloadClasses defaults to empty and is settable (moved from store level)")
    void allowedPayloadClasses() {
        assertThat(jdbc.getAllowedPayloadClasses()).isEmpty();
        jdbc.setAllowedPayloadClasses(java.util.List.of("com.acme.referential", "com.acme.Currency"));
        assertThat(jdbc.getAllowedPayloadClasses()).containsExactly("com.acme.referential", "com.acme.Currency");
    }

    @Test
    @DisplayName("encryption defaults: disabled with the b64 cipher")
    void encryptionDefaults() {
        assertThat(jdbc.getEncryption()).isNotNull();
        assertThat(jdbc.getEncryption().isEnabled()).isFalse();
        assertThat(jdbc.getEncryption().getCipher()).isEqualTo("b64");
    }
}
