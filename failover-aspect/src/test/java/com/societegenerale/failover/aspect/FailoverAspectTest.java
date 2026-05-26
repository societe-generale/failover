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

package com.societegenerale.failover.aspect;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.domain.Referential;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverAspectTest {

    private final LocalDateTime now = LocalDateTime.now();

    private final Client client = new Client(1L, "Client-1");

    // Use a real Method — java.lang.reflect.Method cannot be mocked on Java 25
    // (JDK core classes in restricted modules cannot be instrumented by Mockito).
    private static final Method TEST_METHOD;
    static {
        try {
            TEST_METHOD = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    private FailoverAspect<Client> failoverAspect;

    @BeforeEach
    void setUp() {
        failoverAspect = new FailoverAspect<>(new DummyFailoverExecution());
        given(joinPoint.getSignature()).willReturn(signature);
        given(signature.getMethod()).willReturn(TEST_METHOD);
    }

    @Test
    @DisplayName("should skip failover when failover annotation is null")
    void shouldSkipFailoverWhenFailoverAnnotationIsNull() throws Throwable {
        given(joinPoint.proceed()).willReturn(client);
        Client result = failoverAspect.failoverAroundAdvice(joinPoint, null);
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isNull();
        assertThat(result.getAsOf()).isNull();
    }

    @Test
    @DisplayName("should skip failover when failover annotation name is null")
    void shouldSkipFailoverWhenFailoverAnnotationNameIsNull() throws Throwable {
        given(joinPoint.proceed()).willReturn(client);
        Client result = failoverAspect.failoverAroundAdvice(joinPoint, failoverWithName(null));
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isNull();
        assertThat(result.getAsOf()).isNull();
    }

    @Test
    @DisplayName("should skip failover when failover annotation name is empty")
    void shouldSkipFailoverWhenFailoverAnnotationNameIsEmpty() throws Throwable {
        given(joinPoint.proceed()).willReturn(client);
        Client result = failoverAspect.failoverAroundAdvice(joinPoint, failoverWithName(""));
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isNull();
        assertThat(result.getAsOf()).isNull();
    }

    @Test
    @DisplayName("should execute failover when valid failover annotation found")
    void shouldExecuteFailoverWhenValidFailoverAnnotationFound() throws Throwable {
        given(joinPoint.proceed()).willReturn(client);
        given(joinPoint.getArgs()).willReturn(new Long[]{1L,2L,3L});
        Client result = failoverAspect.failoverAroundAdvice(joinPoint, failoverWithName("FAILOVER"));
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isFalse();
        assertThat(result.getAsOf()).isEqualTo(now);
    }

    @Test
    @DisplayName("should throw exception on failover when valid failover annotation found and execution failed")
    void shouldThrowExceptionOnFailoverWhenValidFailoverAnnotationFoundAndExecutionFailed() throws Throwable {
        given(joinPoint.proceed()).willThrow(new RuntimeException("Dummy Exception"));
        given(joinPoint.getArgs()).willReturn(new Long[]{1L,2L,3L});
        ExecutionException exception = assertThrows(ExecutionException.class, () -> failoverAspect.failoverAroundAdvice(joinPoint, failoverWithName("FAILOVER")));
        assertThat(exception)
                .isInstanceOf(ExecutionException.class)
                .hasMessage("Exception occurred while executing method 'toString' execution failed due to 'Dummy Exception'")
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Dummy Exception");

    }

    @Test
    @DisplayName("should throw exception when failover annotation not found and execution failed")
    void shouldThrowExceptionWhenFailoverAnnotationNotFoundAndExecutionFailed() throws Throwable {
        given(joinPoint.proceed()).willThrow(new RuntimeException("Dummy Exception"));
        var exception = assertThrows(RuntimeException.class, () -> failoverAspect.failoverAroundAdvice(joinPoint, failoverWithName("")));
        assertThat(exception)
                .isInstanceOf(ExecutionException.class)
                .hasMessage("Exception occurred while executing method 'toString' execution failed due to 'Dummy Exception'")
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Dummy Exception");
    }

    private static Failover failoverWithName(String name) {
        return new Failover() {
            @Override public Class<? extends Annotation> annotationType() { return Failover.class; }
            @Override public String name() { return name; }
            @Override public long expiryDuration() { return 1; }
            @Override public String expiryDurationExpression() { return ""; }
            @Override public ChronoUnit expiryUnit() { return ChronoUnit.HOURS; }
            @Override public String expiryUnitExpression() { return ""; }
            @Override public String keyGenerator() { return ""; }
            @Override public String expiryPolicy() { return ""; }
        };
    }

    class DummyFailoverExecution implements FailoverExecution<Client> {
        @Override
        public Client execute(Failover failover, Supplier<Client> supplier, Method method, List<Object> args) {
            Client client = supplier.get();
            client.setUpToDate(false);
            client.setAsOf(now);
            return client;
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    static class Client extends Referential {
        private Long id;

        private String name;
    }
}
