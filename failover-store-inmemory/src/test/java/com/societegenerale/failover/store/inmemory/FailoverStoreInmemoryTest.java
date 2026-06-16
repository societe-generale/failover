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

package com.societegenerale.failover.store.inmemory;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class FailoverStoreInmemoryTest {

    private static final Instant NOW = Instant.now();

    private final FailoverStoreInmemory<ThirdParty> failoverStoreInmemory = new FailoverStoreInmemory<>();

    private final ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>("third-party-failover", "1", true, NOW, NOW, new ThirdParty(1L, "TATA", 5));

    @Test
    @DisplayName("should store the referential")
    void shouldStoreTheReferential() {
        failoverStoreInmemory.store(referentialPayload);

        assertThat(failoverStoreInmemory.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);
    }

    @Test
    @DisplayName("should return the referential when found for the given key")
    void shouldReturnTheReferentialWhenFoundForTheGivenKey() {
        failoverStoreInmemory.store(referentialPayload);

        var result = failoverStoreInmemory.find("third-party-failover", "1");

        assertThat(result).isPresent().contains(referentialPayload);
    }

    @Test
    @DisplayName("should return empty when no referential found for the given key")
    void shouldReturnEmptyWhenNoReferentialFoundForTheGivenKey() {
        failoverStoreInmemory.store(referentialPayload);

        var result = failoverStoreInmemory.find("third-party-failover", "2");

        assertThat(result).isNotPresent();
    }

    @Test
    @DisplayName("should delete the referential")
    void shouldDeleteTheReferential() {
        failoverStoreInmemory.store(referentialPayload);
        assertThat(failoverStoreInmemory.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreInmemory.delete(referentialPayload);

        var result = failoverStoreInmemory.find("third-party-failover", "1");
        assertThat(result).isNotPresent();
    }

    @Test
    @DisplayName("should clean all referential by expiry")
    void shouldCleanAllReferentialByExpiry() {

        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "1", true, NOW, NOW.plusSeconds(60), new ThirdParty(1L, "TATA-1", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "2", true, NOW, NOW.plusSeconds(120), new ThirdParty(2L, "TATA-2", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "3", true, NOW, NOW.plusSeconds(180), new ThirdParty(3L, "TATA-3", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "4", true, NOW, NOW.plusSeconds(240), new ThirdParty(4L, "TATA-4", 5)));
        failoverStoreInmemory.store(new ReferentialPayload<>("third-party-failover", "5", true, NOW, NOW.plusSeconds(300), new ThirdParty(5L, "TATA-5", 5)));

        failoverStoreInmemory.cleanByExpiry(NOW.plusSeconds(240));

        assertThat(failoverStoreInmemory.find("third-party-failover", "1")).isEmpty();
        assertThat(failoverStoreInmemory.find("third-party-failover", "2")).isNotPresent();
        assertThat(failoverStoreInmemory.find("third-party-failover", "3")).isNotPresent();
        var result = failoverStoreInmemory.find("third-party-failover", "4");
        assertThat(result).isPresent().contains(new ReferentialPayload<>("third-party-failover", "4", true, NOW, NOW.plusSeconds(240), new ThirdParty(4L, "TATA-4", 5)));
        result = failoverStoreInmemory.find("third-party-failover", "5");
        assertThat(result).isPresent().contains(new ReferentialPayload<>("third-party-failover", "5", true, NOW, NOW.plusSeconds(300), new ThirdParty(5L, "TATA-5", 5)));
    }

    @Test
    @DisplayName("should find all referential for the given name")
    void shouldFindAllReferentialForGivenName() {
        var rp2 = new ReferentialPayload<>("third-party-failover", "2", true, NOW, NOW, new ThirdParty(2L, "TATA", 6));
        var other = new ReferentialPayload<>("other-failover", "1", true, NOW, NOW, new ThirdParty(3L, "BATA", 7));
        failoverStoreInmemory.store(referentialPayload);
        failoverStoreInmemory.store(rp2);
        failoverStoreInmemory.store(other);

        var result = failoverStoreInmemory.findAll("third-party-failover");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ReferentialPayload::getKey).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("should return empty list when no referential found for given name in findAll")
    void shouldReturnEmptyListWhenNoReferentialFoundForGivenNameInFindAll() {
        var result = failoverStoreInmemory.findAll("unknown-failover");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("bounded store evicts the least-recently-accessed entry past maxEntries (audit I-10)")
    void shouldEvictLeastRecentlyAccessedWhenMaxEntriesExceeded() {
        var bounded = new FailoverStoreInmemory<ThirdParty>(2);
        bounded.store(payload("1"));
        bounded.store(payload("2"));

        // Access "1" so "2" becomes the least-recently-used, then insert "3" → "2" is evicted.
        assertThat(bounded.find("third-party-failover", "1")).isPresent();
        bounded.store(payload("3"));

        assertThat(bounded.find("third-party-failover", "2")).isEmpty();
        assertThat(bounded.find("third-party-failover", "1")).isPresent();
        assertThat(bounded.find("third-party-failover", "3")).isPresent();
        assertThat(bounded.findAll("third-party-failover")).hasSize(2);
    }

    @Test
    @DisplayName("unbounded store (maxEntries <= 0) retains all entries")
    void shouldRetainAllEntriesWhenUnbounded() {
        var unbounded = new FailoverStoreInmemory<ThirdParty>(0);
        for (int i = 0; i < 50; i++) {
            unbounded.store(payload(String.valueOf(i)));
        }
        assertThat(unbounded.findAll("third-party-failover")).hasSize(50);
    }

    private ReferentialPayload<ThirdParty> payload(String key) {
        return new ReferentialPayload<>("third-party-failover", key, true, NOW, NOW, new ThirdParty(Long.parseLong(key), "TATA", 5));
    }

    @Data
    @AllArgsConstructor
    static class ThirdParty  {
        private Long id;

        private String name;

        private int score;
    }
}
