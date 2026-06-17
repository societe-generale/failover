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

import java.lang.reflect.Method;
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

    private static final Method METHOD;
    static {
        try {
            METHOD = List.class.getMethod("size");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

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
        lenient().when(failover.domain()).thenReturn("");
        lenient().when(failover.expiryDuration()).thenReturn(1L);
        lenient().when(failover.expiryUnit()).thenReturn(MINUTES);
    }

    @Test
    @DisplayName("should store along with reporting")
    void shouldStoreAlongWithReporting() {
        given(failoverHandler.store(failover, METHOD, ARGS, PAYLOAD)).willReturn(PAYLOAD);
        advancedFailoverHandler.store(failover, METHOD, ARGS, PAYLOAD);
        verify(failoverHandler).store(failover, METHOD, ARGS, PAYLOAD);
        assertThat(observablePublisher.getMetrics().getInfo()).containsEntry("failover-action", "store")
                .containsEntry("failover-expiry-duration","1").containsEntry("failover-expiry-unit","MINUTES")
                .containsEntry("failover-is-stored", "true");
    }

    @Test
    @DisplayName("should publish metrics with is-stored=false when store delegate throws exception")
    void shouldPublishMetricsEvenWhenStoreDelegateThrowsException() {
        given(failoverHandler.store(failover, METHOD, ARGS, PAYLOAD)).willThrow(new RuntimeException("Store failure"));
        assertThatThrownBy(() -> advancedFailoverHandler.store(failover, METHOD, ARGS, PAYLOAD))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Store failure");
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "store")
                .containsEntry("failover-is-stored", "false");
    }

    @Test
    @DisplayName("should recover along with reporting and recovered payload resolver")
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandler() {
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, METHOD, ARGS, String.class, cause);
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
    @DisplayName("final cause is the innermost cause; cause is the first-level cause")
    void shouldReportInnermostFinalCause() {
        cause = new RuntimeException("L1",
                new IllegalStateException("L2", new java.net.SocketTimeoutException("L3")));
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-exception-type", "java.lang.RuntimeException")
                .containsEntry("failover-exception-cause-type", "java.lang.IllegalStateException")
                .containsEntry("failover-exception-final-cause-type", "java.net.SocketTimeoutException")
                .containsEntry("failover-exception-final-cause-message", "L3");
    }


    @Test
    @DisplayName("should recover along with reporting and recovered payload resolver and null result")
    void shouldRecoverAlongWithReportingAndRecoveredPayloadHandlerAndNullResult() {
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(null);
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, METHOD, ARGS, String.class, cause);
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
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, METHOD, ARGS, String.class, cause);
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
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willThrow(new RuntimeException("Exception on recover"));
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);
        verify(failoverHandler).recover(failover, METHOD, ARGS, String.class, cause);
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
    @DisplayName("should not propagate a RecoveredPayloadHandler failure — returns the raw recovered payload (audit I-06)")
    void shouldReturnRawPayloadWhenRecoveredPayloadHandlerThrows() {
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);
        given(recoveredPayloadHandler.handle(failover, ARGS, String.class, PAYLOAD, cause))
                .willThrow(new RuntimeException("Handler blew up"));

        var result = advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(result).isEqualTo(PAYLOAD);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, PAYLOAD, cause);
    }

    @Test
    @DisplayName("should capture recovery failure message in metrics when recover delegate throws exception")
    void shouldCaptureRecoveryFailureMessageInMetricsWhenRecoverDelegateThrowsException() {
        cause = new RuntimeException("Original-Exception");
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willThrow(new RuntimeException("Recovery failed - store unavailable"));
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);
        verify(recoveredPayloadHandler).handle(failover, ARGS, String.class, null, cause);
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-is-recovered", "false")
                .containsEntry("failover-is-recovery-failed", "true")
                .containsEntry("failover-recovery-failure-message", "Recovery failed - store unavailable");
    }

    @Test
    @DisplayName("should return the stored payload from the delegate")
    void shouldReturnStoredPayloadFromDelegate() {
        given(failoverHandler.store(failover, METHOD, ARGS, PAYLOAD)).willReturn(PAYLOAD);

        String result = advancedFailoverHandler.store(failover, METHOD, ARGS, PAYLOAD);

        assertThat(result).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("should use empty string in metrics when cause message is null")
    void shouldUseEmptyStringInMetricsWhenCauseMessageIsNull() {
        cause = new RuntimeException();   // getMessage() == null
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-exception-message", "")
                .containsEntry("failover-exception-cause-message", "");
    }

    @Test
    @DisplayName("should include duration-ns in store metrics")
    void shouldIncludeDurationNsInStoreMetrics() {
        given(failoverHandler.store(failover, METHOD, ARGS, PAYLOAD)).willReturn(PAYLOAD);
        advancedFailoverHandler.store(failover, METHOD, ARGS, PAYLOAD);

        String durationNs = observablePublisher.getMetrics().getInfo().get("failover-duration-ns");
        assertThat(durationNs).isNotNull();
        assertThat(Long.parseLong(durationNs)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("should include duration-ns in recover metrics")
    void shouldIncludeDurationNsInRecoverMetrics() {
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

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
        given(failoverHandler.store(failover, METHOD, ARGS, PAYLOAD)).willReturn(PAYLOAD);

        advancedFailoverHandler.store(failover, METHOD, ARGS, PAYLOAD);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-expiry-duration", "24")
                .containsEntry("failover-expiry-unit", "HOURS");
    }

    @Test
    @DisplayName("should publish expression-based expiry-duration and expiry-unit in recover metrics")
    void shouldPublishExpressionBasedExpiryInRecoverMetrics() {
        given(failover.expiryDurationExpression()).willReturn("48");
        given(failover.expiryUnitExpression()).willReturn("HOURS");
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-expiry-duration", "48")
                .containsEntry("failover-expiry-unit", "HOURS");
    }

    @Test
    @DisplayName("should publish is-stored=false when store delegate returns null (no exception)")
    void shouldPublishIsStoredFalseWhenDelegateReturnsNull() {
        given(failoverHandler.store(failover, METHOD, ARGS, PAYLOAD)).willReturn(null);

        String result = advancedFailoverHandler.store(failover, METHOD, ARGS, PAYLOAD);

        assertThat(result).isNull();
        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-action", "store")
                .containsEntry("failover-is-stored", "false");
    }

    @Test
    @DisplayName("should return value from recoveredPayloadHandler on recover")
    void shouldReturnValueFromRecoveredPayloadHandlerOnRecover() {
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);
        given(recoveredPayloadHandler.handle(failover, ARGS, String.class, PAYLOAD, cause)).willReturn("ENRICHED");

        String result = advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(result).isEqualTo("ENRICHED");
    }

    @Test
    @DisplayName("should publish is-recovery-failed=false and empty recovery-failure-message in recover happy path")
    void shouldPublishIsRecoveryFailedFalseInRecoverHappyPath() {
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-is-recovery-failed", "false")
                .containsEntry("failover-recovery-failure-message", "");
    }

    @Test
    @DisplayName("should use empty string for exception-cause-message when cause has root cause with null message")
    void shouldUseEmptyStringForCauseMessageWhenRootCauseHasNullMessage() {
        cause = new RuntimeException("Dummy-Exception", new IllegalArgumentException());  // getCause().getMessage() == null
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-exception-cause-type", "java.lang.IllegalArgumentException")
                .containsEntry("failover-exception-cause-message", "");
    }

    @Test
    @DisplayName("store reports a bounded, non-negative duration (nanoTime subtraction, not addition)")
    void storeReportsBoundedDuration() {
        given(failoverHandler.store(failover, METHOD, ARGS, PAYLOAD)).willReturn(PAYLOAD);

        long before = System.nanoTime();
        advancedFailoverHandler.store(failover, METHOD, ARGS, PAYLOAD);

        long duration = Long.parseLong(observablePublisher.getMetrics().getInfo().get("failover-duration-ns"));
        // Real elapsed during the call is tiny; an addition mutant yields ~2x an absolute nanoTime,
        // which is far larger than the nanoTime captured just before the call.
        assertThat(duration).isGreaterThanOrEqualTo(0).isLessThan(before);
    }

    @Test
    @DisplayName("recover reports a bounded, non-negative duration (nanoTime subtraction, not addition)")
    void recoverReportsBoundedDuration() {
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);

        long before = System.nanoTime();
        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        long duration = Long.parseLong(observablePublisher.getMetrics().getInfo().get("failover-duration-ns"));
        assertThat(duration).isGreaterThanOrEqualTo(0).isLessThan(before);
    }

    // ── method-aware overloads: method + domain dimensions ──────────────────────

    @Test
    @DisplayName("store(method) publishes failover-method (SimpleClass#method) and failover-domain")
    void storeMethodAwarePublishesMethodAndDomain() throws NoSuchMethodException {
        Method method = SampleService.class.getMethod("findAll");
        given(failover.domain()).willReturn("country");
        given(failoverHandler.store(failover, method, ARGS, PAYLOAD)).willReturn(PAYLOAD);

        advancedFailoverHandler.store(failover, method, ARGS, PAYLOAD);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-method", "SampleService#findAll")
                .containsEntry("failover-domain", "country");
    }

    @Test
    @DisplayName("recover(method) publishes failover-method and failover-domain")
    void recoverMethodAwarePublishesMethodAndDomain() throws NoSuchMethodException {
        Method method = SampleService.class.getMethod("findAll");
        given(failover.domain()).willReturn("country");
        given(failoverHandler.recover(failover, method, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, method, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-method", "SampleService#findAll")
                .containsEntry("failover-domain", "country");
    }

    @Test
    @DisplayName("blank domain falls back to the failover name; method tag is SimpleClass#method")
    void domainFallsBackToNameWhenBlank() {
        given(failover.domain()).willReturn("");
        given(failoverHandler.recover(failover, METHOD, ARGS, String.class, cause)).willReturn(PAYLOAD);

        advancedFailoverHandler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-domain", FAILOVER_NAME)
                .containsEntry("failover-method", "List#size");
    }

    // ── is-recovered reflects emptiness of the recovered payload (CommonsUtil.isNotNullOrEmpty) ──────

    @Test
    @DisplayName("empty collection recovered ⇒ is-recovered=false")
    void emptyCollectionRecoveredIsNotRecovered() {
        assertIsRecoveredFor(List.of(), "false");
    }

    @Test
    @DisplayName("collection of only nulls recovered ⇒ is-recovered=false")
    void allNullCollectionRecoveredIsNotRecovered() {
        assertIsRecoveredFor(java.util.Arrays.asList(null, null), "false");
    }

    @Test
    @DisplayName("empty array recovered ⇒ is-recovered=false")
    void emptyArrayRecoveredIsNotRecovered() {
        assertIsRecoveredFor(new Object[0], "false");
    }

    @Test
    @DisplayName("empty map recovered ⇒ is-recovered=false")
    void emptyMapRecoveredIsNotRecovered() {
        assertIsRecoveredFor(java.util.Map.of(), "false");
    }

    @Test
    @DisplayName("non-empty collection recovered ⇒ is-recovered=true")
    void nonEmptyCollectionRecoveredIsRecovered() {
        assertIsRecoveredFor(List.of("data"), "true");
    }

    /**
     * Recovers a payload of an arbitrary container type and asserts the published {@code is-recovered}
     * flag. Uses an {@code Object}-typed handler because the shared mocks are {@code String}-typed.
     */
    @SuppressWarnings("unchecked")
    private void assertIsRecoveredFor(Object recovered, String expectedIsRecovered) {
        FailoverHandler<Object> objectHandler = org.mockito.Mockito.mock(FailoverHandler.class);
        AdvancedFailoverHandler<Object> handler = new AdvancedFailoverHandler<>(
                objectHandler, recoveredPayloadHandler, observablePublisher, new BasicFailoverExpiryExtractor());
        given(objectHandler.recover(failover, METHOD, ARGS, Object.class, cause)).willReturn(recovered);

        handler.recover(failover, METHOD, ARGS, Object.class, cause);

        assertThat(observablePublisher.getMetrics().getInfo())
                .containsEntry("failover-is-recovered", expectedIsRecovered);
    }

    interface SampleService {
        List<String> findAll();
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