package com.societegenerale.failover.core.exception;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.exception.policy.NeverRethrowMethodExceptionPolicy;
import com.societegenerale.failover.core.exception.policy.RethrowIfNoRecoveryMethodExceptionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class MethodExceptionHandlerTest {

    @Mock
    private Failover failover;

    @Mock
    private Method method;

    private final MethodExceptionHandler handler = new MethodExceptionHandler(new NeverRethrowMethodExceptionPolicy());

    private final Throwable cause = new RuntimeException("primary-failure");

    private final List<Object> args = List.of("arg1");

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(method.getName()).thenReturn("findReferential");
    }

    @Test
    @DisplayName("should return recovered result when recovery succeeded")
    void returnsRecoveredResultWhenRecoverySucceeded() {
        var context = new MethodExceptionContext<>(failover, method, args, "stale-data", cause);

        String result = handler.handle(context);

        assertThat(result).isEqualTo("stale-data");
    }

    @Test
    @DisplayName("should return null when no recovery result available")
    void returnsNullWhenNoRecoveryResult() {
        var context = new MethodExceptionContext<String>(failover, method, args, null, cause);

        String result = handler.handle(context);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("should never throw even when cause is a runtime exception")
    void neverThrowsForRuntimeException() {
        var context = new MethodExceptionContext<String>(failover, method, args, null,
                new RuntimeException("boom"));

        assertThatNoException().isThrownBy(() -> handler.handle(context));
    }

    @Test
    @DisplayName("should never throw even when cause is an error")
    void neverThrowsForError() {
        var context = new MethodExceptionContext<String>(failover, method, args, null,
                new AssertionError("fatal"));

        assertThatNoException().isThrownBy(() -> handler.handle(context));
    }

    @Test
    @DisplayName("should propagate exception when underlying policy rethrows")
    void propagatesExceptionWhenUnderlyingPolicyRethrows() {
        var rethrowHandler = new MethodExceptionHandler(new RethrowIfNoRecoveryMethodExceptionPolicy());
        RuntimeException originalCause = new RuntimeException("primary-failure");
        var context = new MethodExceptionContext<String>(failover, method, args, null, originalCause);

        assertThatThrownBy(() -> rethrowHandler.handle(context))
                .isSameAs(originalCause);
    }
}
