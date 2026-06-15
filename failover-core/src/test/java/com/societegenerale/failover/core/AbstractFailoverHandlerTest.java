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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AbstractFailoverHandler} — verifies the {@code final} method-aware bridges
 * drop the {@code method} and delegate to the {@code protected} method-less operations, and that the
 * default {@code recoverAll} is unsupported.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class AbstractFailoverHandlerTest {

    private static final List<Object> ARGS = List.of(1L);

    private static final String PAYLOAD = "PAYLOAD";

    @Mock
    private Failover failover;

    @Mock
    private Throwable cause;

    private static final Method METHOD;

    static {
        try {
            METHOD = List.class.getMethod("size");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("store bridge drops the method and delegates to the protected method-less store")
    void storeBridgeDelegatesToMethodLess() {
        var handler = new CapturingHandler();

        String result = handler.store(failover, METHOD, ARGS, PAYLOAD);

        assertThat(result).isEqualTo("stored:PAYLOAD");
        assertThat(handler.storeArgs).isSameAs(ARGS);
        assertThat(handler.storePayload).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("recover bridge drops the method and delegates to the protected method-less recover")
    void recoverBridgeDelegatesToMethodLess() {
        var handler = new CapturingHandler();

        String result = handler.recover(failover, METHOD, ARGS, String.class, cause);

        assertThat(result).isEqualTo("recovered");
        assertThat(handler.recoverArgs).isSameAs(ARGS);
        assertThat(handler.recoverClazz).isEqualTo(String.class);
        assertThat(handler.recoverCause).isSameAs(cause);
    }

    @Test
    @DisplayName("recoverAll bridge drops the method and delegates to the overridden protected recoverAll")
    void recoverAllBridgeDelegatesToMethodLess() {
        var handler = new CapturingHandler();

        List<String> result = handler.recoverAll(failover, METHOD, ARGS, String.class, cause);

        assertThat(result).containsExactly("all-1", "all-2");
        assertThat(handler.recoverAllArgs).isSameAs(ARGS);
    }

    @Test
    @DisplayName("default protected recoverAll is unsupported when a subclass does not override it")
    void defaultRecoverAllIsUnsupported() {
        var handler = new MinimalHandler();

        assertThatThrownBy(() -> handler.recoverAll(failover, METHOD, ARGS, String.class, cause))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Not supported yet.");
    }

    /** Records the method-less calls so the bridges' delegation can be asserted. */
    private static final class CapturingHandler extends AbstractFailoverHandler<String> {
        private List<Object> storeArgs;
        private String storePayload;
        private List<Object> recoverArgs;
        private Class<String> recoverClazz;
        private Throwable recoverCause;
        private List<Object> recoverAllArgs;

        @Override
        protected String store(@NonNull Failover failover, List<Object> args, String payload) {
            this.storeArgs = args;
            this.storePayload = payload;
            return "stored:" + payload;
        }

        @Override
        protected String recover(@NonNull Failover failover, List<Object> args, Class<String> clazz, Throwable throwable) {
            this.recoverArgs = args;
            this.recoverClazz = clazz;
            this.recoverCause = throwable;
            return "recovered";
        }

        @Override
        protected List<String> recoverAll(@NonNull Failover failover, List<Object> args, Class<String> clazz, Throwable throwable) {
            this.recoverAllArgs = args;
            return List.of("all-1", "all-2");
        }

        @Override
        public void clean() {
            // no-op
        }
    }

    /** Implements only the required operations — leaves {@code recoverAll} as the default (throws). */
    private static final class MinimalHandler extends AbstractFailoverHandler<String> {
        @Override
        protected String store(@NonNull Failover failover, List<Object> args, String payload) {
            return payload;
        }

        @Override
        protected String recover(@NonNull Failover failover, List<Object> args, Class<String> clazz, Throwable throwable) {
            return null;
        }

        @Override
        public void clean() {
            // no-op
        }
    }
}
