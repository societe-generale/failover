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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class RethrowIfNoRecoveryMethodExceptionPolicyTest {

    @Mock
    private Failover failover;

    @Mock
    private Method method;

    private final RethrowIfNoRecoveryMethodExceptionPolicy policy = new RethrowIfNoRecoveryMethodExceptionPolicy();

    private final List<Object> args = List.of("arg1");

    @Test
    @DisplayName("should return recovered result when recovery succeeded")
    void returnsRecoveredResultWhenRecoverySucceeded() {
        RuntimeException cause = new RuntimeException("primary-failure");
        var context = new MethodExceptionContext<>(failover, method, args, "stale-data", cause);

        String result = policy.handle(context);

        assertThat(result).isEqualTo("stale-data");
    }

    @Test
    @DisplayName("should rethrow original exception when recovered result is null")
    void rethrowsWhenRecoveredResultIsNull() {
        RuntimeException cause = new RuntimeException("primary-failure");
        var context = new MethodExceptionContext<String>(failover, method, args, null, cause);

        assertThatThrownBy(() -> policy.handle(context))
                .isSameAs(cause);
    }

    @Test
    @DisplayName("should rethrow checked exception when no recovery result")
    void rethrowsCheckedExceptionWhenNoRecovery() {
        IOException cause = new IOException("io-failure");
        var context = new MethodExceptionContext<String>(failover, method, args, null, cause);

        assertThatThrownBy(() -> policy.handle(context))
                .isSameAs(cause)
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("should not rethrow when recovered result is non-null even if cause is present")
    void doesNotRethrowWhenRecoveryIsPresent() {
        RuntimeException cause = new RuntimeException("primary-failure");
        var context = new MethodExceptionContext<>(failover, method, args, "recovered", cause);

        String result = policy.handle(context);

        assertThat(result).isEqualTo("recovered");
    }

    @Test
    @DisplayName("should rethrow preserving original exception message when no recovery")
    void rethrowsPreservingOriginalMessage() {
        RuntimeException cause = new RuntimeException("exact-message");
        var context = new MethodExceptionContext<String>(failover, method, args, null, cause);

        assertThatThrownBy(() -> policy.handle(context))
                .hasMessage("exact-message");
    }
}
