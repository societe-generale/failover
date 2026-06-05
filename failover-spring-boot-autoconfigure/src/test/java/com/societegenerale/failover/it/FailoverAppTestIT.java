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

package com.societegenerale.failover.it;

import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.it.domain.ThirdPartiesResult;
import com.societegenerale.failover.it.domain.ThirdParty;
import com.societegenerale.failover.it.service.RemoteThirdPartyService;
import com.societegenerale.failover.it.service.ThirdPartyService;
import com.societegenerale.failover.it.service.ThirdPartyServiceController;
import com.societegenerale.failover.it.service.ThirdPartyServiceImpl;
import org.assertj.core.api.ListAssert;
import org.assertj.core.api.RecursiveComparisonAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the failover framework against a real H2 JDBC store.
 *
 * <h2>What is tested</h2>
 * <ul>
 *   <li>Store-on-success and recover-on-failure with a real H2 database</li>
 *   <li>Scatter/gather: composite result split into N individual rows; rows gathered and merged on recover</li>
 *   <li>Partial recovery: missing or expired slices recovered as {@code null}</li>
 *   <li>Order-variant recovery: same rows served regardless of CSV argument ordering</li>
 *   <li>Custom expiry policy: entries stored with past expiry → immediately unrecoverable</li>
 *   <li>Custom key generator: all argument variants map to the same database row</li>
 *   <li>Expiry cleanup: {@code FailoverHandler.clean()} removes expired rows</li>
 *   <li>Parallel scatter: all slices written concurrently, all readable afterward</li>
 * </ul>
 *
 * <h2>Infrastructure</h2>
 * <ul>
 *   <li>H2 in-memory database, table {@code IT_FAILOVER_STORE}</li>
 *   <li>{@code failover.store.async=false} — synchronous writes for deterministic assertions</li>
 *   <li>{@code failover.exception-policy=never_throw} — null recovery instead of re-throw</li>
 *   <li>Table cleared in {@code @BeforeEach}; failure mode reset via {@link ThirdPartyServiceController}</li>
 * </ul>
 *
 * <h2>Data</h2>
 * <p>The service under test ({@link ThirdPartyServiceImpl}) owns an immutable referential of
 * 10 {@link ThirdParty} entries (IDs 1–10) and always returns a fresh copy per call, so the
 * failover framework's in-place mutation of {@code upToDate} / {@code asOf} / {@code metadata}
 * never corrupts test fixtures. Recovery assertions therefore ignore those meta-fields and
 * compare only the business payload (id, name, score) via
 * {@link #assertTp(ThirdParty)} / {@link #assertTps(List)}.
 *
 * @author Anand Manissery
 */
@SpringBootTest(classes = FailoverScatterGatherITApplication.class)
@TestPropertySource(properties = {
        "failover.exception-policy=never_throw",
        "failover.store.type=jdbc",
        "failover.store.jdbc.table-prefix=IT_",
        "failover.store.async=false",
        "failover.package-to-scan=com.societegenerale.failover.it",
        "spring.datasource.url=jdbc:h2:mem:failover_scatter_it;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.schema-locations=classpath:schema-it.sql",
        "spring.sql.init.mode=always"
})
class FailoverAppTestIT {

    // ── Domain fixtures (sourced from the service's immutable referential) ────

    static final ThirdParty TP_1 = RemoteThirdPartyService.getCopyOf("1");
    static final ThirdParty TP_2 = RemoteThirdPartyService.getCopyOf("2");
    static final ThirdParty TP_3 = RemoteThirdPartyService.getCopyOf("3");

    // ── Args constants (status / csvIds / region) ─────────────────────────────

    static final String STATUS   = "active";
    static final String REGION   = "India";
    static final String CSV_1_2_3 = "1,2,3";
    static final String CSV_3_2_1 = "3,2,1";
    static final String CSV_1_2   = "1,2";

    // ── Spring-managed infrastructure ─────────────────────────────────────────

    @Autowired
    private ThirdPartyService           service;
    @Autowired
    private ThirdPartyServiceController ctrl;
    @Autowired
    private JdbcTemplate                jdbc;
    @Autowired
    private FailoverHandler<Object>     failoverHandler;
    @Autowired
    private FailoverClock failoverClock;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void resetStateAndDb() {
        ctrl.reset();
        jdbc.update("DELETE FROM IT_FAILOVER_STORE");
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    int rowCount(String failoverName) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM IT_FAILOVER_STORE WHERE FAILOVER_NAME = ?",
                Integer.class, failoverName);
    }

    List<Map<String, Object>> rows(String failoverName) {
        return jdbc.queryForList(
                "SELECT * FROM IT_FAILOVER_STORE WHERE FAILOVER_NAME = ? ORDER BY FAILOVER_KEY",
                failoverName);
    }

    /**
     * Sets EXPIRE_ON to 1 hour in the past for all rows of the given failover name.
     * Uses H2 native {@code DATEADD} to sidestep JDBC {@code LocalDateTime → TIMESTAMP} binding
     * edge cases; also asserts at least one row was updated so the helper fails fast.
     */
    void expireAll(String failoverName) {
        int updated = jdbc.update("UPDATE IT_FAILOVER_STORE SET EXPIRE_ON = ? WHERE FAILOVER_NAME = ?",
                failoverClock.now().minusHours(1), failoverName);
        assertThat(updated).as("expireAll(%s) — expected ≥1 row to be updated", failoverName).isPositive();
    }

    void deleteSliceForId(String failoverName, String id) {
        jdbc.update(
                "DELETE FROM IT_FAILOVER_STORE WHERE FAILOVER_NAME = ? AND PAYLOAD LIKE ?",
                failoverName, "%\"id\":" + id + "%");
    }

    // ── Assertion helpers — ignore Referential meta-fields on recovered objects ─

    /**
     * Compares a recovered {@link ThirdParty} by business fields only (id, name, score),
     * ignoring {@code upToDate}, {@code asOf}, and {@code metadata} which are set by
     * the failover framework on recovery.
     */
    static RecursiveComparisonAssert<?> assertTp(ThirdParty actual) {
        return assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields("upToDate", "asOf", "metadata");
    }

    /**
     * Compares a list of recovered {@link ThirdParty} objects by business fields only,
     * ignoring {@code upToDate}, {@code asOf}, and {@code metadata}.
     */
    static ListAssert<ThirdParty> assertTps(List<ThirdParty> actual) {
        return assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("upToDate", "asOf", "metadata");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1 · Single-Key Failover
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1 · Single-Key Failover — store, recover, null, expiry")
    class SingleKeyFailover {

        static final String NAME = "it-tp-single";

        @Test
        @DisplayName("successful call stores one row in H2")
        void successfulCallStoresOneRowInH2() {
            service.fetchOne("1");

            assertThat(rowCount(NAME)).isEqualTo(1);
        }

        @Test
        @DisplayName("failed call recovers previously stored ThirdParty from H2")
        void failedCallRecoversFromH2() {
            service.fetchOne("1");                  // success → stores ThirdParty-1

            ctrl.simulatePrimaryFailure();
            ThirdParty recovered = service.fetchOne("1");  // fail → recover

            assertTp(recovered).isEqualTo(TP_1);
        }

        @Test
        @DisplayName("failed call with nothing stored returns null — no data in H2")
        void failedCallWithNoStoredDataReturnsNull() {
            ctrl.simulatePrimaryFailure();
            ThirdParty recovered = service.fetchOne("1");

            assertThat(recovered).isNull();
            assertThat(rowCount(NAME)).isZero();
        }

        @Test
        @DisplayName("different argument values produce different rows — default key generator is arg-sensitive")
        void differentArgsDifferentRows() {
            service.fetchOne("1");
            service.fetchOne("2");

            assertThat(rowCount(NAME)).isEqualTo(2);
        }

        @Test
        @DisplayName("manually expired row — recovery returns null and removes the row from H2")
        void manuallyExpiredRowRecoveryReturnsNull() {
            service.fetchOne("1");
            expireAll(NAME);

            ctrl.simulatePrimaryFailure();
            ThirdParty recovered = service.fetchOne("1");

            assertThat(recovered).isNull();
            assertThat(rowCount(NAME)).isZero(); // expired row is deleted by DefaultFailoverHandler on recovery
        }

        @Test
        @DisplayName("re-store on repeated success overwrites previous row — row count stays 1")
        void repeatedSuccessOverwritesSameRow() {
            service.fetchOne("1");   // stores ThirdParty-1
            service.fetchOne("1");   // overwrites with another copy of ThirdParty-1 (same key)

            assertThat(rowCount(NAME)).isEqualTo(1);

            ctrl.simulatePrimaryFailure();
            assertTp(service.fetchOne("1")).isEqualTo(TP_1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2 · Scatter-Gather Failover
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2 · Scatter-Gather Failover — split, recover, merge")
    class ScatterGatherFailover {

        static final String NAME = "it-tp-scatter";

        @Test
        @DisplayName("successful call with 3 IDs stores 3 individual rows in H2")
        void successfulCallStoresNSlicesInH2() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);

            assertThat(rowCount(NAME)).isEqualTo(3);
        }

        @Test
        @DisplayName("failed call recovers all 3 slices from H2 and merges into ThirdPartiesResult")
        void failedCallRecoversAllSlicesAndMerges() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);   // store

            ctrl.simulatePrimaryFailure();
            ThirdPartiesResult recovered = service.fetchAll(STATUS, CSV_1_2_3, REGION);

            assertThat(recovered).isNotNull();
            assertTps(recovered.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);
        }

        @Test
        @DisplayName("partial recovery — missing slice for id=2 produces null in that position")
        void partialRecoveryMissingSliceIsNull() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);   // store all 3
            deleteSliceForId(NAME, "2");                    // remove slice for id=2

            ctrl.simulatePrimaryFailure();
            ThirdPartiesResult recovered = service.fetchAll(STATUS, CSV_1_2_3, REGION);

            List<ThirdParty> parties = recovered.getThirdParties();
            assertTp(parties.get(0)).isEqualTo(TP_1);
            assertThat(parties.get(1)).isNull();
            assertTp(parties.get(2)).isEqualTo(TP_3);
        }

        @Test
        @DisplayName("order-variant recover — '3,2,1' reads same rows as '1,2,3', result follows request order")
        void differentCsvOrderReadsFromSameSlices() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);   // store once

            ctrl.simulatePrimaryFailure();
            ThirdPartiesResult ascResult  = service.fetchAll(STATUS, CSV_1_2_3, REGION);
            ThirdPartiesResult descResult = service.fetchAll(STATUS, CSV_3_2_1, REGION);

            // Same set of ThirdParties — same H2 rows
            assertTps(ascResult.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);
            assertTps(descResult.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);

            // Order follows the request (not insertion order)
            assertTps(ascResult.getThirdParties()).containsExactly(TP_1, TP_2, TP_3);
            assertTps(descResult.getThirdParties()).containsExactly(TP_3, TP_2, TP_1);
        }

        @Test
        @DisplayName("idempotent re-store — overlapping args overwrite existing rows, total stays at 3")
        void idempotentReStoreOverlappingArgs() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);   // store 1,2,3
            service.fetchAll(STATUS, CSV_1_2_3, REGION);   // overwrite 1,2,3

            assertThat(rowCount(NAME)).isEqualTo(3);
        }

        @Test
        @DisplayName("storing 2 IDs then 3 IDs results in 3 rows — overlapping IDs overwrite, new IDs inserted")
        void partialOverlapStoreMergesRows() {
            service.fetchAll(STATUS, CSV_1_2, REGION);     // store 1,2
            service.fetchAll(STATUS, CSV_1_2_3, REGION);   // overwrite 1,2; insert 3

            assertThat(rowCount(NAME)).isEqualTo(3);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3 · Custom Expiry Policy
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3 · Custom Expiry Policy — immediatelyExpiredExpiryPolicy")
    class CustomExpiryPolicy {

        static final String NAME = "it-tp-expired";

        @Test
        @DisplayName("row is stored in H2 even with past expiry — EXPIRE_ON column is in the past")
        void rowStoredWithPastExpiry() {
            service.fetchOneImmediatelyExpired("1");

            assertThat(rowCount(NAME)).isEqualTo(1);
            // queryForList returns TIMESTAMP columns as java.sql.Timestamp — convert before comparing
            LocalDateTime expireOn = ((Timestamp) rows(NAME).getFirst().get("EXPIRE_ON")).toLocalDateTime();
            assertThat(expireOn).isBefore(LocalDateTime.now());
        }

        @Test
        @DisplayName("recovery returns null because the custom policy marks the row as expired on arrival")
        void recoveryReturnsNullBecauseRowIsImmediatelyExpired() {
            service.fetchOneImmediatelyExpired("1");   // stores with past EXPIRE_ON

            ctrl.simulatePrimaryFailure();
            ThirdParty recovered = service.fetchOneImmediatelyExpired("1");

            assertThat(recovered).isNull();             // row exists in DB but is expired
        }

        @Test
        @DisplayName("default fetchOne stores same data with future EXPIRE_ON — not affected by custom policy")
        void defaultPolicyNotAffectedByCustomPolicy() {
            service.fetchOne("1");                      // uses default policy → future expiry

            ctrl.simulatePrimaryFailure();
            assertTp(service.fetchOne("1")).isEqualTo(TP_1);  // recovers normally
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4 · Custom Key Generator
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4 · Custom Key Generator — fixedKeyGenerator")
    class CustomKeyGenerator {

        static final String NAME = "it-tp-fixed-key";

        @Test
        @DisplayName("calls with different args map to the same row — fixed key generator ignores args")
        void differentArgsMappedToSameRow() {
            service.fetchOneWithFixedKey("1");   // stores ThirdParty-1
            service.fetchOneWithFixedKey("2");   // overwrites with ThirdParty-2 (same key!)

            assertThat(rowCount(NAME)).isEqualTo(1);   // still 1 row, not 2
        }

        @Test
        @DisplayName("recovery for any argument returns the last-stored value — all args share one row")
        void recoveryForAnyArgReturnsLastStoredValue() {
            service.fetchOneWithFixedKey("1");   // store ThirdParty-1
            service.fetchOneWithFixedKey("2");   // overwrite with ThirdParty-2

            ctrl.simulatePrimaryFailure();
            // Both "1" and "3" resolve to the same row containing ThirdParty-2
            assertTp(service.fetchOneWithFixedKey("1")).isEqualTo(TP_2);
            assertTp(service.fetchOneWithFixedKey("3")).isEqualTo(TP_2);
        }

        @Test
        @DisplayName("default key gen (fetchOne) keeps separate rows per arg — not affected by fixed key gen")
        void defaultKeyGenUnaffectedByFixedKeyGen() {
            service.fetchOne("1");
            service.fetchOne("2");

            assertThat(rowCount("it-tp-single")).isEqualTo(2);   // separate rows

            ctrl.simulatePrimaryFailure();
            assertTp(service.fetchOne("1")).isEqualTo(TP_1);
            assertTp(service.fetchOne("2")).isEqualTo(TP_2);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5 · Custom Payload Splitter
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5 · Custom Payload Splitter — itThirdPartyPayloadSplitter")
    class CustomPayloadSplitter {

        static final String NAME = "it-tp-scatter";

        @Test
        @DisplayName("splitter called on store — N ThirdParty slices produce N rows with individual args")
        void splitterProducesNRowsOnStore() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);

            List<Map<String, Object>> storedRows = rows(NAME);
            assertThat(storedRows).hasSize(3);
            // Each row is uniquely keyed (slice args differ by ID at index 1)
            assertThat(storedRows.stream().map(r -> r.get("FAILOVER_KEY")).distinct()).hasSize(3);
        }

        @Test
        @DisplayName("splitter called on recover — individual slices gathered and merged back into composite")
        void splitterMergesSlicesOnRecover() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);

            ctrl.simulatePrimaryFailure();
            ThirdPartiesResult result = service.fetchAll(STATUS, CSV_1_2_3, REGION);

            assertTps(result.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);
        }

        @Test
        @DisplayName("status and region args preserved in every slice — splitter copies prefix and suffix args")
        void prefixAndSuffixArgsPreservedInSlices() {
            service.fetchAll("inactive", "1,2", "US");   // different status and region

            ctrl.simulatePrimaryFailure();
            ThirdPartiesResult result = service.fetchAll("inactive", "1,2", "US");

            assertTps(result.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6 · Expiry Cleanup
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6 · Expiry Cleanup — FailoverHandler.clean() removes expired rows")
    class ExpiryCleanup {

        @Test
        @DisplayName("clean() removes expired single-key row from H2")
        void cleanRemovesExpiredSingleKeyRow() {
            service.fetchOne("1");                     // 1 row stored
            expireAll("it-tp-single");

            failoverHandler.clean();

            assertThat(rowCount("it-tp-single")).isZero();
        }

        @Test
        @DisplayName("clean() removes all N expired scatter rows in one sweep")
        void cleanRemovesAllExpiredScatterRows() {
            service.fetchAll(STATUS, CSV_1_2_3, REGION);   // 3 rows stored
            expireAll("it-tp-scatter");

            failoverHandler.clean();

            assertThat(rowCount("it-tp-scatter")).isZero();
        }

        @Test
        @DisplayName("clean() leaves non-expired rows untouched")
        void cleanLeavesNonExpiredRowsUntouched() {
            service.fetchOne("1");                     // future expiry
            service.fetchOne("2");                     // future expiry
            expireAll("it-tp-single");
            // re-store "1" with future expiry (overwrite) — only TP_2's row stays expired
            jdbc.update("UPDATE IT_FAILOVER_STORE SET EXPIRE_ON = ? WHERE PAYLOAD LIKE ?", failoverClock.now().plusHours(1), "%ThirdParty-1%" );

            failoverHandler.clean();

            assertThat(rowCount("it-tp-single")).isEqualTo(1);  // only TP_1's row survives
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7 · Parallel Scatter  (separate Spring context — failover.scatter.parallel=true)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @SpringBootTest(classes = FailoverScatterGatherITApplication.class)
    @TestPropertySource(properties = {
            "failover.exception-policy=never_throw",
            "failover.store.type=jdbc",
            "failover.store.jdbc.table-prefix=IT_",
            "failover.store.async=false",
            "failover.scatter.parallel=true",
            "failover.package-to-scan=com.societegenerale.failover.it",
            "spring.datasource.url=jdbc:h2:mem:failover_scatter_parallel_it;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.schema-locations=classpath:schema-it.sql",
            "spring.sql.init.mode=always"
    })
    @DisplayName("7 · Parallel Scatter — failover.scatter.parallel=true")
    class ParallelScatter {

        static final String NAME = "it-tp-scatter";

        @Autowired ThirdPartyService thirdPartyService;
        @Autowired ThirdPartyServiceController serviceController;
        @Autowired JdbcTemplate jdbcTemplate;

        @BeforeEach
        void resetParallel() {
            serviceController.reset();
            jdbcTemplate.update("DELETE FROM IT_FAILOVER_STORE");
        }

        @Test
        @DisplayName("parallel store writes all N slices to H2 concurrently — same result as sequential")
        void parallelStoreWritesAllSlices() {
            thirdPartyService.fetchAll(STATUS, CSV_1_2_3, REGION);
            int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM IT_FAILOVER_STORE WHERE FAILOVER_NAME = ?", Integer.class, NAME);
            assertThat(count).isEqualTo(3);
        }

        @Test
        @DisplayName("parallel recover gathers all slices and merges correctly")
        void parallelRecoverGathersAndMerges() {
            thirdPartyService.fetchAll(STATUS, CSV_1_2_3, REGION);

            serviceController.simulatePrimaryFailure();
            ThirdPartiesResult recovered = thirdPartyService.fetchAll(STATUS, CSV_1_2_3, REGION);

            assertTps(recovered.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 8 · Rethrow Exception Policy
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @SpringBootTest(classes = FailoverScatterGatherITApplication.class)
    @TestPropertySource(properties = {
            "failover.exception-policy=rethrow",
            "failover.store.type=jdbc",
            "failover.store.jdbc.table-prefix=IT_",
            "failover.store.async=false",
            "failover.package-to-scan=com.societegenerale.failover.it",
            "spring.datasource.url=jdbc:h2:mem:failover_rethrow_it;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.schema-locations=classpath:schema-it.sql",
            "spring.sql.init.mode=always"
    })
    @DisplayName("8 · Rethrow Exception Policy — propagates original exception when recovery unavailable")
    class RethrowExceptionPolicy {

        static final String NAME = "it-tp-single";

        @Autowired ThirdPartyService           rethrowService;
        @Autowired ThirdPartyServiceController rethrowCtrl;
        @Autowired JdbcTemplate                rethrowJdbc;
        @Autowired FailoverClock               rethrowClock;

        @BeforeEach
        void reset() {
            rethrowCtrl.reset();
            rethrowJdbc.update("DELETE FROM IT_FAILOVER_STORE");
        }

        @Test
        @DisplayName("original exception propagates when nothing is stored — no fallback available")
        void exceptionPropagatesWhenNothingStored() {
            rethrowCtrl.simulatePrimaryFailure();

            assertThatThrownBy(() -> rethrowService.fetchOne("1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("unavailable");
        }

        @Test
        @DisplayName("recovered value returned silently when a valid store entry exists")
        void recoveredValueReturnedWhenStoreHasValidEntry() {
            rethrowService.fetchOne("1");       // success → stores ThirdParty-1

            rethrowCtrl.simulatePrimaryFailure();
            ThirdParty recovered = rethrowService.fetchOne("1");

            assertTp(recovered).isEqualTo(TP_1);
        }

        @Test
        @DisplayName("original exception propagates when only store entry is expired")
        void exceptionPropagatesWhenStoreEntryIsExpired() {
            rethrowService.fetchOne("1");
            rethrowJdbc.update(
                    "UPDATE IT_FAILOVER_STORE SET EXPIRE_ON = ? WHERE FAILOVER_NAME = ?",
                    rethrowClock.now().minusHours(1), NAME);

            rethrowCtrl.simulatePrimaryFailure();

            assertThatThrownBy(() -> rethrowService.fetchOne("1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("unavailable");
        }

        @Test
        @DisplayName("scatter-gather: merge always produces non-null composite — rethrow does not fire, null slices returned instead")
        void scatterMergeReturnsPartialResultWhenNoSlicesStored() {
            // Scatter splitter merge() always constructs a ThirdPartiesResult (even with null parties),
            // so RethrowIfNoRecovery sees a non-null recovered value and returns it rather than rethrowing.
            rethrowCtrl.simulatePrimaryFailure();

            ThirdPartiesResult result = rethrowService.fetchAll(STATUS, CSV_1_2_3, REGION);

            assertThat(result).isNotNull();
            assertThat(result.getThirdParties()).hasSize(3).containsOnlyNulls();
        }

        @Test
        @DisplayName("scatter-gather: recovered result returned when all slices present")
        void recoveredResultReturnedWhenAllScatterSlicesPresent() {
            rethrowService.fetchAll(STATUS, CSV_1_2_3, REGION);   // stores 3 slices

            rethrowCtrl.simulatePrimaryFailure();
            ThirdPartiesResult recovered = rethrowService.fetchAll(STATUS, CSV_1_2_3, REGION);

            assertTps(recovered.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 9 · Custom RecoveredPayloadHandler — empty-object fallback with error metadata
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @SpringBootTest(classes = {FailoverScatterGatherITApplication.class, CustomRecoveredPayloadHandlerIT.EmptyFallbackConfig.class})
    @TestPropertySource(properties = {
            "failover.exception-policy=never_throw",
            "failover.store.type=jdbc",
            "failover.store.jdbc.table-prefix=IT_",
            "failover.store.async=false",
            "failover.package-to-scan=com.societegenerale.failover.it",
            "spring.datasource.url=jdbc:h2:mem:failover_custom_handler_it;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.schema-locations=classpath:schema-it.sql",
            "spring.sql.init.mode=always"
    })
    @DisplayName("9 · Custom RecoveredPayloadHandler — empty object with error metadata on zero recovery")
    class CustomRecoveredPayloadHandlerIT {

        static final String NAME = "it-tp-single";

        @TestConfiguration
        static class EmptyFallbackConfig {
            @Bean
            public RecoveredPayloadHandler recoveredPayloadHandler() {
                return new RecoveredPayloadHandler() {
                    @Override
                    public <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload, Throwable cause) {
                        if (payload != null) {
                            return payload;
                        }
                        if (ThirdParty.class.isAssignableFrom(clazz)) {
                            ThirdParty empty = new ThirdParty();
                            empty.setUpToDate(false);
                            empty.getMetadata()
                                 .withInfo("failover-name", failover.name())
                                 .withInfo("exception-type", cause.getClass().getSimpleName())
                                 .withInfo("error", cause.getMessage());
                            return clazz.cast(empty);
                        }
                        return null;
                    }
                };
            }
        }

        @Autowired ThirdPartyService           handlerService;
        @Autowired ThirdPartyServiceController handlerCtrl;
        @Autowired JdbcTemplate                handlerJdbc;
        @Autowired FailoverClock               handlerClock;

        @BeforeEach
        void reset() {
            handlerCtrl.reset();
            handlerJdbc.update("DELETE FROM IT_FAILOVER_STORE");
        }

        @Test
        @DisplayName("returns empty ThirdParty with error metadata when nothing is stored — no null returned to caller")
        void returnsEmptyObjectWithErrorMetadataWhenNothingStored() {
            handlerCtrl.simulatePrimaryFailure();

            ThirdParty result = handlerService.fetchOne("1");

            assertThat(result).isNotNull();
            assertThat(result.getId()).isNull();
            assertThat(result.getMetadata().getInfo())
                    .containsEntry("failover-name", NAME)
                    .containsKey("exception-type")
                    .containsKey("error");
        }

        @Test
        @DisplayName("passes recovered value through unchanged when store entry is valid")
        void passesThroughRecoveredValueWhenStoreHasEntry() {
            handlerService.fetchOne("1");   // success → stores ThirdParty-1

            handlerCtrl.simulatePrimaryFailure();
            ThirdParty recovered = handlerService.fetchOne("1");

            assertTp(recovered).isEqualTo(TP_1);
        }

        @Test
        @DisplayName("returns empty ThirdParty with metadata even after expiry — null recovery triggers fallback")
        void returnsEmptyObjectWithMetadataAfterExpiry() {
            handlerService.fetchOne("1");
            handlerJdbc.update(
                    "UPDATE IT_FAILOVER_STORE SET EXPIRE_ON = ? WHERE FAILOVER_NAME = ?",
                    handlerClock.now().minusHours(2), NAME);

            handlerCtrl.simulatePrimaryFailure();
            ThirdParty result = handlerService.fetchOne("1");

            assertThat(result).isNotNull();
            assertThat(result.getId()).isNull();
            assertThat(result.getMetadata().getInfo())
                    .containsEntry("failover-name", NAME)
                    .containsKey("exception-type");
        }
    }
}
