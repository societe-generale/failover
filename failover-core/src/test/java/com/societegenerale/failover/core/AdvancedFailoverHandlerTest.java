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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.BasicFailoverExpiryExtractor;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.report.AbstractReportPublisher;
import com.societegenerale.failover.core.report.Metrics;
import lombok.Getter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class AdvancedFailoverHandlerTest {

    private static final String FAILOVER_NAME = "failover-name";

    private static final List<Object> ARGS = singletonList(1L);

    private static final String PAYLOAD = "PAYLOAD";

    private static final LocalDateTime NOW = LocalDateTime.now();

    @Mock
    private Failover failover;

    @Mock
    private FailoverClock clock;

    @Mock
    private FailoverHandler<String> failoverHandler;

    @Mock
    private RecoveredPayloadHandler recoveredPayloadHandler;

    private InMemoryReportPublisher reportPublisher;

    private AdvancedFailoverHandler<String> advancedFailoverHandler;

    private Throwable cause;

    @BeforeEach
    void setUp() {
        cause = new RuntimeException("Dummy-Exception", new IllegalArgumentException("Root-Cause"));
        reportPublisher = new InMemoryReportPublisher(clock);
        advancedFailoverHandler = new AdvancedFailoverHandler<>(failoverHandler, recoveredPayloadHandler, reportPublisher, new BasicFailoverExpiryExtractor());

        lenient().when(failover.name()).thenReturn(FAILOVER_NAME);
        lenient().when(failover.expiryDuration()).thenReturn(1L);
        lenient().when(failover.expiryUnit()).thenReturn(MINUTES);
        lenient().when(clock.now()).thenReturn(NOW);
    }

    @Test
    void shouldStoreAlongWithReporting() {
        advancedFailoverHandler.store(failover, ARGS, PAYLOAD);
        verify(failoverHandler).store(failover, ARGS, PAYLOAD);
        assertThat(reportPublisher.getMetrics().getInfo()).containsEntry("failover-action", "store")
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-report-publish-on", NOW.toString());
    }

    @Test
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandler() {
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, PAYLOAD);
        assertThat(reportPublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover").containsEntry("failover-report-publish-on", NOW.toString())
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-cause-type", "java.lang.IllegalArgumentException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-message", "Root-Cause")
                .containsEntry("failover-is-recovered", "true");
    }


    @Test
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandlerAndNullResult() {
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(null);
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, null);
        assertThat(reportPublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover").containsEntry("failover-report-publish-on", NOW.toString())
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-cause-type", "java.lang.IllegalArgumentException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-message", "Root-Cause")
                .containsEntry("failover-is-recovered", "false");
    }

    @Test
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandlerAndWithRootCause() {
        cause = new RuntimeException("Dummy-Exception");
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, PAYLOAD);
        assertThat(reportPublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover").containsEntry("failover-report-publish-on", NOW.toString())
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-type", "")
                .containsEntry("failover-exception-cause-message", "")
                .containsEntry("failover-is-recovered", "true");
    }

    @Test
    void shouldHandledTheExceptionAndHandleWithRecoveredPayloadHandlerWhenAnyExceptionOccurredOnRecover() {
        cause = new RuntimeException("Dummy-Exception");
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willThrow(new RuntimeException("Exception on recover"));
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, null);
        assertThat(reportPublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover").containsEntry("failover-report-publish-on", NOW.toString())
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-type", "")
                .containsEntry("failover-exception-cause-message", "")
                .containsEntry("failover-is-recovered", "false");
    }

    @Test
    void shouldExecuteClean() {
        advancedFailoverHandler.clean();
        verify(failoverHandler).clean();
    }

    class InMemoryReportPublisher extends AbstractReportPublisher {
        @Getter
        private Metrics metrics;

        public InMemoryReportPublisher(FailoverClock clock) {
            super(clock);
        }

        @Override
        public void doPublish(Metrics metrics) {
                this.metrics=metrics;
        }
    }
}