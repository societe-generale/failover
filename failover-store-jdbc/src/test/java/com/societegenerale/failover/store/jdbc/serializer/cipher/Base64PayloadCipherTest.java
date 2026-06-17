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
package com.societegenerale.failover.store.jdbc.serializer.cipher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Base64PayloadCipherTest {

    private final Base64PayloadCipher cipher = new Base64PayloadCipher();

    @Test
    @DisplayName("id is the stable 'b64' tag")
    void idIsB64() {
        assertThat(cipher.id()).isEqualTo("b64");
    }

    @Test
    @DisplayName("encrypt then decrypt round-trips the original string")
    void roundTrips() {
        String json = "{\"name\":\"acme\",\"value\":42}";
        String encoded = cipher.encrypt(json);
        assertThat(encoded).isNotEqualTo(json); // base64, not the raw value
        assertThat(cipher.decrypt(encoded)).isEqualTo(json);
    }

    @Test
    @DisplayName("null in -> null out for both directions")
    void nullSafe() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}
