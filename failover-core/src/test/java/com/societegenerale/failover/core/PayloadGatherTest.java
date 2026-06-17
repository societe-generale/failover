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
import com.societegenerale.failover.core.propagator.ContextPropagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Unit tests for {@link PayloadGather} — the recover/gather side of {@link ScatterGatherFailoverHandler}.
 * Built over a real {@link SplitterInvoker} and a sequential {@link SliceDispatcher} with a mocked
 * splitter and slice delegate.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayloadGatherTest {

    private static final String SPLITTER_NAME = "gatherSplitter";
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
    @Mock private Throwable cause;
    @Mock private FailoverHandler<String> delegateR;
    @Mock private PayloadSplitterLookup<String, String> payloadSplitterLookup;
    @Mock private PayloadSplitter<String, String> splitter;
    @Mock private com.societegenerale.failover.core.observable.publisher.ObservablePublisher observablePublisher;

    @org.mockito.Captor private org.mockito.ArgumentCaptor<com.societegenerale.failover.core.observable.Metrics> metricsCaptor;

    private PayloadGather<String, String> gather;

    @BeforeEach
    void setUp() {
        given(failover.name()).willReturn("gather-failover");
        given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
        given(failover.recoverAll()).willReturn(false);
        given(payloadSplitterLookup.lookup(SPLITTER_NAME)).willReturn(splitter);

        var invoker = new SplitterInvoker<>(payloadSplitterLookup);
        var dispatcher = new SliceDispatcher<String>(null, ContextPropagator.noOp(), null);
        gather = new PayloadGather<>(delegateR, invoker, dispatcher, observablePublisher);
    }

    private RecoverContext<String> sliceCtx(String key) {
        return RecoverContext.<String>builder().failover(failover).args(List.of(key)).clazz(String.class).cause(cause).build();
    }

    private RecoverContext<String> mergedCtx(String payload) {
        return RecoverContext.<String>builder().failover(failover).args(COMPOSITE_ARGS).clazz(String.class).cause(cause).payload(payload).build();
    }

    // ── per-key recover ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("per-key recover")
    class PerKeyRecover {

        @Test
        @DisplayName("recovers each slice via the slice delegate and merges into the composite payload")
        void recoversEachSliceAndMerges() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("1"), sliceCtx("2")));
            given(delegateR.recover(failover, METHOD, List.of("1"), String.class, cause)).willReturn("A");
            given(delegateR.recover(failover, METHOD, List.of("2"), String.class, cause)).willReturn("B");
            given(splitter.merge(any())).willReturn(mergedCtx("A,B"));

            String recovered = gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause);

            assertThat(recovered).isEqualTo("A,B");
            verify(delegateR).recover(failover, METHOD, List.of("1"), String.class, cause);
            verify(delegateR).recover(failover, METHOD, List.of("2"), String.class, cause);
            verify(delegateR, never()).recoverAll(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("no slices recovered — returns null and never merges")
        void noSlicesReturnsNull() {
            given(splitter.splitOnRecover(any())).willReturn(List.of());

            String recovered = gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause);

            assertThat(recovered).isNull();
            verify(splitter, never()).merge(any());
        }

        @Test
        @DisplayName("merge yields a null payload — recover returns null")
        void mergeNullPayloadReturnsNull() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("1")));
            given(delegateR.recover(any(), any(), any(), any(), any())).willReturn("A");
            given(splitter.merge(any())).willReturn(mergedCtx(null));

            assertThat(gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause)).isNull();
        }

        @Test
        @DisplayName("partial recovery — publishes a recover-partial metric tagged method, missing, total (audit I-04)")
        void partialRecoveryPublishesMetric() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("1"), sliceCtx("2")));
            given(delegateR.recover(failover, METHOD, List.of("1"), String.class, cause)).willReturn("A");
            given(delegateR.recover(failover, METHOD, List.of("2"), String.class, cause)).willReturn(null);  // miss
            given(splitter.merge(any())).willReturn(mergedCtx("A"));

            gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause);

            verify(observablePublisher).publish(metricsCaptor.capture());
            java.util.Map<String, String> info = metricsCaptor.getValue().getInfo();
            assertThat(info)
                    .containsEntry("failover-action", "recover-partial")
                    .containsEntry("failover-method", "List#size")
                    .containsEntry("failover-missing", "1")
                    .containsEntry("failover-total", "2");
        }

        @Test
        @DisplayName("all slices missing — full non-recovery, no recover-partial metric (not partial)")
        void allSlicesMissingPublishesNoPartialMetric() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("1"), sliceCtx("2")));
            given(delegateR.recover(failover, METHOD, List.of("1"), String.class, cause)).willReturn(null);  // miss
            given(delegateR.recover(failover, METHOD, List.of("2"), String.class, cause)).willReturn(null);  // miss
            given(splitter.merge(any())).willReturn(mergedCtx(null));

            gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause);

            verify(observablePublisher, never()).publish(any());
        }

        @Test
        @DisplayName("full recovery — no recover-partial metric published")
        void fullRecoveryPublishesNoPartialMetric() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("1"), sliceCtx("2")));
            given(delegateR.recover(failover, METHOD, List.of("1"), String.class, cause)).willReturn("A");
            given(delegateR.recover(failover, METHOD, List.of("2"), String.class, cause)).willReturn("B");
            given(splitter.merge(any())).willReturn(mergedCtx("A,B"));

            gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause);

            verify(observablePublisher, never()).publish(any());
        }
    }

    // ── recover-all ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recover-all")
    class RecoverAll {

        @Test
        @DisplayName("empty args route to recover-all: delegates to recoverAll and merges all entries")
        void emptyArgsRecoverAll() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("template")));
            given(delegateR.recoverAll(failover, METHOD, List.of("template"), String.class, cause)).willReturn(List.of("A", "B"));
            given(splitter.merge(any())).willReturn(mergedCtx("A,B"));

            String recovered = gather.recover(failover, METHOD, List.of(), String.class, cause);

            assertThat(recovered).isEqualTo("A,B");
            verify(delegateR).recoverAll(failover, METHOD, List.of("template"), String.class, cause);
            verify(delegateR, never()).recover(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("recoverAll() flag forces recover-all even with non-empty args")
        void recoverAllFlagForcesRecoverAll() {
            given(failover.recoverAll()).willReturn(true);
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("template")));
            given(delegateR.recoverAll(any(), any(), any(), any(), any())).willReturn(List.of("A"));
            given(splitter.merge(any())).willReturn(mergedCtx("A"));

            String recovered = gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause);

            assertThat(recovered).isEqualTo("A");
            verify(delegateR).recoverAll(any(), any(), any(), any(), any());
            verify(delegateR, never()).recover(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("partial recovery (some entries null) — publishes a recover-partial metric")
        void partialRecoverAllPublishesMetric() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("template")));
            given(delegateR.recoverAll(failover, METHOD, List.of("template"), String.class, cause))
                    .willReturn(java.util.Arrays.asList("A", null));  // one entry missing
            given(splitter.merge(any())).willReturn(mergedCtx("A"));

            gather.recover(failover, METHOD, List.of(), String.class, cause);

            verify(observablePublisher).publish(metricsCaptor.capture());
            assertThat(metricsCaptor.getValue().getInfo())
                    .containsEntry("failover-action", "recover-partial")
                    .containsEntry("failover-missing", "1")
                    .containsEntry("failover-total", "2");
        }

        @Test
        @DisplayName("all entries missing — full non-recovery, no recover-partial metric")
        void allMissingRecoverAllPublishesNoPartialMetric() {
            given(splitter.splitOnRecover(any())).willReturn(List.of(sliceCtx("template")));
            given(delegateR.recoverAll(failover, METHOD, List.of("template"), String.class, cause))
                    .willReturn(java.util.Arrays.asList(null, null));  // every entry missing
            given(splitter.merge(any())).willReturn(mergedCtx(null));

            gather.recover(failover, METHOD, List.of(), String.class, cause);

            verify(observablePublisher, never()).publish(any());
        }

        @Test
        @DisplayName("splitOnRecover returns empty template — returns null, never delegates or merges")
        void emptyTemplateReturnsNull() {
            given(splitter.splitOnRecover(any())).willReturn(List.of());

            String recovered = gather.recover(failover, METHOD, List.of(), String.class, cause);

            assertThat(recovered).isNull();
            verify(delegateR, never()).recoverAll(any(), any(), any(), any(), any());
            verify(splitter, never()).merge(any());
        }
    }

    @Test
    @DisplayName("missing splitter bean — throws PayloadSplitterNotFoundException")
    void missingSplitterThrows() {
        given(payloadSplitterLookup.lookup(SPLITTER_NAME)).willReturn(null);

        assertThatThrownBy(() -> gather.recover(failover, METHOD, COMPOSITE_ARGS, String.class, cause))
                .isInstanceOf(PayloadSplitterNotFoundException.class);
        verify(delegateR, never()).recover(any(), any(), any(), any(), any());
    }
}
