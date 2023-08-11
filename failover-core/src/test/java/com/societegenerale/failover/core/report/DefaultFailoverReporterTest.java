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

package com.societegenerale.failover.core.report;

import com.google.common.collect.ImmutableMap;
import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.BasicFailoverExpiryExtractor;
import com.societegenerale.failover.core.report.manifest.ManifestInfoExtractor;
import com.societegenerale.failover.core.scanner.DefaultFailoverScanner;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@Slf4j
class DefaultFailoverReporterTest {

    private static final Map<String,String> ADDITIONAL_INFO = ImmutableMap.of("additional-info-key", "additional-info-value");

    private static final Map<String,String> METADATA_INFO = ImmutableMap.of("lib-metadata-title", "failover-core", "lib-metadata-version", "1.0.0");

    private static final LocalDateTime NOW = LocalDateTime.now();

    private InMemoryReportPublisher reportPublisher;

    private DefaultFailoverReporter defaultFailoverReporter;

    @Mock
    private ManifestInfoExtractor manifestInfoExtractor;

    @Mock
    private FailoverClock clock;

    @BeforeEach
    void setUp() {
        lenient().when(clock.now()).thenReturn(NOW);
        lenient().when(manifestInfoExtractor.extract(any())).thenReturn(METADATA_INFO);
        reportPublisher = new DefaultFailoverReporterTest.InMemoryReportPublisher(clock);
        FailoverScanner failoverScanner = new DefaultFailoverScanner("com.societegenerale.failover.core.report");
        log.info("The class {} is used for @Failover Scan", SomeReferential.class);
        defaultFailoverReporter = new DefaultFailoverReporter(reportPublisher, failoverScanner, clock, manifestInfoExtractor, new BasicFailoverExpiryExtractor(), ADDITIONAL_INFO);
    }

    @Test
    void shouldPublishReport() {
        defaultFailoverReporter.report();
        assertThat(reportPublisher.getMetricsMap().get("failover-report-find-by-id").getInfo())
                .containsEntry("failover-report-publish-on", NOW.toString())
                .containsEntry("failover-metrics-as-on", NOW.toString())
                .containsEntry("failover-service-start-time", NOW.toString())
                .containsEntry("failover-lib-metadata-title", "failover-core")
                .containsEntry("failover-lib-metadata-version", "1.0.0")
                .containsEntry("failover-additional-info-key", "additional-info-value")
                .containsEntry("failover-name", "find-by-id")
                .containsEntry("failover-expiry-duration", "1")
                .containsEntry("failover-expiry-unit", "HOURS");
        assertThat(reportPublisher.getMetricsMap().get("failover-report-find-by-code").getInfo())
                .containsEntry("failover-report-publish-on", NOW.toString())
                .containsEntry("failover-metrics-as-on", NOW.toString())
                .containsEntry("failover-service-start-time", NOW.toString())
                .containsEntry("failover-lib-metadata-title", "failover-core")
                .containsEntry("failover-lib-metadata-version", "1.0.0")
                .containsEntry("failover-additional-info-key", "additional-info-value")
                .containsEntry("failover-name", "find-by-code")
                .containsEntry("failover-expiry-duration", "1")
                .containsEntry("failover-expiry-unit", "HOURS");
    }

    @Getter
    static class InMemoryReportPublisher extends AbstractReportPublisher {

        private final Map<String,Metrics> metricsMap;

        public InMemoryReportPublisher(FailoverClock clock) {
            super(clock);
            this.metricsMap = new ConcurrentHashMap<>();
        }

        @Override
        public void doPublish(Metrics metrics) {
            getMetricsMap().put(metrics.getName(), metrics);
        }
    }

    interface SomeReferential {
        @Failover(name = "find-by-id")
        String findById(Long id);

        @Failover(name = "find-by-code")
        String findByCode(Long id);
    }
}