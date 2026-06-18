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

package com.societegenerale.failover.core.payload.splitter;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.ScatterGatherFailoverHandler;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PayloadScatter} — the store/scatter side of {@link ScatterGatherFailoverHandler}.
 * Built over a real {@link SplitterInvoker} and a sequential {@link SliceDispatcher} with a mocked
 * splitter and slice delegate.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayloadScatterTest {

    private static final String SPLITTER_NAME = "scatterSplitter";
    private static final List<Object> COMPOSITE_ARGS = List.of("active", "1,2", "India");

    private static final Method METHOD;
    static {
        try {
            METHOD = List.class.getMethod("size");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Mock private Failover failover;
    @Mock private FailoverHandler<String> delegateR;
    @Mock private PayloadSplitterLookup<String, String> payloadSplitterLookup;
    @Mock private PayloadSplitter<String, String> splitter;

    private PayloadScatter<String, String> scatter;

    @BeforeEach
    void setUp() {
        given(failover.name()).willReturn("scatter-failover");
        given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
        given(payloadSplitterLookup.lookup(SPLITTER_NAME)).willReturn(splitter);

        var invoker = new SplitterInvoker<>(payloadSplitterLookup);
        var dispatcher = new SliceDispatcher<String>(null, ContextPropagator.noOp(), null);
        scatter = new PayloadScatter<>(delegateR, invoker, dispatcher);
    }

    private StoreContext<String> sliceCtx(String key, String payload) {
        return StoreContext.<String>builder().failover(failover).args(List.of(key)).payload(payload).build();
    }

    @Test
    @DisplayName("splits the composite and stores each slice via the slice delegate, returning the original payload")
    void storesEachSliceAndReturnsPayload() {
        given(splitter.splitOnStore(any())).willReturn(List.of(sliceCtx("1", "A"), sliceCtx("2", "B")));

        String returned = scatter.store(failover, METHOD, COMPOSITE_ARGS, "A,B");

        assertThat(returned).isEqualTo("A,B");
        verify(delegateR).store(failover, METHOD, List.of("1"), "A");
        verify(delegateR).store(failover, METHOD, List.of("2"), "B");
    }

    @Test
    @DisplayName("no slices — returns the payload and never touches the slice delegate")
    void noSlicesReturnsPayload() {
        given(splitter.splitOnStore(any())).willReturn(List.of());

        String returned = scatter.store(failover, METHOD, COMPOSITE_ARGS, "A,B");

        assertThat(returned).isEqualTo("A,B");
        verify(delegateR, never()).store(any(), any(), any(), any());
    }

    @Test
    @DisplayName("missing splitter bean — throws PayloadSplitterNotFoundException before any store")
    void missingSplitterThrows() {
        given(payloadSplitterLookup.lookup(SPLITTER_NAME)).willReturn(null);

        assertThatThrownBy(() -> scatter.store(failover, METHOD, COMPOSITE_ARGS, "A,B"))
                .isInstanceOf(PayloadSplitterNotFoundException.class);
        verify(delegateR, never()).store(any(), any(), any(), any());
    }
}
