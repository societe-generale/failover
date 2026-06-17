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

import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.store.jdbc.serializer.JsonSerializer;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptingSerializerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Serializer delegate = new JsonSerializer(OBJECT_MAPPER);
    private final Base64PayloadCipher b64 = new Base64PayloadCipher();

    @Data @AllArgsConstructor @NoArgsConstructor
    static class Sample { private String name; private int value; }

    /** A second cipher (reversing "encryption") to exercise multi-cipher dispatch. */
    static class ReverseCipher implements PayloadCipher {
        @Override public String id() { return "rev"; }
        @Override public String encrypt(String plaintext) { return plaintext == null ? null : new StringBuilder(plaintext).reverse().toString(); }
        @Override public String decrypt(String ciphertext) { return ciphertext == null ? null : new StringBuilder(ciphertext).reverse().toString(); }
    }

    @Nested
    @DisplayName("write side")
    class WriteSide {

        @Test
        @DisplayName("wraps the serialized payload as ENC(<id>:<ciphertext>) when a write cipher is set")
        void encryptsAndWraps() {
            Serializer enc = new EncryptingSerializer(delegate, List.of(b64), b64);
            String stored = enc.serialize(new Sample("acme", 1));
            assertThat(stored).startsWith("ENC(b64:").endsWith(")");
            assertThat(stored).doesNotContain("acme"); // plaintext not visible at rest
        }

        @Test
        @DisplayName("writes bare plaintext (no envelope) when the write cipher is null (disabled)")
        void plaintextWhenDisabled() {
            Serializer enc = new EncryptingSerializer(delegate, List.of(b64), null);
            String stored = enc.serialize(new Sample("acme", 1));
            assertThat(stored).doesNotStartWith("ENC(").contains("acme");
        }

        @Test
        @DisplayName("null payload stays null on both paths")
        void nullPayload() {
            assertThat(new EncryptingSerializer(delegate, List.of(b64), b64).serialize(null)).isNull();
            assertThat(new EncryptingSerializer(delegate, List.of(b64), null).serialize(null)).isNull();
        }
    }

    @Nested
    @DisplayName("read side")
    class ReadSide {

        @Test
        @DisplayName("decrypts an ENC(...) value via the cipher named in the envelope")
        void decryptsEnveloped() {
            Serializer enc = new EncryptingSerializer(delegate, List.of(b64), b64);
            Sample original = new Sample("acme", 7);
            String stored = enc.serialize(original);
            assertThat(enc.deserialize(stored, Sample.class)).isEqualTo(original);
        }

        @Test
        @DisplayName("reads a legacy plaintext value (no envelope) straight through")
        void readsPlaintext() {
            Serializer enc = new EncryptingSerializer(delegate, List.of(b64), b64);
            String plaintextJson = delegate.serialize(new Sample("acme", 7));
            assertThat(enc.deserialize(plaintextJson, Sample.class)).isEqualTo(new Sample("acme", 7));
        }

        @Test
        @DisplayName("a store mixing ENC(b64:..) and ENC(rev:..) rows decrypts each with the right cipher")
        void mixedCiphersDispatchById() {
            PayloadCipher rev = new ReverseCipher();
            // writer A used b64; writer B used rev — both registered for reads
            Serializer encB64 = new EncryptingSerializer(delegate, List.of(b64, rev), b64);
            Serializer encRev = new EncryptingSerializer(delegate, List.of(b64, rev), rev);
            String rowB64 = encB64.serialize(new Sample("from-b64", 1));
            String rowRev = encRev.serialize(new Sample("from-rev", 2));
            assertThat(rowB64).startsWith("ENC(b64:");
            assertThat(rowRev).startsWith("ENC(rev:");

            // a single reader with both ciphers registered decrypts both rows
            Serializer reader = new EncryptingSerializer(delegate, List.of(b64, rev), b64);
            assertThat(reader.deserialize(rowB64, Sample.class)).isEqualTo(new Sample("from-b64", 1));
            assertThat(reader.deserialize(rowRev, Sample.class)).isEqualTo(new Sample("from-rev", 2));
        }

        @Test
        @DisplayName("throws when the envelope names a cipher id that is not registered")
        void unknownCipherIdThrows() {
            Serializer enc = new EncryptingSerializer(delegate, List.of(b64), b64);
            assertThatThrownBy(() -> enc.deserialize("ENC(aesgcm:whatever)", Sample.class))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("aesgcm")
                    .hasMessageContaining("registered");
        }

        @Test
        @DisplayName("throws on a malformed envelope missing the ':' separator")
        void malformedEnvelopeThrows() {
            Serializer enc = new EncryptingSerializer(delegate, List.of(b64), b64);
            assertThatThrownBy(() -> enc.deserialize("ENC(no-colon-here)", Sample.class))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("Malformed");
        }

        @Test
        @DisplayName("null stored value stays null")
        void nullStored() {
            assertThat(new EncryptingSerializer(delegate, List.of(b64), b64).deserialize(null, Sample.class)).isNull();
        }
    }

    @Nested
    @DisplayName("construction validation")
    class Construction {

        @Test
        @DisplayName("rejects duplicate cipher ids")
        void duplicateIds() {
            assertThatThrownBy(() -> new EncryptingSerializer(delegate, List.of(b64, new Base64PayloadCipher()), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate");
        }

        @Test
        @DisplayName("rejects a write cipher that is not among the registered ciphers")
        void writeCipherNotRegistered() {
            assertThatThrownBy(() -> new EncryptingSerializer(delegate, List.of(b64), new ReverseCipher()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not among the registered");
        }

        @Test
        @DisplayName("rejects an id containing the reserved ':' character")
        void idWithColon() {
            PayloadCipher bad = new PayloadCipher() {
                @Override public String id() { return "a:b"; }
                @Override public String encrypt(String p) { return p; }
                @Override public String decrypt(String c) { return c; }
            };
            assertThatThrownBy(() -> new EncryptingSerializer(delegate, List.of(bad), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid PayloadCipher id");
        }
    }

    @Nested
    @DisplayName("pass-through methods")
    class PassThrough {

        @Test
        @DisplayName("toClassName / toClass are not transformed (PAYLOAD_CLASS stays plaintext)")
        void classMethodsDelegate() {
            Serializer enc = new EncryptingSerializer(delegate, List.of(b64), b64);
            assertThat(enc.toClassName(new Sample("x", 1))).isEqualTo(Sample.class.getName());
            assertThat(enc.<Sample>toClass(Sample.class.getName())).isEqualTo(Sample.class);
        }
    }
}
