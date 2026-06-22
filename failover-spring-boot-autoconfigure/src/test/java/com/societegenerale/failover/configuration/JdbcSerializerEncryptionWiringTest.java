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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import com.societegenerale.failover.store.jdbc.serializer.cipher.AesGcmPayloadCipher;
import com.societegenerale.failover.store.jdbc.serializer.cipher.Base64PayloadCipher;
import com.societegenerale.failover.store.jdbc.serializer.cipher.PayloadCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the JDBC serializer/encryption wiring in
 * {@link FailoverStoreAutoConfiguration.JdbcStoreConfiguration#serializer}. Exercises the
 * encryption-enabled / disabled / custom-cipher / fail-fast paths without a Spring context.
 */
class JdbcSerializerEncryptionWiringTest {

    private final FailoverStoreAutoConfiguration.JdbcStoreConfiguration config =
            new FailoverStoreAutoConfiguration.JdbcStoreConfiguration();

    record Sample(String name, int value) {}

    private Serializer build(FailoverProperties props, List<PayloadCipher> ciphers) {
        @SuppressWarnings("unchecked")
        ObjectProvider<FailoverScanner> scannerProvider = mock(ObjectProvider.class);
        when(scannerProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<PayloadCipher> cipherProvider = mock(ObjectProvider.class);
        when(cipherProvider.orderedStream()).thenReturn(ciphers.stream());
        return config.serializer(new ObjectMapper(), props, scannerProvider, cipherProvider);
    }

    private FailoverProperties props(boolean enabled, String cipherId) {
        FailoverProperties props = new FailoverProperties();
        props.getStore().getJdbc().getEncryption().setEnabled(enabled);
        props.getStore().getJdbc().getEncryption().setCipher(cipherId);
        return props;
    }

    static class RevCipher implements PayloadCipher {
        @Override public String id() { return "rev"; }
        @Override public String encrypt(String p) { return p == null ? null : new StringBuilder(p).reverse().toString(); }
        @Override public String decrypt(String c) { return c == null ? null : new StringBuilder(c).reverse().toString(); }
    }

    @Nested
    @DisplayName("encryption disabled (default)")
    class Disabled {
        @Test
        @DisplayName("writes bare plaintext — no ENC envelope")
        void plaintext() {
            Serializer serializer = build(props(false, "b64"), List.of(new Base64PayloadCipher()));
            assertThat(serializer.serialize(new Sample("acme", 1))).doesNotStartWith("ENC(").contains("acme");
        }
    }

    @Nested
    @DisplayName("encryption enabled")
    class Enabled {
        @Test
        @DisplayName("with the default b64 cipher, writes ENC(b64:..)")
        void base64() {
            Serializer serializer = build(props(true, "b64"), List.of(new Base64PayloadCipher()));
            assertThat(serializer.serialize(new Sample("acme", 1))).startsWith("ENC(b64:").doesNotContain("acme");
        }

        @Test
        @DisplayName("selects the configured custom cipher by id and writes ENC(<id>:..)")
        void customCipherSelected() {
            Serializer serializer = build(props(true, "rev"), List.of(new Base64PayloadCipher(), new RevCipher()));
            assertThat(serializer.serialize(new Sample("acme", 1))).startsWith("ENC(rev:");
        }

        @Test
        @DisplayName("fails fast when the configured cipher id matches no registered bean")
        void unknownCipherIdFailsFast() {
            assertThatThrownBy(() -> build(props(true, "aesgcm"), List.of(new Base64PayloadCipher())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("aesgcm")
                    .hasMessageContaining("encryption.cipher");
        }

        @Test
        @DisplayName("with the built-in AES-GCM cipher, writes ENC(aesgcm:..) and the payload round-trips (audit A4)")
        void aesGcmEncryptsAndRoundTrips() {
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            AesGcmPayloadCipher aesgcm = new AesGcmPayloadCipher(key);

            Serializer serializer = build(props(true, "aesgcm"), List.of(new Base64PayloadCipher(), aesgcm));

            String stored = serializer.serialize(new Sample("acme", 1));
            assertThat(stored).startsWith("ENC(aesgcm:").doesNotContain("acme");

            Sample recovered = serializer.deserialize(stored, Sample.class);
            assertThat(recovered).isEqualTo(new Sample("acme", 1));
        }
    }
}
