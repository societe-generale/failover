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
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static java.time.LocalDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class FailoverStoreInmemoryTest {

    private static final LocalDateTime NOW = now();

    private final FailoverStoreInmemory<ThirdParty> failoverStoreInmemory = new FailoverStoreInmemory<>();

    private final ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>("third-party-failover", "1", true, NOW, NOW, new ThirdParty(1L, "TATA", 5));

    @Test
    void shouldStoreTheReferential() {
        failoverStoreInmemory.store(referentialPayload);

        assertThat(failoverStoreInmemory.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);
    }

    @Test
    void shouldReturnTheReferentialWhenFoundForTheGivenKey() {
        failoverStoreInmemory.store(referentialPayload);

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreInmemory.find("third-party-failover", "1");

        assertThat(result).isPresent().contains(referentialPayload);
    }

    @Test
    void shouldReturnEmptyWhenNoReferentialFoundForTheGivenKey() {
        failoverStoreInmemory.store(referentialPayload);

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreInmemory.find("third-party-failover", "2");

        assertThat(result).isNotPresent();
    }

    @Test
    void shouldDeleteTheReferential() {
        failoverStoreInmemory.store(referentialPayload);
        assertThat(failoverStoreInmemory.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreInmemory.delete(referentialPayload);

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreInmemory.find("third-party-failover", "1");
        assertThat(result).isNotPresent();
    }

    @Test
    void shouldCleanAllReferentialByExpiry() {

        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "1", true, NOW, NOW.plusMinutes(1), new ThirdParty(1L, "TATA-1", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "2", true, NOW, NOW.plusMinutes(2), new ThirdParty(2L, "TATA-2", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "3", true, NOW, NOW.plusMinutes(3), new ThirdParty(3L, "TATA-3", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "4", true, NOW, NOW.plusMinutes(4), new ThirdParty(4L, "TATA-4", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "5", true, NOW, NOW.plusMinutes(5), new ThirdParty(5L, "TATA-5", 5)));

        failoverStoreInmemory.cleanByExpiry(NOW.plusMinutes(4));

        assertThat(failoverStoreInmemory.find("third-party-failover", "1")).isNotPresent();
        assertThat(failoverStoreInmemory.find("third-party-failover", "2")).isNotPresent();
        assertThat(failoverStoreInmemory.find("third-party-failover", "3")).isNotPresent();
        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreInmemory.find("third-party-failover", "4");
        assertThat(result).isPresent().contains(new ReferentialPayload<>("third-party-failover", "4", true, NOW, NOW.plusMinutes(4), new ThirdParty(4L, "TATA-4", 5)));
        result = failoverStoreInmemory.find("third-party-failover", "5");
        assertThat(result).isPresent().contains(new ReferentialPayload<>("third-party-failover", "5", true, NOW, NOW.plusMinutes(5), new ThirdParty(5L, "TATA-5", 5)));
    }

    @Data
    @AllArgsConstructor
    static class ThirdParty  {
        private Long id;

        private String name;

        private int score;
    }
}
