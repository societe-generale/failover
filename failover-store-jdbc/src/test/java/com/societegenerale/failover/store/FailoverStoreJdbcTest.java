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

package com.societegenerale.failover.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.domain.Referential;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static java.time.LocalDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
@SpringBootTest(classes = {MySpringBootApplication.class})
class FailoverStoreJdbcTest {

    private static final String NAME = "Failover-Name";

    private static final String KEY = "Failover-Key";

    private static final LocalDateTime NOW = now();

    private ReferentialPayload<Client> referentialPayload;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FailoverStoreJdbc<Client> failoverStoreJdbc;

    @BeforeEach
    void setup() {
        Client client = new Client(1L, "TATA");
        client.setAsOf(now());
        client.setUpToDate(false);
        referentialPayload = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW, client);
        jdbcTemplate.update("DELETE FROM TEST_FAILOVER_STORE");
    }

    @DisplayName("should store and retrieve the referential payload")
    @Test
    void shouldStoreAndRetrieveTheReferentialPayload() {
        failoverStoreJdbc.store(referentialPayload);
        Optional<ReferentialPayload<Client>> fromDB = failoverStoreJdbc.find(NAME, KEY);
        assertThat(fromDB).isPresent().contains(referentialPayload);
    }

    @DisplayName("should store and retrieve the referential payload with no payload")
    @Test
    void shouldStoreAndRetrieveTheReferentialPayloadWithNullPayload() {
        referentialPayload.setPayload(null);
        failoverStoreJdbc.store(referentialPayload);
        Optional<ReferentialPayload<Client>> fromDB = failoverStoreJdbc.find(NAME, KEY);
        assertThat(fromDB).isPresent().contains(referentialPayload);
    }

    @DisplayName("should store and delete the referential payload")
    @Test
    void shouldDeleteGivenReferentialPayload() {
        failoverStoreJdbc.store(referentialPayload);
        Optional<ReferentialPayload<Client>> fromDB = failoverStoreJdbc.find(NAME, KEY);
        assertThat(fromDB).isPresent().contains(referentialPayload);

        failoverStoreJdbc.delete(referentialPayload);
        fromDB = failoverStoreJdbc.find(NAME, KEY);
        assertThat(fromDB).isNotPresent();
    }

    @DisplayName("should return empty when no matching referential payload found")
    @Test
    void shouldReturnEmptyWhenNoReferentialPayloadFoundForGivenNameAndKey() {
        failoverStoreJdbc.store(referentialPayload);
        Optional<ReferentialPayload<Client>> fromDB = failoverStoreJdbc.find(NAME+"X", KEY+"Y");
        assertThat(fromDB).isNotPresent();
    }

    @DisplayName("should update the referential payload when already exist")
    @Test
    void shouldUpdateTheReferentialPayloadWhenExist() {
        failoverStoreJdbc.store(referentialPayload);
        Optional<ReferentialPayload<Client>> fromDB = failoverStoreJdbc.find(NAME, KEY);
        assertThat(fromDB).isPresent().contains(referentialPayload);
        referentialPayload.setAsOf(now());
        failoverStoreJdbc.store(referentialPayload);
        fromDB = failoverStoreJdbc.find(NAME, KEY);
        assertThat(fromDB).isPresent().contains(referentialPayload);
    }

    @Test
    void shouldCleanAllReferentialByExpiry() {
        failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "1", false, NOW, NOW.plusMinutes(1), new Client(1L, "TATA-1")));
        failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "2", false, NOW, NOW.plusMinutes(2), new Client(2L, "TATA-2")));
        failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "3", false, NOW, NOW.plusMinutes(3), new Client(3L, "TATA-3")));
        failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "4", false, NOW, NOW.plusMinutes(4), new Client(4L, "TATA-4")));
        failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "5", false, NOW, NOW.plusMinutes(5), new Client(5L, "TATA-5")));

        failoverStoreJdbc.cleanByExpiry(NOW.plusMinutes(4));

        assertThat(failoverStoreJdbc.find(NAME, "1")).isNotPresent();
        assertThat(failoverStoreJdbc.find(NAME, "2")).isNotPresent();
        assertThat(failoverStoreJdbc.find(NAME, "3")).isNotPresent();
        Optional<ReferentialPayload<Client>> result = failoverStoreJdbc.find(NAME, "4");
        assertThat(result).isPresent().contains(new ReferentialPayload<>(NAME, "4", false, NOW, NOW.plusMinutes(4), new Client(4L, "TATA-4")));
        result = failoverStoreJdbc.find(NAME, "5");
        assertThat(result).isPresent().contains(new ReferentialPayload<>(NAME, "5", false, NOW, NOW.plusMinutes(5), new Client(5L, "TATA-5")));
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    @NoArgsConstructor
    static class Client extends Referential {
        private Long id;

        private String name;
    }
}