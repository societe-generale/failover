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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.expiry.BasicFailoverExpiryExtractor;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.observable.publisher.AbstractObservablePublisher;
import com.societegenerale.failover.core.observable.Metrics;
import lombok.Getter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class AdvancedFailoverHandlerTest {

    private static final String FAILOVER_NAME = "failover-name";

    private static final List<Object> ARGS = List.of(1L);

    private static final String PAYLOAD = "PAYLOAD";

    @Mock
    private Failover failover;

    @Mock
    private FailoverHandler<String> failoverHandler;

    @Mock
    private RecoveredPayloadHandler recoveredPayloadHandler;

    private InMemoryObservablePublisher observablePublisher;

    private AdvancedFailoverHandler<String> advancedFailoverHandler;

    private Throwable cause;

    @BeforeEach
    void setUp() {
        cause = new RuntimeException("Dummy-Exception", new IllegalArgumentException("Root-Cause"));
        observablePublisher = new InMemoryObservablePublisher();
        advancedFailoverHandler = new AdvancedFailoverHandler<>(failoverHandler, recoveredPayloadHandler, observablePublisher, new BasicFailoverExpiryExtractor());

        lenient().when(failover.name()).thenReturn(FAILOVER_NAME);
        lenient().when(failover.expiryDuration()).thenReturn(1L);
        lenient().when(failover.expiryUnit()).thenReturn(MINUTES);
    }

    @Test
    @DisplayName("should store along with reporting")
    void shouldStoreAlongWithReporting() {
        given(failoverHandler.store(failover, ARGS, PAYLOAD)).willReturn(PAYLOAD);
        advancedFailoverHandler.store(failover, ARGS, PAYLOAD);
        verify(failoverHandler).store(failover, ARGS, PAYLOAD);
        assertThat(observablePublisher.getMetrics().getInfo()).containsEntry("failover-action", "store")
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-is-stored", "true");
    }

    @Test
    @DisplayName("should publish metrics with is-stored=false when store delegate throws exception")
    void shouldPublishMetricsEvenWhenStoreDelegateThrowsException() {
        given(failoverHandler.store(failover, ARGS, PAYLOAD)).willThrow(new RuntimeException("Store failure"));
        assertThatThrownBy(() -> advancedFailoverHandler.store(failover, ARGS, PAYLOAD))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Store failure");
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "store")
                .containsEntry("failover-is-stored", "false");
    }

    @Test
    @DisplayName("should recover along with reporting and recovered payload resolver")
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandler() {
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, PAYLOAD, cause);
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover")
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-cause-type", "java.lang.IllegalArgumentException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-message", "Root-Cause")
                .containsEntry("failover-is-recovered", "true");
    }


    @Test
    @DisplayName("should recover along with reporting and recovered payload resolver and null result")
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandlerAndNullResult() {
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(null);
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, null, cause);
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover")
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-cause-type", "java.lang.IllegalArgumentException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-message", "Root-Cause")
                .containsEntry("failover-is-recovered", "false");
    }

    @Test
    @DisplayName("should recover along with reporting and recovered payload resolver and with root cause")
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandlerAndWithRootCause() {
        cause = new RuntimeException("Dummy-Exception");
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, PAYLOAD, cause);
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover")
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-type", "")
                .containsEntry("failover-exception-cause-message", "")
                .containsEntry("failover-is-recovered", "true");
    }

    @Test
    @DisplayName("should handled the exception and handle with recovered payload resolver when any exception occurred on recover")
    void shouldHandledTheExceptionAndHandleWithRecoveredPayloadHandlerWhenAnyExceptionOccurredOnRecover() {
        cause = new RuntimeException("Dummy-Exception");
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willThrow(new RuntimeException("Exception on recover"));
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, null, cause);
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "recover")
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-message", "Dummy-Exception")
                .containsEntry("failover-exception-cause-type", "")
                .containsEntry("failover-exception-cause-message", "")
                .containsEntry("failover-is-recovered", "false")
                .containsEntry("failover-is-recovery-failed", "true")
                .containsEntry("failover-recovery-failure-message", "Exception on recover");
    }

    @Test
    @DisplayName("should capture recovery failure message in metrics when recover delegate throws exception")
    void shouldCaptureRecoveryFailureMessageInMetricsWhenRecoverDelegateThrowsException() {
        cause = new RuntimeException("Original-Exception");
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willThrow(new RuntimeException("Recovery failed - store unavailable"));
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, null, cause);
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-is-recovered", "false")
                .containsEntry("failover-is-recovery-failed", "true")
                .containsEntry("failover-recovery-failure-message", "Recovery failed - store unavailable");
    }

    @Test
    @DisplayName("should return the stored payload from the delegate")
    void shouldReturnStoredPayloadFromDelegate() {
        given(failoverHandler.store(failover, ARGS, PAYLOAD)).willReturn(PAYLOAD);

        String result = advancedFailoverHandler.store(failover, ARGS, PAYLOAD);

        assertThat(result).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("should use empty string in metrics when cause message is null")
    void shouldUseEmptyStringInMetricsWhenCauseMessageIsNull() {
        cause = new RuntimeException();   // getMessage() == null
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-exception-message", "")
                .containsEntry("failover-exception-cause-message", "");
    }

    @Test
    @DisplayName("should include duration-ns in store metrics")
    void shouldIncludeDurationNsInStoreMetrics() {
        given(failoverHandler.store(failover, ARGS, PAYLOAD)).willReturn(PAYLOAD);
        advancedFailoverHandler.store(failover, ARGS, PAYLOAD);

        String durationNs = observablePublisher.getMetrics().getInfo().get("failover-duration-ns");
        assertThat(durationNs).isNotNull();
        assertThat(Long.parseLong(durationNs)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("should include duration-ns in recover metrics")
    void shouldIncludeDurationNsInRecoverMetrics() {
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);

        String durationNs = observablePublisher.getMetrics().getInfo().get("failover-duration-ns");
        assertThat(durationNs).isNotNull();
        assertThat(Long.parseLong(durationNs)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("should execute clean")
    void shouldExecuteClean() {
        advancedFailoverHandler.clean();
        verify(failoverHandler).clean();
    }

    @Test
    @DisplayName("should publish expression-based expiry-duration and expiry-unit in store metrics")
    void shouldPublishExpressionBasedExpiryInStoreMetrics() {
        given(failover.expiryDurationExpression()).willReturn("24");
        given(failover.expiryUnitExpression()).willReturn("HOURS");
        given(failoverHandler.store(failover, ARGS, PAYLOAD)).willReturn(PAYLOAD);

        advancedFailoverHandler.store(failover, ARGS, PAYLOAD);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-expiry-duration", "24")
                .containsEntry("failover-expiry-unit", "HOURS");
    }

    @Test
    @DisplayName("should publish expression-based expiry-duration and expiry-unit in recover metrics")
    void shouldPublishExpressionBasedExpiryInRecoverMetrics() {
        given(failover.expiryDurationExpression()).willReturn("48");
        given(failover.expiryUnitExpression()).willReturn("HOURS");
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-expiry-duration", "48")
                .containsEntry("failover-expiry-unit", "HOURS");
    }

    @Test
    @DisplayName("should publish is-stored=false when store delegate returns null (no exception)")
    void shouldPublishIsStoredFalseWhenDelegateReturnsNull() {
        given(failoverHandler.store(failover, ARGS, PAYLOAD)).willReturn(null);

        String result = advancedFailoverHandler.store(failover, ARGS, PAYLOAD);

        assertThat(result).isNull();
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "store")
                .containsEntry("failover-is-stored", "false");
    }

    @Test
    @DisplayName("should return value from recoveredPayloadHandler on recover")
    void shouldReturnValueFromRecoveredPayloadHandlerOnRecover() {
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);
        given(recoveredPayloadHandler.handle(failover, ARGS, String.class, PAYLOAD, cause)).willReturn("ENRICHED");

        String result = advancedFailoverHandler.recover(failover, ARGS, String.class, cause);

        assertThat(result).isEqualTo("ENRICHED");
    }

    @Test
    @DisplayName("should publish is-recovery-failed=false and empty recovery-failure-message in recover happy path")
    void shouldPublishIsRecoveryFailedFalseInRecoverHappyPath() {
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-is-recovery-failed", "false")
                .containsEntry("failover-recovery-failure-message", "");
    }

    @Test
    @DisplayName("should use empty string for exception-cause-message when cause has root cause with null message")
    void shouldUseEmptyStringForCauseMessageWhenRootCauseHasNullMessage() {
        cause = new RuntimeException("Dummy-Exception", new IllegalArgumentException());  // getCause().getMessage() == null
        given(failoverHandler.recover(failover, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-exception-cause-type", "java.lang.IllegalArgumentException")
                .containsEntry("failover-exception-cause-message", "");
    }

    static class InMemoryObservablePublisher extends AbstractObservablePublisher {
        @Getter
        private Metrics metrics;

        @Override
        public void doPublish(Metrics metrics) {
            this.metrics = metrics;
        }
    }
}