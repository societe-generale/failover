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
import com.societegenerale.failover.core.exception.MethodExceptionContext;
import com.societegenerale.failover.core.exception.MethodExceptionHandler;
import com.societegenerale.failover.core.observable.Metrics;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class BasicFailoverExecutionTest {

    private static final String PAYLOAD = "Payload";

    private static final List<Object> ARGS = List.of(1L);

    @Mock
    private Failover failover;

    @Mock
    private Supplier<String> supplier;

    @Mock
    private FailoverHandler<String> failoverHandler;

    @Mock
    private MethodExceptionHandler methodExceptionHandler;

    @Mock
    private ObservablePublisher observablePublisher;

    private Method method;

    private BasicFailoverExecution<String> basicFailoverExecution;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        method = ReferentialMethod.class.getMethod("findReferential");
        basicFailoverExecution = new BasicFailoverExecution<>(failoverHandler, methodExceptionHandler);
    }

    @DisplayName("should return and store the actual result when execution is successful")
    @Test
    void shouldReturnAndStoreTheActualResultWhenExecutionIsSuccessful() {
        given(supplier.get()).willReturn(PAYLOAD);

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isEqualTo(PAYLOAD);
        verify(failoverHandler).store(failover, method, ARGS, PAYLOAD);
    }

    @Test
    @DisplayName("should return the actual result when store execution is failed")
    void shouldReturnTheActualResultWhenStoreExecutionIsFailed() {
        given(supplier.get()).willReturn(PAYLOAD);
        given(failoverHandler.store(failover, method, ARGS, PAYLOAD)).willThrow(new RuntimeException("SomeException"));

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isEqualTo(PAYLOAD);
        verify(failoverHandler).store(failover, method, ARGS, PAYLOAD);
    }

    @Test
    @DisplayName("should recover the result from failover when an exception occurred")
    void shouldRecoverTheResultFromFailoverWhenAnExceptionOccurred() {
        Throwable throwable = new RuntimeException("Some Exception");
        given(supplier.get()).willThrow(throwable);
        given(failoverHandler.recover(failover, method, ARGS, String.class, throwable)).willReturn(PAYLOAD);
        given(methodExceptionHandler.handle(any())).willReturn(PAYLOAD);

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isEqualTo(PAYLOAD);
        verify(failoverHandler).recover(failover, method, ARGS, String.class, throwable);
        verify(failoverHandler, never()).store(failover, method, ARGS, PAYLOAD);
    }

    @Test
    @DisplayName("should delegate to method exception policy after recovery")
    void shouldDelegateToMethodExceptionPolicyAfterRecovery() {
        Throwable throwable = new RuntimeException("Some Exception");
        given(supplier.get()).willThrow(throwable);
        given(failoverHandler.recover(failover, method, ARGS, String.class, throwable)).willReturn(PAYLOAD);
        given(methodExceptionHandler.handle(any(MethodExceptionContext.class))).willReturn(PAYLOAD);

        basicFailoverExecution.execute(failover, supplier, method, ARGS);

        verify(methodExceptionHandler).handle(any(MethodExceptionContext.class));
    }

    @Test
    @DisplayName("should return null when failover recover has any exception and policy returns null")
    void shouldReturnNullWhenFailoverRecoverHasAnyException() {
        Throwable throwable = new RuntimeException("Some Exception");
        given(supplier.get()).willThrow(throwable);
        given(failoverHandler.recover(failover, method, ARGS, String.class, throwable)).willThrow(throwable);
        given(methodExceptionHandler.handle(any())).willReturn(null);

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isNull();
        verify(failoverHandler).recover(failover, method, ARGS, String.class, throwable);
        verify(failoverHandler, never()).store(failover, method, ARGS, PAYLOAD);
    }

    @Test
    @DisplayName("should propagate exception when handler rethrows")
    void shouldPropagateExceptionWhenHandlerRethrows() {
        Throwable throwable = new RuntimeException("Some Exception");
        RuntimeException rethrown = new RuntimeException("rethrown");
        given(supplier.get()).willThrow(throwable);
        given(failoverHandler.recover(failover, method, ARGS, String.class, throwable)).willReturn(null);
        given(methodExceptionHandler.handle(any())).willThrow(rethrown);

        assertThatThrownBy(() -> basicFailoverExecution.execute(failover, supplier, method, ARGS))
                .isSameAs(rethrown);
    }

    // ── upstream-duration metric ───────────────────────────────────────────────

    @Test
    @DisplayName("publishes failover.upstream.duration with result=success on a successful upstream call")
    void publishesUpstreamSuccessMetric() {
        given(failover.name()).willReturn("country");
        given(failover.domain()).willReturn("");
        given(supplier.get()).willReturn(PAYLOAD);
        BasicFailoverExecution<String> execution =
                new BasicFailoverExecution<>(failoverHandler, methodExceptionHandler, observablePublisher);

        execution.execute(failover, supplier, method, ARGS);

        Map<String, String> info = capturePublishedMetric();
        assertThat(info).containsEntry("failover-action", "upstream")
                .containsEntry("failover-upstream-result", "success");
        assertThat(info.get("failover-upstream-duration-ns")).isNotBlank();
    }

    @Test
    @DisplayName("publishes failover.upstream.duration with result=failure when the upstream call throws")
    void publishesUpstreamFailureMetric() {
        given(failover.name()).willReturn("country");
        given(failover.domain()).willReturn("");
        Throwable throwable = new RuntimeException("upstream down");
        given(supplier.get()).willThrow(throwable);
        given(failoverHandler.recover(failover, method, ARGS, String.class, throwable)).willReturn(PAYLOAD);
        given(methodExceptionHandler.handle(any())).willReturn(PAYLOAD);
        BasicFailoverExecution<String> execution =
                new BasicFailoverExecution<>(failoverHandler, methodExceptionHandler, observablePublisher);

        execution.execute(failover, supplier, method, ARGS);

        assertThat(capturePublishedMetric()).containsEntry("failover-upstream-result", "failure");
    }

    private Map<String, String> capturePublishedMetric() {
        ArgumentCaptor<Metrics> captor = ArgumentCaptor.forClass(Metrics.class);
        verify(observablePublisher).publish(captor.capture());
        return captor.getValue().getInfo();
    }

    interface ReferentialMethod {
        String findReferential();
    }
}