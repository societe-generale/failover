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
package com.societegenerale.failover.store.jdbc;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.store.jdbc.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.jdbc.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.jdbc.resolver.VarcharPayloadColumnResolver;
import com.societegenerale.failover.store.jdbc.serializer.JsonSerializer;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import com.societegenerale.failover.store.jdbc.serializer.cipher.Base64PayloadCipher;
import com.societegenerale.failover.store.jdbc.serializer.cipher.EncryptingSerializer;
import com.societegenerale.failover.store.jdbc.serializer.cipher.PayloadCipher;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test (real H2 JDBC store) for payload-at-rest encryption.
 *
 * <p>Wires a {@link FailoverStoreJdbc} with an {@link EncryptingSerializer} and asserts, against a
 * real database, that: the {@code PAYLOAD} column holds {@code ENC(...)} ciphertext (not plaintext);
 * the value round-trips on {@code find}; a store mixing rows from two different ciphers decrypts each
 * correctly; and disabled encryption writes bare plaintext that is still readable.
 */
@SpringBootTest(classes = {MySpringBootApplication.class})
class EncryptedPayloadJdbcTest {

    private static final String NAME = "Enc-Failover-Name";
    private static final Instant NOW = Instant.now();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Serializer json = new JsonSerializer(new JsonMapper());
    private final Base64PayloadCipher b64 = new Base64PayloadCipher();
    private final PayloadCipher rev = new ReverseCipher();

    @BeforeEach
    void setup() {
        jdbcTemplate.update("DELETE FROM TEST_FAILOVER_STORE");
    }

    private FailoverStoreJdbc<Secret> storeWith(Serializer serializer) {
        var queryResolver = new DefaultFailoverStoreQueryResolver(
                "TEST_", serializer, new DefaultDatabaseResolver(jdbcTemplate), new VarcharPayloadColumnResolver());
        var rowMapper = new ReferentialPayloadRowMapper<Secret>(new VarcharPayloadColumnResolver(), serializer);
        return new FailoverStoreJdbc<>(jdbcTemplate, queryResolver, rowMapper);
    }

    private String rawPayloadColumn(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT PAYLOAD FROM TEST_FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?",
                String.class, NAME, key);
    }

    @Test
    @DisplayName("encrypted write stores ENC(b64:..) ciphertext at rest, not plaintext, and round-trips on find")
    void encryptsAtRestAndRoundTrips() {
        var store = storeWith(new EncryptingSerializer(json, List.of(b64), b64));
        var secret = new Secret("alice", "top-secret-token");
        store.store(new ReferentialPayload<>(NAME, "k1", false, NOW, NOW, secret));

        // at rest: the DB column is the ENC envelope and does NOT leak the plaintext
        String raw = rawPayloadColumn("k1");
        assertThat(raw).startsWith("ENC(b64:").endsWith(")");
        assertThat(raw).doesNotContain("top-secret-token").doesNotContain("alice");

        // round-trips through find
        assertThat(store.find(NAME, "k1").orElseThrow().getPayload()).isEqualTo(secret);
    }

    @Test
    @DisplayName("a store mixing two ciphers' rows decrypts each via the cipher named in its envelope")
    void mixedCipherStoreRoundTrips() {
        // write one row with b64, another with rev — both ciphers registered for reads
        storeWith(new EncryptingSerializer(json, List.of(b64, rev), b64))
                .store(new ReferentialPayload<>(NAME, "viaB64", false, NOW, NOW, new Secret("a", "111")));
        storeWith(new EncryptingSerializer(json, List.of(b64, rev), rev))
                .store(new ReferentialPayload<>(NAME, "viaRev", false, NOW, NOW, new Secret("b", "222")));

        assertThat(rawPayloadColumn("viaB64")).startsWith("ENC(b64:");
        assertThat(rawPayloadColumn("viaRev")).startsWith("ENC(rev:");

        var reader = storeWith(new EncryptingSerializer(json, List.of(b64, rev), b64));
        assertThat(reader.find(NAME, "viaB64").orElseThrow().getPayload()).isEqualTo(new Secret("a", "111"));
        assertThat(reader.find(NAME, "viaRev").orElseThrow().getPayload()).isEqualTo(new Secret("b", "222"));
    }

    @Test
    @DisplayName("disabled encryption writes bare plaintext JSON that remains readable")
    void disabledWritesPlaintext() {
        var store = storeWith(new EncryptingSerializer(json, List.of(b64), null));
        var secret = new Secret("carol", "plain");
        store.store(new ReferentialPayload<>(NAME, "k2", false, NOW, NOW, secret));

        assertThat(rawPayloadColumn("k2")).doesNotStartWith("ENC(").contains("carol");
        assertThat(store.find(NAME, "k2").orElseThrow().getPayload()).isEqualTo(secret);
    }

    // ── fixtures ──

    @Data @AllArgsConstructor @NoArgsConstructor
    static class Secret { private String owner; private String token; }

    /** Toy reversible cipher (id "rev") for multi-cipher dispatch tests — NOT real encryption. */
    static class ReverseCipher implements PayloadCipher {
        @Override public String id() { return "rev"; }
        @Override public String encrypt(String plaintext) { return plaintext == null ? null : new StringBuilder(plaintext).reverse().toString(); }
        @Override public String decrypt(String ciphertext) { return ciphertext == null ? null : new StringBuilder(ciphertext).reverse().toString(); }
    }
}
