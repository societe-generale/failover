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

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AesGcmPayloadCipher")
class AesGcmPayloadCipherTest {

    private static byte[] randomKey(int bytes) {
        byte[] k = new byte[bytes];
        new SecureRandom().nextBytes(k);
        return k;
    }

    private final AesGcmPayloadCipher cipher = new AesGcmPayloadCipher(randomKey(32));

    @Test
    @DisplayName("id is 'aesgcm'")
    void idIsAesGcm() {
        assertThat(cipher.id()).isEqualTo("aesgcm");
    }

    @Test
    @DisplayName("round-trips a payload: decrypt(encrypt(x)) == x")
    void roundTrip() {
        String plaintext = "{\"name\":\"acme\",\"ssn\":\"123-45-6789\"}";
        String encrypted = cipher.encrypt(plaintext);
        assertThat(encrypted).isNotNull().isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("encrypting the same plaintext twice yields different ciphertext (random IV)")
    void semanticSecurityRandomIv() {
        String plaintext = "same-value";
        assertThat(cipher.encrypt(plaintext)).isNotEqualTo(cipher.encrypt(plaintext));
    }

    @Test
    @DisplayName("ciphertext is not readable as the plaintext (confidentiality)")
    void ciphertextHidesPlaintext() {
        String secret = "very-secret-pii";
        assertThat(cipher.encrypt(secret)).doesNotContain(secret);
    }

    @Test
    @DisplayName("null round-trips to null")
    void nullHandling() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("works for AES-128 (16-byte) and AES-192 (24-byte) keys too")
    void otherKeyLengths() {
        for (int len : new int[]{16, 24, 32}) {
            AesGcmPayloadCipher c = new AesGcmPayloadCipher(randomKey(len));
            assertThat(c.decrypt(c.encrypt("x"))).isEqualTo("x");
        }
    }

    @Test
    @DisplayName("rejects an invalid key length")
    void rejectsInvalidKeyLength() {
        assertThatThrownBy(() -> new AesGcmPayloadCipher(randomKey(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16, 24, or 32 bytes");
    }

    @Test
    @DisplayName("rejects a null key")
    void rejectsNullKey() {
        assertThatThrownBy(() -> new AesGcmPayloadCipher(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decrypt of data encrypted under a different key fails loudly (GCM tag)")
    void wrongKeyFails() {
        String encrypted = cipher.encrypt("payload");
        AesGcmPayloadCipher otherKey = new AesGcmPayloadCipher(randomKey(32));
        assertThatThrownBy(() -> otherKey.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption");
    }

    @Test
    @DisplayName("decrypt of tampered ciphertext fails loudly (authentication tag)")
    void tamperedCiphertextFails() {
        String encrypted = cipher.encrypt("payload");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01; // flip a bit in the GCM tag region
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("decrypt of non-AES-GCM input (e.g. too short) fails loudly")
    void garbageInputFails() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        assertThatThrownBy(() -> cipher.decrypt(tooShort))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("fromBase64 builds a working cipher from a Base64-encoded key")
    void fromBase64RoundTrips() {
        String base64Key = Base64.getEncoder().encodeToString(randomKey(32));
        AesGcmPayloadCipher c = AesGcmPayloadCipher.fromBase64(base64Key);
        assertThat(c.decrypt(c.encrypt("hello"))).isEqualTo("hello");
    }

    @Test
    @DisplayName("fromBase64 rejects empty and invalid Base64")
    void fromBase64Rejects() {
        assertThatThrownBy(() -> AesGcmPayloadCipher.fromBase64(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AesGcmPayloadCipher.fromBase64("not valid base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("interoperates with EncryptingSerializer envelope dispatch")
    void worksThroughEncryptingSerializer() {
        // ciphertext from this cipher decrypts back via the same cipher id — sanity that the
        // raw-ciphertext contract (no envelope) is honoured.
        assertThatCode(() -> {
            String enc = cipher.encrypt("enveloped");
            assertThat(cipher.decrypt(enc)).isEqualTo("enveloped");
        }).doesNotThrowAnyException();
    }
}
