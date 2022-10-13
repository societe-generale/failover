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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class BasicFailoverExecutionTest {

    private static final String PAYLOAD = "Payload";

    private static final List<Object> ARGS = singletonList(1L);

    @Mock
    private Failover failover;

    @Mock
    private Supplier<String> supplier;

    @Mock
    private FailoverHandler<String> failoverHandler;

    private Method method;

    private BasicFailoverExecution<String> basicFailoverExecution;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        method = ReferentialMethod.class.getMethod("findReferential");
        basicFailoverExecution = new BasicFailoverExecution<>(failoverHandler);
    }

    @DisplayName("should return and store the actual result when execution is successful")
    @Test
    void shouldReturnAndStoreTheActualResultWhenExecutionIsSuccessful() {
        given(supplier.get()).willReturn(PAYLOAD);

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isEqualTo(PAYLOAD);
        verify(failoverHandler).store(failover, ARGS, PAYLOAD);
    }

    @DisplayName("should return the actual result when store execution is failed")
    @Test
    void shouldReturnTheActualResultWhenStoreExecutionIsFailed() {
        given(supplier.get()).willReturn(PAYLOAD);
        given(failoverHandler.store(failover, ARGS, PAYLOAD)).willThrow(new RuntimeException("SomeException"));

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isEqualTo(PAYLOAD);
        verify(failoverHandler).store(failover, ARGS, PAYLOAD);
    }

    @DisplayName("should recover the result from failover when execution occurred")
    @Test
    void shouldRecoverTheResultFromFailoverWhenAnExceptionOccurred() {
        Throwable throwable = new RuntimeException("Some Exception");
        given(failoverHandler.recover(failover, ARGS, String.class, throwable)).willReturn(PAYLOAD);
        given(supplier.get()).willThrow(throwable);

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isEqualTo(PAYLOAD);
        verify(failoverHandler).recover(failover, ARGS, String.class, throwable);
        verify(failoverHandler, never()).store(failover, ARGS, PAYLOAD);
    }

    @DisplayName("should return null when failover recover has any execution")
    @Test
    void shouldReturnNullWhenFailoverRecoverHasAnyException() {
        Throwable throwable = new RuntimeException("Some Exception");
        given(supplier.get()).willThrow(throwable);
        given(failoverHandler.recover(failover, ARGS, String.class, throwable)).willThrow(throwable);

        String result = basicFailoverExecution.execute(failover, supplier, method, ARGS);

        assertThat(result).isNull();
        verify(failoverHandler).recover(failover, ARGS, String.class, throwable);
        verify(failoverHandler, never()).store(failover, ARGS, PAYLOAD);
    }

    interface ReferentialMethod {
        String findReferential();
    }
}