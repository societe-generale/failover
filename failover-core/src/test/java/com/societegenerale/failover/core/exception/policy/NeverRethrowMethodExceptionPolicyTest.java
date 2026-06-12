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

package com.societegenerale.failover.core.exception.policy;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.exception.MethodExceptionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class NeverRethrowMethodExceptionPolicyTest {

    @Mock
    private Failover failover;

    @Mock
    private Method method;

    private final NeverRethrowMethodExceptionPolicy policy = new NeverRethrowMethodExceptionPolicy();

    private final List<Object> args = List.of("arg1");

    @Test
    @DisplayName("should return recovered result when recovery succeeded")
    void returnsRecoveredResultWhenRecoverySucceeded() {
        var context = new MethodExceptionContext<>(failover, method, args, "stale-data", new RuntimeException("fail"));

        String result = policy.handle(context);

        assertThat(result).isEqualTo("stale-data");
    }

    @Test
    @DisplayName("should return null when recovered result is null")
    void returnsNullWhenRecoveredResultIsNull() {
        var context = new MethodExceptionContext<String>(failover, method, args, null, new RuntimeException("fail"));

        String result = policy.handle(context);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("should never throw for RuntimeException cause")
    void neverThrowsForRuntimeException() {
        var context = new MethodExceptionContext<String>(failover, method, args, null, new RuntimeException("boom"));

        assertThatNoException().isThrownBy(() -> policy.handle(context));
    }

    @Test
    @DisplayName("should never throw for checked exception cause")
    void neverThrowsForCheckedException() {
        var context = new MethodExceptionContext<String>(failover, method, args, null, new IOException("io-fail"));

        assertThatNoException().isThrownBy(() -> policy.handle(context));
    }

    @Test
    @DisplayName("should never throw for Error cause")
    void neverThrowsForError() {
        var context = new MethodExceptionContext<String>(failover, method, args, null, new AssertionError("fatal"));

        assertThatNoException().isThrownBy(() -> policy.handle(context));
    }
}
