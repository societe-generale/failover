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

package com.societegenerale.failover.store;

import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.lenient;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverStoreCaffeineTest {

    private static final String NAME = "third-party-failover";

    private static final Instant NOW = Instant.now();

    @Mock
    private FailoverClock clock;

    private FailoverStoreCaffeine<ThirdParty> failoverStoreCaffeine;

    private ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(NAME, "1", true, NOW, NOW.plusSeconds(60L), new ThirdParty(1L, "TATA", 5));

    @BeforeEach
    void setUp() {
        lenient().when(clock.now()).thenReturn(NOW);
        failoverStoreCaffeine = new FailoverStoreCaffeine<>(clock);
    }

    @Test
    @DisplayName("should store the referential")
    void shouldStoreTheReferential() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);
    }

    @Test
    @DisplayName("should return the referential when found for the given key")
    void shouldReturnTheReferentialWhenFoundForTheGivenKey() {
        failoverStoreCaffeine.store(referentialPayload);

        var result = failoverStoreCaffeine.find(NAME, "1");

        assertThat(result).isPresent().contains(referentialPayload);
    }

    @Test
    @DisplayName("should return empty when no referential found for the given key")
    void shouldReturnEmptyWhenNoReferentialFoundForTheGivenKey() {
        failoverStoreCaffeine.store(referentialPayload);

        var result = failoverStoreCaffeine.find(NAME, "2");

        assertThat(result).isNotPresent();
    }

    @Test
    @DisplayName("should return empty when no referential found for the given name")
    void shouldReturnEmptyWhenNoReferentialFoundForTheGivenName() {
        failoverStoreCaffeine.store(referentialPayload);

        var result = failoverStoreCaffeine.find("DUMMY-KEY", "1");

        assertThat(result).isNotPresent();
    }

    @Test
    @DisplayName("should delete the referential")
    void shouldDeleteTheReferential() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreCaffeine.delete(referentialPayload);

        var result = failoverStoreCaffeine.find(NAME, "1");
        assertThat(result).isNotPresent();
    }


    @Test
    @DisplayName("should execute delete without exception when no referential name found for delete")
    void shouldExecuteDeleteWithoutExceptionWhenNoReferentialNameFoundForDelete() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreCaffeine.delete(new ReferentialPayload<>("DUMMY-NAME", "1", true, Instant.now(), Instant.now(), new ThirdParty(1L, "TATA", 5)));

        var result = failoverStoreCaffeine.find(NAME, "1");
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("should execute delete without exception when no referential key found in cache for delete")
    void shouldExecuteDeleteWithoutExceptionWhenNoReferentialKeyFoundInCacheForDelete() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreCaffeine.delete(new ReferentialPayload<>(NAME, "DUMMY-KEY", true, Instant.now(), Instant.now(), new ThirdParty(1L, "TATA", 5)));

        var result = failoverStoreCaffeine.find(NAME, "1");
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("should invalidate the data on expiry")
    void shouldInvalidateTheDataOnExpiry() {
        // given
        referentialPayload = new ReferentialPayload<>(NAME, "1", true, NOW, NOW.plusSeconds(2L), new ThirdParty(1L, "TATA", 5));

        // when
        failoverStoreCaffeine.store(referentialPayload);

        // and : do nothing on clear
        failoverStoreCaffeine.cleanByExpiry(Instant.now());

        // then : wait and assert for cache expiry
        await().atMost(3, TimeUnit.SECONDS).until(()-> failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey()).isEmpty());

        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isNotPresent();
    }

    /**
     * <p>Bug (section 2.2): {@code computeIfAbsent} creates the per-name Caffeine cache once,
     * using the first payload's {@code expireOn} as the fixed {@code expireAfterWrite} TTL.
     * All subsequent payloads stored under the same name share that cache and therefore
     * inherit the first entry's TTL — their own {@code expireOn} is silently ignored.
     *
     * <p>This test will FAIL until the fix (per-entry {@code Expiry} policy) is applied.
     */
    @Test
    @DisplayName("second entry with longer expireOn should not evicted by first entry's shorter TTL")
    void everyEntryShouldHaveItsOwnTTLForExpiry() {
        // ARRANGE
        // Payload A: short-lived (2s). Storing this first creates the NAME cache with expireAfterWrite=2s.
        var shortLivedPayload = new ReferentialPayload<>(NAME, "key-short", true, NOW, NOW.plusSeconds(2L),
                new ThirdParty(1L, "SHORT", 1));
        var longLivedPayload  = new ReferentialPayload<>(NAME, "key-long",  true, NOW, NOW.plusSeconds(30L),
                new ThirdParty(2L, "LONG",  2));

        // ACT: store short-lived first — creates NAME cache with expireAfterWrite=2s.
        // Then store long-lived into the same name — BUG: computeIfAbsent reuses the 2s-TTL cache,
        // so the 30s expireOn of the long-lived payload is silently ignored.
        failoverStoreCaffeine.store(shortLivedPayload);
        failoverStoreCaffeine.store(longLivedPayload);

        // Sanity: both entries present immediately after store
        assertThat(failoverStoreCaffeine.find(NAME, "key-short")).isPresent();
        assertThat(failoverStoreCaffeine.find(NAME, "key-long")).isPresent();

        // Wait for key-short to expire (expected — it has a 2s TTL)
        await().atMost(4, TimeUnit.SECONDS)
               .until(() -> failoverStoreCaffeine.find(NAME, "key-short").isEmpty());

        // ASSERT (RED — proves the bug):
        // key-long has expireOn=NOW+30s, so it must still be present when key-short (2s) just expired.
        // BUG: both entries share the 2s-TTL cache created by key-short, so key-long is
        // evicted at the same time — its 30s expireOn is completely ignored.
        assertThat(failoverStoreCaffeine.find(NAME, "key-long"))
                .as("long-lived entry (expireOn=NOW+30s) must still be present when the 2s entry expires — " +
                    "FAILS because both entries share the same 2s-TTL cache created by the first entry")
                .isPresent();
    }

    @Test
    @DisplayName("should extend TTL when an existing entry is updated with a later expireOn")
    void shouldUpdateTTLOnExpireAfterUpdate() {
        // ARRANGE
        // Sentinel: 2s TTL on a separate key — expires at ~T+2s, used as a wall-clock timer.
        var sentinel = new ReferentialPayload<>(NAME, "sentinel-key", true, NOW, NOW.plusSeconds(2L),
                new ThirdParty(0L, "SENTINEL", 0));
        var initialPayload = new ReferentialPayload<>(NAME, "update-key", true, NOW, NOW.plusSeconds(2L),
                new ThirdParty(1L, "INITIAL", 1));
        var updatedPayload = new ReferentialPayload<>(NAME, "update-key", true, NOW, NOW.plusSeconds(30L),
                new ThirdParty(1L, "UPDATED", 2));

        // ACT: store initial entry, then immediately update it (same key, longer expireOn)
        failoverStoreCaffeine.store(sentinel);
        failoverStoreCaffeine.store(initialPayload);
        failoverStoreCaffeine.store(updatedPayload); // triggers expireAfterUpdate → new TTL = 30s

        // Sanity: updated payload is present immediately
        assertThat(failoverStoreCaffeine.find(NAME, "update-key"))
                .isPresent()
                .hasValueSatisfying(p -> assertThat(p.getPayload().getName()).isEqualTo("UPDATED"));

        // Wait for sentinel to expire — proves ~2s of wall-clock time has elapsed
        await().atMost(4, TimeUnit.SECONDS)
               .until(() -> failoverStoreCaffeine.find(NAME, "sentinel-key").isEmpty());

        // ASSERT: updated entry must still be present (expireAfterUpdate extended TTL to 30s)
        assertThat(failoverStoreCaffeine.find(NAME, "update-key"))
                .as("entry updated with expireOn=NOW+30s must still be present after the original 2s TTL elapses")
                .isPresent()
                .hasValueSatisfying(p -> assertThat(p.getPayload().getName()).isEqualTo("UPDATED"));
    }

    @Test
    @DisplayName("should find all referential for the given name")
    void shouldFindAllReferentialForGivenName() {
        var rp2 = new ReferentialPayload<>(NAME, "2", true, NOW, NOW.plusSeconds(60L), new ThirdParty(2L, "TATA", 6));
        var other = new ReferentialPayload<>("other-failover", "1", true, NOW, NOW.plusSeconds(60L), new ThirdParty(3L, "BATA", 7));
        failoverStoreCaffeine.store(referentialPayload);
        failoverStoreCaffeine.store(rp2);
        failoverStoreCaffeine.store(other);

        var result = failoverStoreCaffeine.findAll(NAME);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ReferentialPayload::getKey).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("should return empty list when no referential found for given name in findAll")
    void shouldReturnEmptyListWhenNoReferentialFoundForGivenNameInFindAll() {
        var result = failoverStoreCaffeine.findAll("unknown-failover");

        assertThat(result).isEmpty();
    }

    @Data
    @AllArgsConstructor
    static class ThirdParty  {
        private Long id;
        private String name;
        private int score;
    }
}
