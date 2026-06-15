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
import com.societegenerale.failover.core.payload.splitter.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Unit tests for {@link SplitterInvoker} — splitter lookup and user-exception-wrapping invocation.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SplitterInvokerTest {

    private static final String FAILOVER_NAME = "third-parties-failover";
    private static final String SPLITTER_NAME = "thirdPartiesSplitter";
    private static final RuntimeException BOOM = new RuntimeException("simulated splitter failure");

    @Mock private Failover failover;
    @Mock private Throwable cause;
    @Mock private PayloadSplitterLookup<String, String> payloadSplitterLookup;
    @Mock private PayloadSplitter<String, String> splitter;

    private SplitterInvoker<String, String> invoker;

    @BeforeEach
    void setUp() {
        invoker = new SplitterInvoker<>(payloadSplitterLookup);
        given(failover.name()).willReturn(FAILOVER_NAME);
        given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
    }

    private StoreContext<String> storeCtx() {
        return StoreContext.<String>builder().failover(failover).args(List.of("k")).payload("v").build();
    }

    private RecoverContext<String> recoverCtx() {
        return RecoverContext.<String>builder().failover(failover).args(List.of("k")).clazz(String.class).cause(cause).build();
    }

    @Test
    @DisplayName("lookup returns the resolved splitter when the lookup finds a bean")
    void lookupReturnsResolvedSplitter() {
        given(payloadSplitterLookup.lookup(SPLITTER_NAME)).willReturn(splitter);

        assertThat(invoker.lookup(failover)).isSameAs(splitter);
    }

    @Test
    @DisplayName("lookup throws PayloadSplitterNotFoundException carrying failover and splitter names when no bean is found")
    void lookupThrowsWhenBeanNotFound() {
        given(payloadSplitterLookup.lookup(SPLITTER_NAME)).willReturn(null);

        assertThatThrownBy(() -> invoker.lookup(failover))
                .isInstanceOf(PayloadSplitterNotFoundException.class)
                .hasMessageContaining(FAILOVER_NAME)
                .hasMessageContaining(SPLITTER_NAME);
    }

    @Test
    @DisplayName("splitOnStore delegates to the splitter and returns its slices")
    void splitOnStoreDelegates() {
        List<StoreContext<String>> slices = List.of(storeCtx());
        given(splitter.splitOnStore(any())).willReturn(slices);

        assertThat(invoker.splitOnStore(splitter, failover, storeCtx())).isSameAs(slices);
    }

    @Test
    @DisplayName("splitOnStore wraps a user exception as PayloadSplitterExecutionException with operation context")
    void splitOnStoreWrapsException() {
        given(splitter.splitOnStore(any())).willThrow(BOOM);

        assertThatThrownBy(() -> invoker.splitOnStore(splitter, failover, storeCtx()))
                .isInstanceOf(PayloadSplitterExecutionException.class)
                .hasMessageContaining(FAILOVER_NAME)
                .hasMessageContaining(SPLITTER_NAME)
                .hasMessageContaining("splitOnStore")
                .hasCause(BOOM);
    }

    @Test
    @DisplayName("splitOnRecover delegates to the splitter and returns its slices")
    void splitOnRecoverDelegates() {
        List<RecoverContext<String>> slices = List.of(recoverCtx());
        given(splitter.splitOnRecover(any())).willReturn(slices);

        assertThat(invoker.splitOnRecover(splitter, failover, recoverCtx())).isSameAs(slices);
    }

    @Test
    @DisplayName("splitOnRecover wraps a user exception as PayloadSplitterExecutionException with operation context")
    void splitOnRecoverWrapsException() {
        given(splitter.splitOnRecover(any())).willThrow(BOOM);

        assertThatThrownBy(() -> invoker.splitOnRecover(splitter, failover, recoverCtx()))
                .isInstanceOf(PayloadSplitterExecutionException.class)
                .hasMessageContaining(FAILOVER_NAME)
                .hasMessageContaining(SPLITTER_NAME)
                .hasMessageContaining("splitOnRecover")
                .hasCause(BOOM);
    }

    @Test
    @DisplayName("merge delegates to the splitter and returns the merged context")
    void mergeDelegates() {
        RecoverContext<String> merged = recoverCtx();
        given(splitter.merge(any())).willReturn(merged);

        assertThat(invoker.merge(splitter, failover, List.of(recoverCtx()))).isSameAs(merged);
    }

    @Test
    @DisplayName("merge wraps a user exception as PayloadSplitterExecutionException with operation context")
    void mergeWrapsException() {
        given(splitter.merge(any())).willThrow(BOOM);

        assertThatThrownBy(() -> invoker.merge(splitter, failover, List.of(recoverCtx())))
                .isInstanceOf(PayloadSplitterExecutionException.class)
                .hasMessageContaining(FAILOVER_NAME)
                .hasMessageContaining(SPLITTER_NAME)
                .hasMessageContaining("merge")
                .hasCause(BOOM);
    }
}
