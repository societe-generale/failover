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

package com.societegenerale.failover.core.observable;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.BasicFailoverExpiryExtractor;
import com.societegenerale.failover.core.observable.manifest.ManifestInfoExtractor;
import com.societegenerale.failover.core.observable.publisher.AbstractObservablePublisher;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import lombok.Getter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class DefaultFailoverObserverTest {

    private static final Map<String,String> ADDITIONAL_INFO = Map.of("additional-info-key", "additional-info-value");

    private static final Map<String,String> METADATA_INFO = Map.of("lib-metadata-title", "failover-core", "lib-metadata-version", "1.0.0");

    private static final Instant NOW = Instant.now();

    private InMemoryObservablePublisher observablePublisher;

    private DefaultFailoverObserver defaultFailoverObserver;

    @Mock
    private ManifestInfoExtractor manifestInfoExtractor;

    @Mock
    private FailoverClock clock;

    @BeforeEach
    void setUp() {
        lenient().when(clock.now()).thenReturn(NOW);
        lenient().when(manifestInfoExtractor.extract(any())).thenReturn(METADATA_INFO);
        observablePublisher = new DefaultFailoverObserverTest.InMemoryObservablePublisher();

        Failover findById = mock(Failover.class);
        when(findById.name()).thenReturn("find-by-id");
        when(findById.expiryDuration()).thenReturn(1L);
        when(findById.expiryUnit()).thenReturn(java.time.temporal.ChronoUnit.HOURS);
        when(findById.expiryDurationExpression()).thenReturn("");
        when(findById.expiryUnitExpression()).thenReturn("");

        Failover findByCode = mock(Failover.class);
        when(findByCode.name()).thenReturn("find-by-code");
        when(findByCode.expiryDuration()).thenReturn(1L);
        when(findByCode.expiryUnit()).thenReturn(java.time.temporal.ChronoUnit.HOURS);
        when(findByCode.expiryDurationExpression()).thenReturn("");
        when(findByCode.expiryUnitExpression()).thenReturn("");

        FailoverScanner failoverScanner = mock(FailoverScanner.class);
        when(failoverScanner.findAllFailover()).thenReturn(List.of(findById, findByCode));

        defaultFailoverObserver = new DefaultFailoverObserver(observablePublisher, failoverScanner, clock, manifestInfoExtractor, new BasicFailoverExpiryExtractor(), ADDITIONAL_INFO);
    }

    @Test
    @DisplayName("should publish report")
    void shouldPublishReport() {
        defaultFailoverObserver.observe();
        assertThat(observablePublisher.getMetricsMap().get("failover-report-find-by-id").getInfo())
                .containsEntry("failover-metrics-as-on", NOW.toString())
                .containsEntry("failover-service-start-time", NOW.toString())
                .containsEntry("failover-lib-metadata-title", "failover-core")
                .containsEntry("failover-lib-metadata-version", "1.0.0")
                .containsEntry("failover-additional-info-key", "additional-info-value")
                .containsEntry("failover-name", "find-by-id")
                .containsEntry("failover-expiry-duration", "1")
                .containsEntry("failover-expiry-unit", "HOURS");
        assertThat(observablePublisher.getMetricsMap().get("failover-report-find-by-code").getInfo())
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
    static class InMemoryObservablePublisher extends AbstractObservablePublisher {

        private final Map<String, Metrics> metricsMap = new ConcurrentHashMap<>();

        @Override
        public void doPublish(Metrics metrics) {
            getMetricsMap().put(metrics.getName(), metrics);
        }
    }

}