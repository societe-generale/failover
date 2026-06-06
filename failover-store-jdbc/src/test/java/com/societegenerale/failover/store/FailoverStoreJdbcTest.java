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
import com.societegenerale.failover.store.resolver.*;
import com.societegenerale.failover.store.serializer.Serializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * @author Anand Manissery
 */
@SpringBootTest(classes = {MySpringBootApplication.class})
class FailoverStoreJdbcTest {

    private static final String NAME = "Failover-Name";
    private static final String KEY  = "Failover-Key";
    private static final Instant NOW = Instant.now();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Serializer serializer;

    @MockitoSpyBean
    private DatabaseResolver databaseResolver;

    @MockitoSpyBean
    private FailoverStoreQueryResolver failoverStoreQueryResolver;

    @Autowired
    private FailoverStoreJdbc<Client> failoverStoreJdbc;

    @Autowired
    private RowMapper<ReferentialPayload<Client>> rowMapper;

    private ReferentialPayload<Client> referentialPayload;


    @BeforeEach
    void setup() {
        var client = new Client(1L, "TATA");
        client.setAsOf(NOW);
        client.setUpToDate(false);
        referentialPayload = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW, client);
        jdbcTemplate.update("DELETE FROM TEST_FAILOVER_STORE");
    }

    // -------------------------------------------------------------------------
    // find()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("find")
    class FindScenarios {

        @Test
        @DisplayName("should return empty when store is empty")
        void shouldReturnEmptyWhenStoreIsEmpty() {
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isNotPresent();
        }

        @Test
        @DisplayName("should return payload when name and key both match")
        void shouldReturnPayloadWhenNameAndKeyBothMatch() {
            failoverStoreJdbc.store(referentialPayload);
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent().contains(referentialPayload);
        }

        @Test
        @DisplayName("should return empty when name matches but key does not")
        void shouldReturnEmptyWhenNameMatchesButKeyDoesNot() {
            failoverStoreJdbc.store(referentialPayload);
            assertThat(failoverStoreJdbc.find(NAME, "unknown-key")).isNotPresent();
        }

        @Test
        @DisplayName("should return empty when key matches but name does not")
        void shouldReturnEmptyWhenKeyMatchesButNameDoesNot() {
            failoverStoreJdbc.store(referentialPayload);
            assertThat(failoverStoreJdbc.find("unknown-name", KEY)).isNotPresent();
        }

        @Test
        @DisplayName("should return empty when neither name nor key matches")
        void shouldReturnEmptyWhenNeitherNameNorKeyMatches() {
            failoverStoreJdbc.store(referentialPayload);
            assertThat(failoverStoreJdbc.find("unknown-name", "unknown-key")).isNotPresent();
        }

        @Test
        @DisplayName("should always return upToDate as false regardless of what was stored")
        void shouldAlwaysReturnUpToDateAsFalse() {
            var payloadWithUpToDateTrue = new ReferentialPayload<>(NAME, KEY, true, NOW, NOW, new Client(1L, "test"));
            failoverStoreJdbc.store(payloadWithUpToDateTrue);
            var result = failoverStoreJdbc.find(NAME, KEY);
            assertThat(result).isPresent();
            assertThat(result.get().isUpToDate()).isFalse();
        }

        @Test
        @DisplayName("should preserve asOf and expireOn timestamps exactly on round-trip")
        void shouldPreserveTimestampsExactlyOnRoundTrip() {
            Instant asOf     = Instant.parse("2024-06-15T14:30:45Z");
            Instant expireOn = Instant.parse("2025-12-31T23:59:59Z");
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, KEY, false, asOf, expireOn, new Client(1L, "ts-test")));
            var result = failoverStoreJdbc.find(NAME, KEY).orElseThrow();
            assertThat(result.getAsOf()).isEqualTo(asOf);
            assertThat(result.getExpireOn()).isEqualTo(expireOn);
        }
    }

    // -------------------------------------------------------------------------
    // store()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("store")
    class StoreScenarios {

        @Test
        @DisplayName("should store and retrieve the referential payload")
        void shouldStoreAndRetrieveTheReferentialPayload() {
            failoverStoreJdbc.store(referentialPayload);
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent().contains(referentialPayload);
        }

        @Test
        @DisplayName("should store and retrieve with null payload")
        void shouldStoreAndRetrieveWithNullPayload() {
            referentialPayload.setPayload(null);
            failoverStoreJdbc.store(referentialPayload);
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent().contains(referentialPayload);
        }

        @Test
        @DisplayName("should update stored values when storing the same key again")
        void shouldUpdateStoredValuesWhenStoringTheSameKeyAgain() {
            failoverStoreJdbc.store(referentialPayload);
            var updated = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(3600), new Client(99L, "updated"));
            failoverStoreJdbc.store(updated);
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent().contains(updated);
        }


        @Test
        @DisplayName("should result in exactly one row when the same key is stored multiple times")
        void shouldResultInOneRowWhenSameKeyStoredMultipleTimes() {
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(3600), new Client(1L, "first")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(7200), new Client(2L, "second")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(10800), new Client(3L, "third")));

            var count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM TEST_FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?",
                    Integer.class, NAME, KEY);
            assertThat(count).isEqualTo(1);

            var result = failoverStoreJdbc.find(NAME, KEY).orElseThrow();
            assertThat(result.getPayload()).isEqualTo(new Client(3L, "third"));
            assertThat(result.getExpireOn()).isEqualTo(NOW.plusSeconds(10800));
        }

        @Test
        @DisplayName("should store multiple payloads with different keys under the same name")
        void shouldStoreMultiplePayloadsWithDifferentKeysUnderSameName() {
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "key-1", false, NOW, NOW, new Client(1L, "client-1")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "key-2", false, NOW, NOW, new Client(2L, "client-2")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "key-3", false, NOW, NOW, new Client(3L, "client-3")));

            assertThat(failoverStoreJdbc.find(NAME, "key-1")).isPresent();
            assertThat(failoverStoreJdbc.find(NAME, "key-2")).isPresent();
            assertThat(failoverStoreJdbc.find(NAME, "key-3")).isPresent();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TEST_FAILOVER_STORE", Integer.class)).isEqualTo(3);
        }

        @Test
        @DisplayName("should store payloads under different names without cross-contamination")
        void shouldStorePayloadsUnderDifferentNamesWithoutCrossContamination() {
            failoverStoreJdbc.store(new ReferentialPayload<>("name-A", KEY, false, NOW, NOW, new Client(1L, "clientA")));
            failoverStoreJdbc.store(new ReferentialPayload<>("name-B", KEY, false, NOW, NOW, new Client(2L, "clientB")));

            assertThat(failoverStoreJdbc.find("name-A", KEY).map(r -> r.getPayload().getName())).contains("clientA");
            assertThat(failoverStoreJdbc.find("name-B", KEY).map(r -> r.getPayload().getName())).contains("clientB");
        }

        @Test
        @DisplayName("should store and retrieve payload containing special characters")
        void shouldStoreAndRetrievePayloadWithSpecialCharacters() {
            var specialClient = new Client(99L, "O'Brien & \"Müller\" <admin@example.com> \\unicode: éàü");
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, KEY, false, NOW, NOW, specialClient));
            var result = failoverStoreJdbc.find(NAME, KEY);
            assertThat(result).isPresent();
            assertThat(result.get().getPayload().getName()).isEqualTo(specialClient.getName());
        }
    }

    // -------------------------------------------------------------------------
    // delete()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("delete")
    class DeleteScenarios {

        @Test
        @DisplayName("should delete an existing payload so it can no longer be found")
        void shouldDeleteExistingPayloadSoItCanNoLongerBeFound() {
            failoverStoreJdbc.store(referentialPayload);
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent();

            failoverStoreJdbc.delete(referentialPayload);
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isNotPresent();
        }

        @Test
        @DisplayName("should not throw when deleting a non-existent payload")
        void shouldNotThrowWhenDeletingNonExistentPayload() {
            assertThatNoException().isThrownBy(() -> failoverStoreJdbc.delete(referentialPayload));
        }

        @Test
        @DisplayName("should delete idempotently — second delete on same key does not throw")
        void shouldDeleteIdempotently() {
            failoverStoreJdbc.store(referentialPayload);
            failoverStoreJdbc.delete(referentialPayload);
            assertThatNoException().isThrownBy(() -> failoverStoreJdbc.delete(referentialPayload));
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isNotPresent();
        }

        @Test
        @DisplayName("should delete only the matching payload leaving other entries intact")
        void shouldDeleteOnlyMatchingPayloadLeavingOthersIntact() {
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "key-1", false, NOW, NOW, new Client(1L, "client-1")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "key-2", false, NOW, NOW, new Client(2L, "client-2")));

            failoverStoreJdbc.delete(new ReferentialPayload<>(NAME, "key-1", false, NOW, NOW, null));

            assertThat(failoverStoreJdbc.find(NAME, "key-1")).isNotPresent();
            assertThat(failoverStoreJdbc.find(NAME, "key-2")).isPresent();
        }

        @Test
        @DisplayName("should not delete an entry whose name matches but key differs")
        void shouldNotDeleteEntryWhenKeyDiffers() {
            failoverStoreJdbc.store(referentialPayload);
            failoverStoreJdbc.delete(new ReferentialPayload<>(NAME, "wrong-key", false, NOW, NOW, null));
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent();
        }

        @Test
        @DisplayName("should not delete an entry whose key matches but name differs")
        void shouldNotDeleteEntryWhenNameDiffers() {
            failoverStoreJdbc.store(referentialPayload);
            failoverStoreJdbc.delete(new ReferentialPayload<>("wrong-name", KEY, false, NOW, NOW, null));
            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent();
        }
    }

    // -------------------------------------------------------------------------
    // cleanByExpiry()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("cleanByExpiry")
    class CleanByExpiryScenarios {

        @Test
        @DisplayName("should not throw when store is empty")
        void shouldNotThrowWhenStoreIsEmpty() {
            assertThatNoException().isThrownBy(() -> failoverStoreJdbc.cleanByExpiry(NOW));
        }

        @Test
        @DisplayName("should remove all records when all expire before the cutoff")
        void shouldRemoveAllRecordsWhenAllExpireBeforeCutoff() {
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "1", false, NOW, NOW.plusSeconds(60), new Client(1L, "A")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "2", false, NOW, NOW.plusSeconds(120), new Client(2L, "B")));

            failoverStoreJdbc.cleanByExpiry(NOW.plusSeconds(3600));

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TEST_FAILOVER_STORE", Integer.class)).isZero();
        }

        @Test
        @DisplayName("should remove nothing when all records expire after the cutoff")
        void shouldRemoveNothingWhenAllExpireAfterCutoff() {
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "1", false, NOW, NOW.plusSeconds(3600), new Client(1L, "A")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "2", false, NOW, NOW.plusSeconds(7200), new Client(2L, "B")));

            failoverStoreJdbc.cleanByExpiry(NOW.minusSeconds(1));

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TEST_FAILOVER_STORE", Integer.class)).isEqualTo(2);
        }

        @Test
        @DisplayName("should keep record whose EXPIRE_ON equals the cutoff (boundary: WHERE EXPIRE_ON < ?)")
        void shouldKeepRecordWhoseExpireOnEqualsTheCutoff() {
            Instant cutoff = NOW.plusSeconds(300);
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "at-boundary", false, NOW, cutoff, new Client(1L, "boundary")));

            failoverStoreJdbc.cleanByExpiry(cutoff);

            assertThat(failoverStoreJdbc.find(NAME, "at-boundary")).isPresent();
        }

        @Test
        @DisplayName("should remove record whose EXPIRE_ON is one millisecond before the cutoff")
        void shouldRemoveRecordJustBeforeCutoff() {
            Instant cutoff     = NOW.plusSeconds(300);
            Instant justBefore = cutoff.minusMillis(1);
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "just-before", false, NOW, justBefore, new Client(1L, "test")));

            failoverStoreJdbc.cleanByExpiry(cutoff);

            assertThat(failoverStoreJdbc.find(NAME, "just-before")).isNotPresent();
        }

        @Test
        @DisplayName("should clean only expired records and preserve non-expired ones")
        void shouldCleanAllReferentialByExpiry() {
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "1", false, NOW, NOW.plusSeconds(60), new Client(1L, "TATA-1")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "2", false, NOW, NOW.plusSeconds(120), new Client(2L, "TATA-2")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "3", false, NOW, NOW.plusSeconds(180), new Client(3L, "TATA-3")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "4", false, NOW, NOW.plusSeconds(240), new Client(4L, "TATA-4")));
            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "5", false, NOW, NOW.plusSeconds(300), new Client(5L, "TATA-5")));

            failoverStoreJdbc.cleanByExpiry(NOW.plusSeconds(240));

            assertThat(failoverStoreJdbc.find(NAME, "1")).isNotPresent();
            assertThat(failoverStoreJdbc.find(NAME, "2")).isNotPresent();
            assertThat(failoverStoreJdbc.find(NAME, "3")).isNotPresent();
            assertThat(failoverStoreJdbc.find(NAME, "4"))
                    .isPresent()
                    .contains(new ReferentialPayload<>(NAME, "4", false, NOW, NOW.plusSeconds(240), new Client(4L, "TATA-4")));
            assertThat(failoverStoreJdbc.find(NAME, "5"))
                    .isPresent()
                    .contains(new ReferentialPayload<>(NAME, "5", false, NOW, NOW.plusSeconds(300), new Client(5L, "TATA-5")));
        }
    }

    // -------------------------------------------------------------------------
    // Concurrency
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("concurrency")
    class ConcurrencyScenarios {

        @Test
        @DisplayName("should handle concurrent store on same key without duplicate key exception")
        void shouldHandleConcurrentStoreOnSameKeyAtomically() throws Exception {
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            try(ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
                List<Future<?>> futures = new ArrayList<>();

                for (int i = 0; i < threadCount; i++) {
                    final int index = i;
                    futures.add(executor.submit(() -> {
                        try {
                            startLatch.await();
                            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, KEY, false,
                                    NOW.plusSeconds(index), NOW.plusSeconds(3600), new Client((long) index, "CLIENT-" + index)));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                }

                startLatch.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
                executor.shutdown();
            }
            var count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM TEST_FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?",
                    Integer.class, NAME, KEY);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle concurrent store on distinct keys without interference")
        void shouldHandleConcurrentStoreOnDistinctKeys() throws Exception {
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            try(ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
                List<Future<?>> futures = new ArrayList<>();

                for (int i = 0; i < threadCount; i++) {
                    final int index = i;
                    futures.add(executor.submit(() -> {
                        try {
                            startLatch.await();
                            failoverStoreJdbc.store(new ReferentialPayload<>(NAME, "key-" + index, false,
                                    NOW, NOW.plusSeconds(3600), new Client((long) index, "CLIENT-" + index)));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                }

                startLatch.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
                executor.shutdown();
            }
            var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TEST_FAILOVER_STORE", Integer.class);
            assertThat(count).isEqualTo(threadCount);
        }
    }

    // -------------------------------------------------------------------------
    // INSERT / UPDATE fallback (DatabaseResolver returns null → mergeEnabled=false)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("INSERT/UPDATE fallback — DatabaseResolver returns null (no merge dialect)")
    class InsertUpdateFallbackScenarios {

        /**
         * A store built with a DatabaseResolver that returns null has mergeEnabled=false
         * and uses INSERT-first, UPDATE-on-duplicate-key fallback for every store() call.
         */
        private FailoverStoreJdbc<Client> buildFallbackStore() {
            var nullDbResolver = Mockito.mock(DatabaseResolver.class);
            Mockito.when(nullDbResolver.resolve()).thenReturn(null);
            var queryResolver = new DefaultFailoverStoreQueryResolver("TEST_", serializer, nullDbResolver, new VarcharPayloadColumnResolver());
            return new FailoverStoreJdbc<>(jdbcTemplate, queryResolver, rowMapper);
        }

        @Test
        @DisplayName("DatabaseResolver.resolve() is invoked exactly once during construction")
        void databaseResolverIsCalledExactlyOnceAtConstruction() {
            var spyResolver = Mockito.spy(new DefaultDatabaseResolver(jdbcTemplate));
            new DefaultFailoverStoreQueryResolver("TEST_", serializer, spyResolver, new VarcharPayloadColumnResolver());
            Mockito.verify(spyResolver, Mockito.times(1)).resolve();
        }

        @Test
        @DisplayName("Spring-context DatabaseResolver spy resolves to H2 for the live H2 datasource")
        void springContextDatabaseResolverSpyResolvesToH2() {
            assertThat(databaseResolver.resolve()).isEqualToIgnoringCase("H2");
        }

        @Test
        @DisplayName("first store() on a new key succeeds via INSERT path")
        void firstStoreSucceedsViaInsert() {
            var store = buildFallbackStore();
            store.store(referentialPayload);
            assertThat(store.find(NAME, KEY)).isPresent().contains(referentialPayload);
        }

        @Test
        @DisplayName("second store() on the same key succeeds via UPDATE path after INSERT raises DuplicateKeyException")
        void secondStoreSucceedsViaUpdateAfterDuplicateKeyException() {
            var store   = buildFallbackStore();
            var first   = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(3600), new Client(1L, "first"));
            var updated = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(7200), new Client(2L, "updated"));

            store.store(first);
            store.store(updated);   // INSERT fails → UPDATE triggered

            assertThat(store.find(NAME, KEY)).isPresent().contains(updated);
        }

        @Test
        @DisplayName("multiple store() calls on the same key result in exactly one row")
        void multipleStoresOnSameKeyResultInOneRow() {
            var store = buildFallbackStore();
            for (int i = 1; i <= 5; i++) {
                store.store(new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds((long) i * 3600), new Client((long) i, "client-" + i)));
            }
            var count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM TEST_FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?",
                    Integer.class, NAME, KEY);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("find() returns empty on unknown key even in fallback mode")
        void findReturnsEmptyForUnknownKeyInFallbackMode() {
            var store = buildFallbackStore();
            assertThat(store.find(NAME, "no-such-key")).isNotPresent();
        }

        @Test
        @DisplayName("delete() removes the entry stored via INSERT fallback path")
        void deleteRemovesEntryStoredViaInsertFallbackPath() {
            var store = buildFallbackStore();
            store.store(referentialPayload);
            store.delete(referentialPayload);
            assertThat(store.find(NAME, KEY)).isNotPresent();
        }

        @Test
        @DisplayName("cleanByExpiry() removes expired entries in fallback mode")
        void cleanByExpiryRemovesExpiredEntriesInFallbackMode() {
            var store = buildFallbackStore();
            store.store(new ReferentialPayload<>(NAME, "exp-1", false, NOW, NOW.plusSeconds(60),   new Client(1L, "A")));
            store.store(new ReferentialPayload<>(NAME, "exp-2", false, NOW, NOW.plusSeconds(7200), new Client(2L, "B")));

            store.cleanByExpiry(NOW.plusSeconds(1800));

            assertThat(store.find(NAME, "exp-1")).isNotPresent();
            assertThat(store.find(NAME, "exp-2")).isPresent();
        }
    }

    // -------------------------------------------------------------------------
    // mergeQuery — final field resolved once at construction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("mergeQuery — final field resolved once at construction")
    class MergeQueryResolutionScenarios {

        private FailoverStoreJdbc<Client> freshStore() {
            return new FailoverStoreJdbc<>(jdbcTemplate, failoverStoreQueryResolver, rowMapper);
        }

        @Test
        @DisplayName("getMergeQuery() returns non-null H2 merge SQL — resolved at construction from live datasource")
        void getMergeQueryReturnsNonNullH2MergeSqlAtConstruction() {
            assertThat(freshStore().getMergeQuery())
                    .isNotNull()
                    .containsIgnoringCase("MERGE");
        }

        @Test
        @DisplayName("getMergeQuery() returns null when DatabaseResolver returns null — no merge dialect available")
        void getMergeQueryReturnsNullWhenResolverReturnsNull() {
            var nullDbResolver = Mockito.mock(DatabaseResolver.class);
            Mockito.when(nullDbResolver.resolve()).thenReturn(null);
            var queryResolver = new DefaultFailoverStoreQueryResolver("TEST_", serializer, nullDbResolver, new VarcharPayloadColumnResolver());
            assertThat(new FailoverStoreJdbc<>(jdbcTemplate, queryResolver, rowMapper).getMergeQuery()).isNull();
        }

        @Test
        @DisplayName("two store() calls on same key produce exactly one row — merge active for H2")
        void storeWithH2MergeProducesOneRow() {
            var store = freshStore();
            store.store(referentialPayload);
            var updated = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(3600), new Client(99L, "updated"));
            store.store(updated);

            assertThat(store.find(NAME, KEY)).isPresent().contains(updated);
            var count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM TEST_FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?",
                    Integer.class, NAME, KEY);
            assertThat(count).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // BadSqlGrammarException — spy returns invalid merge SQL → H2 throws → fallback
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("BadSqlGrammarException — invalid merge SQL → H2 throws → INSERT/UPDATE fallback")
    class BadSqlGrammarFromQueryResolverSpyScenarios {

        /**
         * Builds a fresh store whose mergeQuery is permanently set to invalid SQL.
         * A spy wrapping a real resolver overrides getMergeQuery() at construction time,
         * so the final field is baked in as bad SQL — no setter required.
         * Using a local instance isolates mergeEnabled state: each test starts with mergeEnabled=true.
         */
        private FailoverStoreJdbc<Client> storeWithBadMergeSql() {
            var realResolver = new DefaultFailoverStoreQueryResolver(
                    "TEST_", serializer, new DefaultDatabaseResolver(jdbcTemplate), new VarcharPayloadColumnResolver());
            var spyResolver = Mockito.spy(realResolver);
            Mockito.doReturn("THIS IS NOT VALID SQL !! @#$").when(spyResolver).getMergeQuery();
            return new FailoverStoreJdbc<>(jdbcTemplate, spyResolver, rowMapper);
        }

        @Test
        @DisplayName("store() completes and record is findable — real H2 throws BadSqlGrammarException, falls back to INSERT")
        void storeCompletesViaInsertFallbackWhenMergeSqlIsInvalid() {
            storeWithBadMergeSql().store(referentialPayload);

            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent().contains(referentialPayload);
        }

        @Test
        @DisplayName("store() on same key after BadSqlGrammarException succeeds — first INSERT, second UPDATE path")
        void storeOnSameKeyAfterBadSqlGrammarSucceeds() {
            var store   = storeWithBadMergeSql();
            var first   = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(3600), new Client(1L, "first"));
            var updated = new ReferentialPayload<>(NAME, KEY, false, NOW, NOW.plusSeconds(7200), new Client(99L, "updated"));

            store.store(first);    // merge → BadSqlGrammarException → INSERT → mergeEnabled=false
            store.store(updated);  // mergeEnabled=false → INSERT → DuplicateKeyException → UPDATE

            assertThat(store.find(NAME, KEY)).isPresent().contains(updated);
        }

        @Test
        @DisplayName("store() with null payload completes successfully after merge fails with invalid SQL")
        void storeWithNullPayloadCompletesViaInsertFallback() {
            referentialPayload.setPayload(null);
            storeWithBadMergeSql().store(referentialPayload);

            assertThat(failoverStoreJdbc.find(NAME, KEY)).isPresent().contains(referentialPayload);
        }
    }

    // -------------------------------------------------------------------------
    // Domain fixture
    // -------------------------------------------------------------------------

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    @NoArgsConstructor
    static class Client extends Referential {
        private Long id;
        private String name;
    }
}
