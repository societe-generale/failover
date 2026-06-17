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
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.store.jdbc.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.jdbc.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.jdbc.resolver.VarcharPayloadColumnResolver;
import com.societegenerale.failover.store.jdbc.serializer.JsonSerializer;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test (real H2 JDBC store) for the deserialization allowlist on nested/composite
 * payloads (audit I-02, part C).
 *
 * <p>The allowlist is configured with ONLY the exact top-level payload class name. The test then
 * round-trips a payload whose nested fields live in non-allowlisted packages ({@code java.time},
 * {@code java.math}, generic collections) through {@code store} → H2 → {@code find}, proving:
 * <ul>
 *   <li>the gate is on the <em>top-level</em> {@code PAYLOAD_CLASS} only — nested/foreign field
 *       types are reconstructed structurally by Jackson with no allowlist lookup; and</li>
 *   <li>a stored row whose {@code PAYLOAD_CLASS} is NOT on the allowlist is refused on read,
 *       end-to-end through the real store.</li>
 * </ul>
 */
@SpringBootTest(classes = {MySpringBootApplication.class})
class NestedPayloadAllowlistJdbcTest {

    private static final String NAME = "Nested-Failover-Name";
    private static final String KEY  = "Nested-Failover-Key";
    private static final Instant NOW = Instant.now();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Store wired with a serializer whose allowlist contains ONLY the exact top-level payload class. */
    private FailoverStoreJdbc<Acme> restrictedStore;

    @BeforeEach
    void setup() {
        jdbcTemplate.update("DELETE FROM TEST_FAILOVER_STORE");

        Serializer restricted = new JsonSerializer(new JsonMapper(), List.of(Acme.class.getName()));
        var queryResolver = new DefaultFailoverStoreQueryResolver(
                "TEST_", restricted, new DefaultDatabaseResolver(jdbcTemplate), new VarcharPayloadColumnResolver());
        var rowMapper = new ReferentialPayloadRowMapper<Acme>(new VarcharPayloadColumnResolver(), restricted);
        restrictedStore = new FailoverStoreJdbc<>(jdbcTemplate, queryResolver, rowMapper);
    }

    @Test
    @DisplayName("nested foreign/generic field types round-trip through H2 — only the top-level class is allowlisted")
    void nestedTypesRoundTripThroughRealStore() {
        Acme original = new Acme(
                "acme-co",
                new Address("Paris", LocalDate.parse("2020-01-01")), // nested POJO with a java.time field
                new BigDecimal("12345.67"),                          // java.math — not allowlisted
                List.of("alpha", "beta"));                           // generic collection
        restrictedStore.store(new ReferentialPayload<>(NAME, KEY, false, NOW, NOW, original));

        ReferentialPayload<Acme> found = restrictedStore.find(NAME, KEY).orElseThrow();

        assertThat(found.getPayload()).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    @DisplayName("a stored row whose PAYLOAD_CLASS is not allowlisted is refused on read, end-to-end")
    void foreignTopLevelClassRejectedOnRead() {
        // Write a row directly with a non-allowlisted PAYLOAD_CLASS (simulates a class the allowlist forbids).
        jdbcTemplate.update(
                "INSERT INTO TEST_FAILOVER_STORE (FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                NAME, KEY, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                "\"pwned\"", "java.lang.Runtime");

        assertThatThrownBy(() -> restrictedStore.find(NAME, KEY))
                .isInstanceOf(FailoverStoreException.class)
                .hasMessageContaining("java.lang.Runtime")
                .hasMessageContaining("allowlist");
    }

    // ── Test payload types: a composite with nested + foreign-package + generic fields ──

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class Acme {
        private String name;
        private Address address;     // nested POJO (same package — but NOT separately allowlisted)
        private BigDecimal revenue;  // java.math.BigDecimal
        private List<String> tags;   // generic collection
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class Address {
        private String city;
        private LocalDate since;     // java.time.LocalDate
    }
}
