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
import com.societegenerale.failover.core.payload.splitter.RecoverContext;
import com.societegenerale.failover.core.payload.splitter.StoreContext;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Unit tests for {@link SliceDispatcher} — parallel-vs-sequential slice dispatch and the per-slice
 * timeout policy.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SliceDispatcherTest {

    @Mock private Failover failover;
    @Mock private Throwable cause;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        given(failover.name()).willReturn("dispatcher-failover");
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private StoreContext<String> storeCtx(String payload) {
        return StoreContext.<String>builder().failover(failover).args(List.of(payload)).payload(payload).build();
    }

    private RecoverContext<String> recoverCtx(String key) {
        return RecoverContext.<String>builder().failover(failover).args(List.of(key)).clazz(String.class).cause(cause).build();
    }

    /** Maps a recover slice to the payload of its single arg — used as a simple identity mapper. */
    private String keyOf(RecoverContext<String> ctx) {
        return (String) ctx.getArgs().getFirst();
    }

    // ── dispatchStore ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("dispatchStore")
    class DispatchStore {

        @Test
        @DisplayName("sequential (no executor) runs the action for every slice in order")
        void sequentialRunsEverySliceInOrder() {
            var dispatcher = new SliceDispatcher<String>(null, ContextPropagator.noOp(), null);
            List<String> seen = new ArrayList<>();

            dispatcher.dispatchStore(List.of(storeCtx("a"), storeCtx("b"), storeCtx("c")),
                    ctx -> seen.add(ctx.getPayload()));

            assertThat(seen).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("parallel (executor) runs the action for every slice")
        void parallelRunsEverySlice() {
            executor = Executors.newFixedThreadPool(3);
            var dispatcher = new SliceDispatcher<String>(executor, ContextPropagator.noOp(), Duration.ofSeconds(5));
            List<String> seen = new CopyOnWriteArrayList<>();

            dispatcher.dispatchStore(List.of(storeCtx("a"), storeCtx("b"), storeCtx("c")),
                    ctx -> seen.add(ctx.getPayload()));

            assertThat(seen).containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("parallel store — a failing slice surfaces the failure to the caller")
        void parallelStoreFailurePropagates() {
            executor = Executors.newFixedThreadPool(2);
            var dispatcher = new SliceDispatcher<String>(executor, ContextPropagator.noOp(), Duration.ofSeconds(5));

            assertThatThrownBy(() -> dispatcher.dispatchStore(List.of(storeCtx("a")), ctx -> {
                throw new IllegalStateException("store boom");
            })).isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    // ── dispatchRecover ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("dispatchRecover")
    class DispatchRecover {

        private final Function<RecoverContext<String>, String> notRecovered = ctx -> "MISSING";

        @Test
        @DisplayName("sequential (no executor) maps every slice in order")
        void sequentialMapsEverySliceInOrder() {
            var dispatcher = new SliceDispatcher<String>(null, ContextPropagator.noOp(), null);

            List<String> result = dispatcher.dispatchRecover(
                    List.of(recoverCtx("1"), recoverCtx("2"), recoverCtx("3")),
                    SliceDispatcherTest.this::keyOf, notRecovered);

            assertThat(result).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("parallel (executor) preserves slice order in the result")
        void parallelPreservesOrder() {
            executor = Executors.newFixedThreadPool(3);
            var dispatcher = new SliceDispatcher<String>(executor, ContextPropagator.noOp(), Duration.ofSeconds(5));

            List<String> result = dispatcher.dispatchRecover(
                    List.of(recoverCtx("1"), recoverCtx("2"), recoverCtx("3")),
                    SliceDispatcherTest.this::keyOf, notRecovered);

            assertThat(result).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("parallel — a slice exceeding the timeout yields the onTimeout fallback; others map normally")
        void parallelTimedOutSliceUsesFallback() {
            executor = Executors.newFixedThreadPool(3);
            var dispatcher = new SliceDispatcher<String>(executor, ContextPropagator.noOp(), Duration.ofMillis(100));

            List<String> result = dispatcher.dispatchRecover(
                    List.of(recoverCtx("fast-1"), recoverCtx("slow"), recoverCtx("fast-2")),
                    ctx -> {
                        if ("slow".equals(keyOf(ctx))) {
                            sleep(2000);
                        }
                        return keyOf(ctx);
                    },
                    notRecovered);

            assertThat(result).containsExactly("fast-1", "MISSING", "fast-2");
        }

        @Test
        @DisplayName("parallel — a non-timeout failure propagates (not swallowed as a timeout)")
        void parallelNonTimeoutFailurePropagates() {
            executor = Executors.newFixedThreadPool(2);
            var dispatcher = new SliceDispatcher<String>(executor, ContextPropagator.noOp(), Duration.ofSeconds(5));

            assertThatThrownBy(() -> dispatcher.dispatchRecover(
                    List.of(recoverCtx("1")),
                    ctx -> { throw new IllegalStateException("recover boom"); },
                    notRecovered))
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
