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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.BasicFailoverExecution;
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.StoreType;
import com.societegenerale.failover.store.async.RejectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverStartupSummaryLoggerTest {

    @Mock ApplicationContext applicationContext;
    @Mock ObjectProvider<FailoverScanner> scannerProvider;

    private FailoverProperties properties;
    private FailoverStartupSummaryLogger logger;

    @BeforeEach
    void setUp() {
        properties = new FailoverProperties();
        logger = new FailoverStartupSummaryLogger(properties, applicationContext, scannerProvider);
    }

    // ── annotation fixtures ───────────────────────────────────────────────────

    @SuppressWarnings("unused")
    static class Fixtures {
        // Annotation-only fixtures: the method body is intentionally empty;
        // only the @Failover annotation metadata is used by the tests via reflection.
        @Failover(name = "basic-failover")
        void basicFailover() { /* annotation fixture */ }

        @Failover(name = "full-failover", expiryDuration = 30, expiryUnit = ChronoUnit.MINUTES,
                  domain = "my-domain", keyGenerator = "myKey", expiryPolicy = "myExpiry",
                  payloadSplitter = "mySplitter", recoverAll = true)
        void fullFailover() { /* annotation fixture */ }

        @Failover(name = "expr-failover",
                  expiryDurationExpression = "${expiry.duration:5}",
                  expiryUnitExpression = "${expiry.unit:DAYS}")
        void expressionFailover() { /* annotation fixture */ }

        @Failover(name = "expr-duration-only",
                  expiryDurationExpression = "${expiry.duration:5}")
        void expressionDurationOnly() { /* annotation fixture */ }
    }

    private static Failover annotation(String method) throws Exception {
        return Fixtures.class.getDeclaredMethod(method).getAnnotation(Failover.class);
    }

    // ── toConfigLine ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toConfigLine")
    class ToConfigLine {

        @Test
        @DisplayName("default failover — shows name and expiry only")
        void defaultFailover() throws Exception {
            assertThat(FailoverStartupSummaryLogger.toConfigLine(annotation("basicFailover")))
                    .isEqualTo("basic-failover : expiry=1 HOURS");
        }

        @Test
        @DisplayName("full config — shows all non-default fields")
        void fullConfig() throws Exception {
            assertThat(FailoverStartupSummaryLogger.toConfigLine(annotation("fullFailover")))
                    .isEqualTo("full-failover : expiry=30 MINUTES, domain='my-domain', "
                             + "keyGenerator='myKey', expiryPolicy='myExpiry', splitter='mySplitter', recoverAll=true");
        }

        @Test
        @DisplayName("expression expiry — expression takes precedence over numeric duration")
        void expressionExpiry() throws Exception {
            assertThat(FailoverStartupSummaryLogger.toConfigLine(annotation("expressionFailover")))
                    .isEqualTo("expr-failover : expiry=${expiry.duration:5} ${expiry.unit:DAYS}");
        }

        @Test
        @DisplayName("expression duration only — falls back to default expiryUnit")
        void expressionDurationOnly() throws Exception {
            assertThat(FailoverStartupSummaryLogger.toConfigLine(annotation("expressionDurationOnly")))
                    .isEqualTo("expr-duration-only : expiry=${expiry.duration:5} HOURS");
        }
    }

    // ── buildSummary — infrastructure ─────────────────────────────────────────

    @Nested
    @DisplayName("buildSummary — infrastructure")
    class BuildSummaryInfrastructure {

        @BeforeEach
        void mockDefaults() {
            when(applicationContext.getBeanNamesForType(FailoverExecution.class))
                    .thenReturn(new String[]{"failoverExecution"});
            lenient().when(applicationContext.getBean("failoverExecution"))
                    .thenReturn(mock(BasicFailoverExecution.class));
            when(applicationContext.getBeanNamesForType(MicrometerObservablePublisher.class))
                    .thenReturn(new String[]{});
            when(scannerProvider.getIfAvailable()).thenReturn(null);
        }

        @Test
        @DisplayName("INMEMORY store — no extra details")
        void inMemoryStore() {
            String summary = logger.buildSummary();
            assertThat(summary)
                    .contains("execution        : BasicFailoverExecution")
                    .contains("exception-policy : RETHROW")
                    .contains("store            : INMEMORY, async=true, multitenant=false")
                    .contains("scatter          : parallel=true, timeout=10s")
                    .contains("publisher        : MdcLogger (async")
                    .contains("snapshot-store   : n/a")
                    .doesNotContain("[failover endpoints]");
        }

        @Test
        @DisplayName("JDBC store — shows table-prefix in brackets")
        void jdbcStore() {
            properties.getStore().setType(StoreType.JDBC);
            properties.getStore().getJdbc().setTablePrefix("DEMO_");
            String summary = logger.buildSummary();
            assertThat(summary).contains("store            : JDBC, async=true, multitenant=false [table-prefix='DEMO_']");
        }

        @Test
        @DisplayName("JDBC store — blank table-prefix shows (none)")
        void jdbcStoreNoPrefix() {
            properties.getStore().setType(StoreType.JDBC);
            String summary = logger.buildSummary();
            assertThat(summary).contains("[table-prefix='(none)']");
        }

        @Test
        @DisplayName("JDBC store + multitenant — shows prefix and strategy")
        void jdbcStoreMultitenant() {
            properties.getStore().setType(StoreType.JDBC);
            properties.getStore().getJdbc().setTablePrefix("DEMO_");
            properties.getStore().getMultitenant().setEnabled(true);
            String summary = logger.buildSummary();
            assertThat(summary)
                    .contains("multitenant=true")
                    .contains("[table-prefix='DEMO_', strategy=TABLE_PREFIX]");
        }

        @Test
        @DisplayName("CAFFEINE store — shows max-size")
        void caffeineStore() {
            properties.getStore().setType(StoreType.CAFFEINE);
            properties.getStore().getCaffeine().setMaxSize(50_000L);
            String summary = logger.buildSummary();
            assertThat(summary).contains("store            : CAFFEINE, async=true, multitenant=false [max-size=50000]");
        }

        @Test
        @DisplayName("INMEMORY + multitenant — shows strategy")
        void inMemoryMultitenant() {
            properties.getStore().getMultitenant().setEnabled(true);
            String summary = logger.buildSummary();
            assertThat(summary)
                    .contains("multitenant=true")
                    .contains("[strategy=TABLE_PREFIX]");
        }

        @Test
        @DisplayName("bounded async store executor — shows store-executor line")
        void boundedStoreExecutor() {
            properties.getStore().setAsync(true);
            properties.getStore().getAsyncExecutor().setConcurrencyLimit(10);
            properties.getStore().getAsyncExecutor().setRejectionPolicy(RejectionPolicy.ABORT);
            String summary = logger.buildSummary();
            assertThat(summary)
                    .contains("store-executor   : bounded, concurrencyLimit=10, rejectionPolicy=ABORT");
        }

        @Test
        @DisplayName("unbounded async store executor — no store-executor line")
        void unboundedStoreExecutor() {
            properties.getStore().setAsync(true);
            properties.getStore().getAsyncExecutor().setConcurrencyLimit(0);
            String summary = logger.buildSummary();
            assertThat(summary).doesNotContain("store-executor");
        }

        @Test
        @DisplayName("scatter concurrencyLimit > 0 — shows scatter concurrencyLimit and rejectionPolicy")
        void scatterBounded() {
            properties.getScatter().setConcurrencyLimit(4);
            properties.getScatter().setRejectionPolicy(RejectionPolicy.DISCARD);
            String summary = logger.buildSummary();
            assertThat(summary)
                    .contains("scatter          : parallel=true, timeout=10s, concurrencyLimit=4, rejectionPolicy=DISCARD");
        }

        @Test
        @DisplayName("Micrometer publisher present — shows MdcLogger + Micrometer")
        void micrometerPublisher() {
            when(applicationContext.getBeanNamesForType(MicrometerObservablePublisher.class))
                    .thenReturn(new String[]{"micrometerObservablePublisher"});
            String summary = logger.buildSummary();
            assertThat(summary).contains("publisher        : MdcLogger + Micrometer");
        }

        @Test
        @DisplayName("async publisher disabled — shows (sync)")
        void syncPublisher() {
            properties.getObservable().getAsync().setEnabled(false);
            String summary = logger.buildSummary();
            assertThat(summary).contains("publisher        : MdcLogger (sync)");
        }

        @Test
        @DisplayName("no FailoverExecution bean — shows unknown")
        void noExecutionBean() {
            when(applicationContext.getBeanNamesForType(FailoverExecution.class))
                    .thenReturn(new String[]{});
            String summary = logger.buildSummary();
            assertThat(summary).contains("execution        : unknown");
        }
    }

    // ── buildSummary — failover endpoints ────────────────────────────────────

    @Nested
    @DisplayName("buildSummary — failover endpoints")
    class BuildSummaryEndpoints {

        @BeforeEach
        void mockDefaults() {
            when(applicationContext.getBeanNamesForType(FailoverExecution.class))
                    .thenReturn(new String[]{});
            when(applicationContext.getBeanNamesForType(MicrometerObservablePublisher.class))
                    .thenReturn(new String[]{});
        }

        @Test
        @DisplayName("no scanner — no failover endpoints section")
        void noScanner() {
            when(scannerProvider.getIfAvailable()).thenReturn(null);
            assertThat(logger.buildSummary()).doesNotContain("[failover endpoints]");
        }

        @Test
        @DisplayName("scanner with failovers — lists them sorted by name")
        void scannerWithFailovers() throws Exception {
            FailoverScanner scanner = mock(FailoverScanner.class);
            when(scanner.findAllFailover()).thenReturn(List.of(
                    annotation("fullFailover"),
                    annotation("basicFailover")
            ));
            when(scannerProvider.getIfAvailable()).thenReturn(scanner);

            String summary = logger.buildSummary();
            assertThat(summary)
                    .contains("[failover endpoints] (2)")
                    .contains("basic-failover : expiry=1 HOURS")
                    .contains("full-failover : expiry=30 MINUTES");
            // verify sorted — basic-failover appears before full-failover
            assertThat(summary.indexOf("basic-failover")).isLessThan(summary.indexOf("full-failover"));
        }

        @Test
        @DisplayName("scanner with no failovers — shows (0) count")
        void scannerEmpty() {
            FailoverScanner scanner = mock(FailoverScanner.class);
            when(scanner.findAllFailover()).thenReturn(List.of());
            when(scannerProvider.getIfAvailable()).thenReturn(scanner);
            assertThat(logger.buildSummary()).contains("[failover endpoints] (0)");
        }
    }
}
