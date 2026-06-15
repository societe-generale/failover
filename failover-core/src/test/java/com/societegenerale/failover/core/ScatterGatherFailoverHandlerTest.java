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
import com.societegenerale.failover.domain.Referential;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Tests {@link ScatterGatherFailoverHandler} scatter/gather routing and delegation.
 *
 * <p>Uses {@code T=ThirdPartiesResult} (composite) and {@code R=ThirdParty} (slice) to reflect
 * real-world scatter/gather: a composite result is scattered into individual slices on store,
 * and individual slices are gathered and merged back into a composite on recover.
 *
 * <p>Args shape: composite = {@code ["active", "1,2,3", "india"]} (3-element list where index 1
 * is a CSV of IDs); slice = {@code ["active", "<id>", "india"]} (same prefix/suffix, single ID).
 * The splitter reads and writes index 1; indices 0 and 2 are preserved unchanged.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScatterGatherFailoverHandlerTest {

    private static final String FAILOVER_NAME = "third-parties-failover";

    private static final String SPLITTER_NAME = "thirdPartiesSplitter";

    private static final ThirdParty TP_1 = new ThirdParty(1L, "ThirdParty-1", 10);

    private static final ThirdParty TP_2 = new ThirdParty(2L, "ThirdParty-2", 20);

    private static final ThirdParty TP_3 = new ThirdParty(3L, "ThirdParty-3", 30);

    // Composite args: index 0 = filter, index 1 = CSV of IDs, index 2 = region
    private static final List<Object> ARGS_1   = List.of("active", "1",     "India");
    private static final List<Object> ARGS_2   = List.of("active", "2",     "India");
    private static final List<Object> ARGS_3   = List.of("active", "3",     "India");
    private static final List<Object> ARGS_1_2 = List.of("active", "1,2",   "India");
    private static final List<Object> ARGS_1_2_3 = List.of("active", "1,2,3", "India");

    private static final Method METHOD = sampleMethod();

    @Mock private Failover failover;
    @Mock private FailoverHandler<ThirdPartiesResult> delegateT;
    @Mock private FailoverHandler<ThirdParty> delegateR;
    @Mock private Throwable cause;
    @Mock private PayloadSplitterLookup<ThirdPartiesResult, ThirdParty> payloadSplitterLookup;

    private final PayloadSplitter<ThirdPartiesResult, ThirdParty> thirdPartyPayloadSplitter = new ThirdPartyPayloadSplitter();

    private final PayloadSplitter<ThirdPartiesResult, ThirdParty> thirdPartyPayloadSplitterForRecoverAll = new ThirdPartyPayloadSplitterForRecoverAll();

    private ScatterGatherFailoverHandler<ThirdPartiesResult, ThirdParty> handler;

    @BeforeEach
    void setUp() {
        handler = new ScatterGatherFailoverHandler<>(delegateT, delegateR, payloadSplitterLookup);
        given(failover.name()).willReturn(FAILOVER_NAME);
        given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
        doReturn(thirdPartyPayloadSplitter).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
    }

    private static Method sampleMethod() {
        try {
            return SampleService.class.getMethod("findAll");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    interface SampleService {
        ThirdPartiesResult findAll();
    }

    private static ThirdPartiesResult result(ThirdParty... parties) {
        ThirdPartiesResult r = new ThirdPartiesResult();
        r.setThirdParties(List.of(parties));
        return r;
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCATTER STORE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scatter Store")
    class ScatterStoreTests {

        @Test
        @DisplayName("should call splitOnStore with composite StoreContext<ThirdPartiesResult> and delegate each ThirdParty slice to delegateR")
        void shouldCallSplitOnStoreAndDelegateEachSliceToDelegateR() {
            ThirdPartiesResult compositePayload = result(TP_1, TP_2, TP_3);

            ThirdPartiesResult stored = handler.store(failover, METHOD, ARGS_1_2_3, compositePayload);

            assertThat(stored).isEqualTo(compositePayload);
            verify(delegateT, never()).store(any(), any(), any(), any());
            verify(delegateR).store(failover, METHOD, ARGS_1, TP_1);
            verify(delegateR).store(failover, METHOD, ARGS_2, TP_2);
            verify(delegateR).store(failover, METHOD, ARGS_3, TP_3);
        }

        @Test
        @DisplayName("should return null without calling splitter when payload is null")
        void shouldReturnNullWithoutCallingSplitterWhenPayloadIsNull() {
            ThirdPartiesResult result = handler.store(failover, METHOD, ARGS_1_2_3, null);

            assertThat(result).isNull();
            verify(delegateR, never()).store(any(), any(), any(), any());
            verify(delegateT, never()).store(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should delegate to delegateT without touching splitter when payloadSplitter not configured")
        void shouldDelegateToDelegateTWhenNoSplitterConfigured() {
            given(failover.payloadSplitter()).willReturn("");
            ThirdPartiesResult compositePayload = result(TP_1, TP_2);

            handler.store(failover, METHOD, ARGS_1_2, compositePayload);

            verify(delegateT).store(failover, METHOD, ARGS_1_2, compositePayload);
            verify(delegateR, never()).store(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should throw PayloadSplitterNotFoundException when splitter bean not found")
        void shouldThrowWhenSplitterBeanNotFound() {
            doReturn(null).when(payloadSplitterLookup).lookup(SPLITTER_NAME);

            assertThatThrownBy(() -> handler.store(failover, METHOD, ARGS_1, result(TP_1)))
                    .isInstanceOf(PayloadSplitterNotFoundException.class)
                    .hasMessageContaining(FAILOVER_NAME)
                    .hasMessageContaining(SPLITTER_NAME);

            verify(delegateR, never()).store(any(), any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCATTER RECOVER
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Scatter Recover")
    class ScatterRecoverTests {

        @Test
        @DisplayName("should call splitOnRecover, recover each ThirdParty slice via delegateR, then merge into ThirdPartiesResult")
        void shouldCallSplitOnRecoverAndMergeIntoComposite() {
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willReturn(TP_2);
            given(delegateR.recover(failover, METHOD, ARGS_3, ThirdParty.class, cause)).willReturn(TP_3);

            ThirdPartiesResult recovered = handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            assertThat(recovered).isEqualTo(result(TP_1, TP_2, TP_3));
            verify(delegateT, never()).recover(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should build new RecoverContext per slice with recovered payload — no input mutation")
        void shouldBuildNewRecoverContextPerSliceWithPayload() {
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willReturn(TP_2);

            ThirdPartiesResult recovered = handler.recover(failover, METHOD, ARGS_1_2, ThirdPartiesResult.class, cause);

            assertThat(recovered).isEqualTo(result(TP_1, TP_2));
            verify(delegateR).recover(failover, METHOD, ARGS_1, ThirdParty.class, cause);
            verify(delegateR).recover(failover, METHOD, ARGS_2, ThirdParty.class, cause);
        }

        @Test
        @DisplayName("should delegate to delegateT without touching splitter when payloadSplitter not configured")
        void shouldDelegateToDelegateTWhenNoSplitterConfigured() {
            given(failover.payloadSplitter()).willReturn("");

            handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            verify(delegateT).recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);
            verify(delegateR, never()).recover(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should return null without calling merge when splitOnRecover returns empty (line 227-230 guard)")
        void shouldReturnNullWithoutCallingMergeWhenSplitOnRecoverReturnsEmpty() {
            PayloadSplitter<ThirdPartiesResult, ThirdParty> emptySplitter = mock();
            doReturn(emptySplitter).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(emptySplitter.splitOnRecover(any())).willReturn(List.of());

            ThirdPartiesResult recovered = handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            assertThat(recovered).isNull();
            verify(delegateR, never()).recover(any(), any(), any(), any(), any());
            verify(emptySplitter, never()).merge(any());
        }

        @Test
        @DisplayName("should throw PayloadSplitterNotFoundException when splitter not found during recover")
        void shouldThrowWhenSplitterNotFoundDuringRecover() {
            doReturn(null).when(payloadSplitterLookup).lookup(SPLITTER_NAME);

            assertThatThrownBy(() -> handler.recover(failover, METHOD, ARGS_1_2, ThirdPartiesResult.class, cause))
                    .isInstanceOf(PayloadSplitterNotFoundException.class)
                    .hasMessageContaining(FAILOVER_NAME)
                    .hasMessageContaining(SPLITTER_NAME);

            verify(delegateR, never()).recover(any(), any(), any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CLEAN
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Clean")
    class CleanTests {

        @Test
        @DisplayName("should call clean on both delegateT and delegateR")
        void shouldCleanBothDelegates() {
            handler.clean();
            verify(delegateT).clean();
            verify(delegateR).clean();
        }

        @Test
        @DisplayName("should not call clean on both delegateT and delegateR if its same reference — same instance should not be called twice")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void shouldNotCleanBothDelegatesWhenSameInstance() {
            FailoverHandler sameDelegate = mock(FailoverHandler.class);
            var h = new ScatterGatherFailoverHandler<>(sameDelegate, sameDelegate, payloadSplitterLookup);
            h.clean();
            verify(sameDelegate).clean();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NO SPLITTER CONFIGURED — pass-through to delegateT
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("No payloadSplitter configured — delegates to delegateT unchanged")
    class NoSplitterConfiguredTests {

        @BeforeEach
        void overrideToNoSplitter() {
            given(failover.payloadSplitter()).willReturn("");
        }

        @Test
        @DisplayName("store should delegate composite ThirdPartiesResult to delegateT and return its result")
        void storeShouldDelegateToDelegateTAndReturnItsResult() {
            ThirdPartiesResult compositePayload = result(TP_1, TP_2);
            given(delegateT.store(failover, METHOD, ARGS_1_2, compositePayload)).willReturn(compositePayload);

            ThirdPartiesResult stored = handler.store(failover, METHOD, ARGS_1_2, compositePayload);

            assertThat(stored).isEqualTo(compositePayload);
            verify(delegateT).store(failover, METHOD, ARGS_1_2, compositePayload);
            verify(delegateR, never()).store(any(), any(), any(), any());
        }

        @Test
        @DisplayName("recover should delegate to delegateT and return composite ThirdPartiesResult")
        void recoverShouldDelegateToDelegateTAndReturnComposite() {
            ThirdPartiesResult expected = result(TP_1, TP_2, TP_3);
            given(delegateT.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause)).willReturn(expected);

            ThirdPartiesResult recovered = handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            assertThat(recovered).isEqualTo(expected);
            verify(delegateT).recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);
            verify(delegateR, never()).recover(any(), any(), any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ORDER-VARIANT RECOVERY — same slice store, different CSV orderings
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Order-variant recovery from same slice store")
    class OrderVariantTests {

        // Reverse-order composite args — same IDs, reversed sequence
        private static final List<Object> ARGS_3_2_1 = List.of("active", "3,2,1", "India");

        @BeforeEach
        void stubSlices() {
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willReturn(TP_2);
            given(delegateR.recover(failover, METHOD, ARGS_3, ThirdParty.class, cause)).willReturn(TP_3);
        }

        @Test
        @DisplayName("ascending order 1,2,3 and descending 3,2,1 return the same set of ThirdParties — order follows request")
        void differentArgOrderSameContentSingleStoreSource() {
            ThirdPartiesResult recoveredAsc  = handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);
            ThirdPartiesResult recoveredDesc = handler.recover(failover, METHOD, ARGS_3_2_1, ThirdPartiesResult.class, cause);

            // Same three ThirdParties in both results — same slice store
            assertThat(recoveredAsc).isNotNull();
            assertThat(recoveredDesc).isNotNull();
            assertThat(recoveredAsc.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);
            assertThat(recoveredDesc.getThirdParties()).containsExactlyInAnyOrder(TP_1, TP_2, TP_3);

            // Result order follows request order
            assertThat(recoveredAsc.getThirdParties()).containsExactly(TP_1, TP_2, TP_3);
            assertThat(recoveredDesc.getThirdParties()).containsExactly(TP_3, TP_2, TP_1);
        }

        @Test
        @DisplayName("ascending 1,2,3 and descending 3,2,1 with null payload for id=2 — null in request-order position, non-null content identical")
        void differentArgOrderNullPayloadForId2SameNonNullContent() {
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willReturn(null); // id=2 is cache miss

            ThirdPartiesResult recoveredAsc  = handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);
            ThirdPartiesResult recoveredDesc = handler.recover(failover, METHOD, ARGS_3_2_1, ThirdPartiesResult.class, cause);

            // null appears at the position matching id=2's place in the request order
            assertThat(recoveredAsc).isNotNull();
            assertThat(recoveredDesc).isNotNull();
            assertThat(recoveredAsc.getThirdParties()).containsExactly(TP_1, null, TP_3);
            assertThat(recoveredDesc.getThirdParties()).containsExactly(TP_3, null, TP_1);

            // Non-null content is identical regardless of request order
            assertThat(recoveredAsc.getThirdParties()).filteredOn(Objects::nonNull).containsExactlyInAnyOrder(TP_1, TP_3);
            assertThat(recoveredDesc.getThirdParties()).filteredOn(Objects::nonNull).containsExactlyInAnyOrder(TP_1, TP_3);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RECOVER ALL - No Executor - WHEN NO ARG GIVEN  ( OR NO ID ARGS GIVEN WITH SOME OTHER ARGS )
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Store All - RecoverAll (findAll()) - No Executor - payloadSplitter configured — delegates to delegateR to recover all")
    class RecoverAllSplitterConfiguredTests {

        @BeforeEach
        void overrideToNoSplitter() {
            given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("findAll() - store - payloadSplitter configured — store all - split one to many and store all when args is null or empty")
        void shouldSplitOneToManyAndStoreAllWhenArgsIsNullOrEmpty(List<Object> nullOrEmptyArgs) {
            doReturn(thirdPartyPayloadSplitterForRecoverAll).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            ThirdPartiesResult compositePayload = result(TP_1, TP_2, TP_3);

            ThirdPartiesResult stored = handler.store(failover, METHOD, nullOrEmptyArgs, compositePayload);

            assertThat(stored).isEqualTo(compositePayload);
            verify(delegateT, never()).store(any(), any(), any(), any());
            verify(delegateR).store(failover, METHOD, List.of(1L), TP_1);
            verify(delegateR).store(failover, METHOD, List.of(2L), TP_2);
            verify(delegateR).store(failover, METHOD, List.of(3L), TP_3);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("findAll() - payloadSplitter configured — recover all and merge when args is null or empty")
        void shouldCallSplitOnRecoverAndDelegateEachSliceToDelegateRForRecoverAllWhenArgsEmpty(List<Object> nullOrEmptyArgs) {
            doReturn(thirdPartyPayloadSplitterForRecoverAll).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(delegateR.recoverAll(failover, METHOD, nullOrEmptyArgs, ThirdParty.class, cause)).willReturn(List.of(TP_1, TP_2, TP_3));

            ThirdPartiesResult stored = handler.recover(failover, METHOD, nullOrEmptyArgs, ThirdPartiesResult.class, cause);

            assertThat(stored).isEqualTo(result(TP_1, TP_2, TP_3));
            verify(delegateT, never()).store(any(), any(), any(), any());
        }

        @Test
        @DisplayName("findAll('non-id-args') - payloadSplitter configured — recover all and merge when args is not empty and failover configured with recoverAll true")
        void shouldCallSplitOnRecoverAndDelegateEachSliceToDelegateRForRecoverAllWhenArgsNotEmptyAndFailoverRecoverAllIsTrue() {
            var inputArgs = List.<Object>of("some non id args", "some other non id args");
            given(failover.recoverAll()).willReturn(true);
            given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
            doReturn(thirdPartyPayloadSplitterForRecoverAll).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(delegateR.recoverAll(failover, METHOD, inputArgs, ThirdParty.class, cause)).willReturn(List.of(TP_1, TP_2, TP_3));

            ThirdPartiesResult stored = handler.recover(failover, METHOD, inputArgs, ThirdPartiesResult.class, cause);

            assertThat(stored).isEqualTo(result(TP_1, TP_2, TP_3));
            verify(delegateT, never()).store(any(), any(), any(), any());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("findAll() - splitOnRecover returns empty — doRecoverAll returns empty (line 253-256), recover returns null (line 227-230), merge never called")
        void shouldReturnNullWithoutCallingMergeWhenSplitOnRecoverReturnsEmptyInRecoverAllPath(List<Object> nullOrEmptyArgs) {
            PayloadSplitter<ThirdPartiesResult, ThirdParty> emptySplitter = mock();
            doReturn(emptySplitter).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(emptySplitter.splitOnRecover(any())).willReturn(List.of());

            ThirdPartiesResult recovered = handler.recover(failover, METHOD, nullOrEmptyArgs, ThirdPartiesResult.class, cause);

            assertThat(recovered).isNull();
            verify(delegateR, never()).recoverAll(any(), any(), any(), any(), any());
            verify(emptySplitter, never()).merge(any());
        }

        @Test
        @DisplayName("Recover - findAllById('1,2,3') - payloadSplitter configured — split and recover and merge when args is comma separated ids and failover configured with recoverAll false")
        void shouldCallSplitOnRecoverAndDelegateSliceToDelegateRForRecoverAllWhenArgsIsNotEmptyAndFailoverRecoverAllIsFalse() {
            given(failover.recoverAll()).willReturn(false);
            given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
            doReturn(thirdPartyPayloadSplitter).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willReturn(TP_2);
            given(delegateR.recover(failover, METHOD, ARGS_3, ThirdParty.class, cause)).willReturn(TP_3);

            ThirdPartiesResult recovered = handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            assertThat(recovered).isEqualTo(result(TP_1, TP_2, TP_3));
            verify(delegateR).recover(failover, METHOD, ARGS_1, ThirdParty.class, cause);
            verify(delegateR).recover(failover, METHOD, ARGS_2, ThirdParty.class, cause);
            verify(delegateR).recover(failover, METHOD, ARGS_3, ThirdParty.class, cause);
            verify(delegateR, never()).recoverAll(any(), any(), any(), any(), any());
            verify(delegateT, never()).recover(any(), any(), any(), any(), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RECOVER ALL - With Executor - WHEN NO ARG GIVEN  ( OR NO ID ARGS GIVEN WITH SOME OTHER ARGS )
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RecoverAll (findAll()) - With Executor - payloadSplitter configured — delegates to delegateR to recover all")
    class RecoverAllSplitterConfiguredWithExecutorTests {

        private ExecutorService executorService;
        private AtomicInteger propagatorCallCount;
        private ScatterGatherFailoverHandler<ThirdPartiesResult, ThirdParty> parallelHandler;

        @BeforeEach
        void setUp() {
            executorService = Executors.newFixedThreadPool(4);
            propagatorCallCount = new AtomicInteger();
            ContextPropagator countingPropagator = task -> {
                propagatorCallCount.incrementAndGet();
                return task;
            };
            parallelHandler = new ScatterGatherFailoverHandler<>(delegateT, delegateR, payloadSplitterLookup, executorService, countingPropagator);
            given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
        }

        @AfterEach
        void tearDown() {
            executorService.shutdownNow();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("findAll() - payloadSplitter configured — split one to many and store all when args is null or empty")
        void shouldSplitOneToManyAndStoreAllWhenArgsIsNullOrEmpty(List<Object> nullOrEmptyArgs) {
            doReturn(thirdPartyPayloadSplitterForRecoverAll).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            ThirdPartiesResult compositePayload = result(TP_1, TP_2, TP_3);

            ThirdPartiesResult stored = parallelHandler.store(failover, METHOD, nullOrEmptyArgs, compositePayload);

            assertThat(stored).isEqualTo(compositePayload);
            assertThat(propagatorCallCount.get()).isEqualTo(3);
            verify(delegateT, never()).store(any(), any(), any(), any());
            verify(delegateR).store(failover, METHOD, List.of(1L), TP_1);
            verify(delegateR).store(failover, METHOD, List.of(2L), TP_2);
            verify(delegateR).store(failover, METHOD, List.of(3L), TP_3);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("findAll() - payloadSplitter configured — recover all and merge when args is null or empty")
        void shouldCallSplitOnRecoverAndDelegateEachSliceToDelegateRForRecoverAllWhenArgsEmpty(List<Object> nullOrEmptyArgs) {
            doReturn(thirdPartyPayloadSplitterForRecoverAll).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(delegateR.recoverAll(failover, METHOD, nullOrEmptyArgs, ThirdParty.class, cause)).willReturn(List.of(TP_1, TP_2, TP_3));

            ThirdPartiesResult stored = parallelHandler.recover(failover, METHOD, nullOrEmptyArgs, ThirdPartiesResult.class, cause);

            assertThat(propagatorCallCount.get()).isEqualTo(1);
            verify(delegateR).recoverAll(failover, METHOD, nullOrEmptyArgs, ThirdParty.class, cause);
            assertThat(stored).isEqualTo(result(TP_1, TP_2, TP_3));
            verify(delegateT, never()).store(any(), any(), any(), any());
        }

        @Test
        @DisplayName("findAll() - payloadSplitter configured — recover all and merge when args is not empty and failover configured with recoverAll true")
        void shouldCallSplitOnRecoverAndDelegateEachSliceToDelegateRForRecoverAllWhenArgsNotEmptyAndFailoverRecoverAllIsTrue() {
            var inputArgs = List.<Object>of("some non id args", "some other non id args");
            given(failover.recoverAll()).willReturn(true);
            doReturn(thirdPartyPayloadSplitterForRecoverAll).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(delegateR.recoverAll(failover, METHOD, inputArgs, ThirdParty.class, cause)).willReturn(List.of(TP_1, TP_2, TP_3));

            ThirdPartiesResult stored = parallelHandler.recover(failover, METHOD, inputArgs, ThirdPartiesResult.class, cause);

            assertThat(propagatorCallCount.get()).isEqualTo(1);
            verify(delegateR).recoverAll(failover, METHOD, inputArgs, ThirdParty.class, cause);
            assertThat(stored).isEqualTo(result(TP_1, TP_2, TP_3));
            verify(delegateT, never()).store(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Recover - findAllById('1,2,3') - payloadSplitter configured — split and recover and merge when args is comma separated ids and failover configured with recoverAll false")
        void shouldCallSplitOnRecoverAndDelegateSliceToDelegateRForRecoverAllWhenArgsIsNotEmptyAndFailoverRecoverAllIsFalse() {
            given(failover.recoverAll()).willReturn(false);
            given(failover.payloadSplitter()).willReturn(SPLITTER_NAME);
            doReturn(thirdPartyPayloadSplitter).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willReturn(TP_2);
            given(delegateR.recover(failover, METHOD, ARGS_3, ThirdParty.class, cause)).willReturn(TP_3);

            ThirdPartiesResult recovered = parallelHandler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            assertThat(recovered).isEqualTo(result(TP_1, TP_2, TP_3));
            verify(delegateR).recover(failover, METHOD, ARGS_1, ThirdParty.class, cause);
            verify(delegateR).recover(failover, METHOD, ARGS_2, ThirdParty.class, cause);
            verify(delegateR).recover(failover, METHOD, ARGS_3, ThirdParty.class, cause);
            verify(delegateR, never()).recoverAll(any(), any(), any(), any(), any());
            verify(delegateT, never()).recover(any(), any(), any(), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PARALLEL SCATTER — executor + context propagator
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Parallel scatter — executor + ContextPropagator")
    class ParallelScatterTests {

        private ExecutorService executorService;
        private AtomicInteger propagatorCallCount;
        private ScatterGatherFailoverHandler<ThirdPartiesResult, ThirdParty> parallelHandler;

        @BeforeEach
        void setUp() {
            executorService = Executors.newFixedThreadPool(4);
            propagatorCallCount = new AtomicInteger();
            ContextPropagator countingPropagator = task -> {
                propagatorCallCount.incrementAndGet();
                return task;
            };
            parallelHandler = new ScatterGatherFailoverHandler<>(delegateT, delegateR, payloadSplitterLookup, executorService, countingPropagator);
        }

        @AfterEach
        void tearDown() {
            executorService.shutdownNow();
        }

        @Test
        @DisplayName("scatter-store: invokes contextPropagator.wrap() once per slice and delegates each slice to delegateR")
        void parallelScatterStoreDelegatesEachSliceAndCallsPropagatorPerSlice() {
            ThirdPartiesResult payload = result(TP_1, TP_2, TP_3);

            ThirdPartiesResult stored = parallelHandler.store(failover, METHOD, ARGS_1_2_3, payload);

            assertThat(stored).isEqualTo(payload);
            assertThat(propagatorCallCount.get()).isEqualTo(3);
            verify(delegateR).store(failover, METHOD, ARGS_1, TP_1);
            verify(delegateR).store(failover, METHOD, ARGS_2, TP_2);
            verify(delegateR).store(failover, METHOD, ARGS_3, TP_3);
            verify(delegateT, never()).store(any(), any(), any(), any());
        }

        @Test
        @DisplayName("scatter-recover: invokes contextPropagator.wrap() once per slice and merges into composite")
        void parallelScatterRecoverCallsPropagatorPerSliceAndMergesResult() {
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willReturn(TP_2);
            given(delegateR.recover(failover, METHOD, ARGS_3, ThirdParty.class, cause)).willReturn(TP_3);

            ThirdPartiesResult recovered = parallelHandler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            assertThat(recovered).isEqualTo(result(TP_1, TP_2, TP_3));
            assertThat(propagatorCallCount.get()).isEqualTo(3);
            verify(delegateT, never()).recover(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("contextPropagator propagates context captured on calling thread to executor thread")
        void contextPropagatorCapturesCallingThreadContextAndRestoresOnExecutorThread() {
            ThreadLocal<String> ctx = new ThreadLocal<>();
            ctx.set("tenant-X");
            List<String> seenOnExecutorThread = new java.util.concurrent.CopyOnWriteArrayList<>();

            ContextPropagator capturingPropagator = task -> {
                String captured = ctx.get();
                return () -> {
                    ctx.set(captured);
                    try { task.run(); } finally { ctx.remove(); }
                };
            };
            var capturingHandler = new ScatterGatherFailoverHandler<>(delegateT, delegateR, payloadSplitterLookup, executorService, capturingPropagator);

            doAnswer(invocation -> { seenOnExecutorThread.add(ctx.get()); return null; })
                    .when(delegateR).store(any(), any(), any(), any());

            capturingHandler.store(failover, METHOD, ARGS_1_2, result(TP_1, TP_2));

            assertThat(seenOnExecutorThread).containsOnly("tenant-X");
            ctx.remove();
        }

        @Test
        @DisplayName("no-op propagator: sequential handler does not invoke propagator")
        void sequentialHandlerDoesNotInvokePropagator() {
            handler.store(failover, METHOD, ARGS_1_2, result(TP_1, TP_2));
            assertThat(propagatorCallCount.get()).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PARALLEL SCATTER — per-slice timeout (failover.scatter.timeout)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Parallel scatter — per-slice timeout")
    class ParallelScatterTimeoutTests {

        private ExecutorService executorService;
        private ScatterGatherFailoverHandler<ThirdPartiesResult, ThirdParty> timeoutHandler;

        @BeforeEach
        void setUp() {
            executorService = Executors.newFixedThreadPool(4);
            timeoutHandler = new ScatterGatherFailoverHandler<>(delegateT, delegateR, payloadSplitterLookup,
                    executorService, ContextPropagator.noOp(), Duration.ofMillis(150));
        }

        @AfterEach
        void tearDown() {
            executorService.shutdownNow();
        }

        @Test
        @DisplayName("scatter-recover: a slice exceeding the timeout is treated as not recovered (null in its position); other slices still merge")
        void recoverTimedOutSliceTreatedAsNotRecovered() {
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_3, ThirdParty.class, cause)).willReturn(TP_3);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause)).willAnswer(inv -> {
                Thread.sleep(2_000);
                return TP_2;
            });

            ThirdPartiesResult recovered = timeoutHandler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause);

            assertThat(recovered).isNotNull();
            assertThat(recovered.getThirdParties()).containsExactly(TP_1, null, TP_3);
        }

        @Test
        @DisplayName("scatter-store: a slice exceeding the timeout surfaces a TimeoutException to the caller (isolated upstream)")
        void storeTimedOutSliceSurfacesTimeout() {
            doAnswer(inv -> { Thread.sleep(2_000); return null; }).when(delegateR).store(eq(failover), any(), eq(ARGS_2), any());

            assertThatThrownBy(() -> timeoutHandler.store(failover, METHOD, ARGS_1_2_3, result(TP_1, TP_2, TP_3)))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        }

        @Test
        @DisplayName("scatter-recover: a slice failing with a non-timeout exception propagates (not swallowed as a timeout)")
        void recoverSliceNonTimeoutFailurePropagates() {
            given(delegateR.recover(failover, METHOD, ARGS_1, ThirdParty.class, cause)).willReturn(TP_1);
            given(delegateR.recover(failover, METHOD, ARGS_3, ThirdParty.class, cause)).willReturn(TP_3);
            given(delegateR.recover(failover, METHOD, ARGS_2, ThirdParty.class, cause))
                    .willThrow(new IllegalStateException("slice boom"));

            assertThatThrownBy(() -> timeoutHandler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause))
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("scatter-recover-all: a recoverAll slice exceeding the timeout yields no data — recover returns null instead of hanging")
        void recoverAllTimedOutSliceReturnsNull() {
            doReturn(thirdPartyPayloadSplitterForRecoverAll).when(payloadSplitterLookup).lookup(SPLITTER_NAME);
            given(delegateR.recoverAll(eq(failover), any(), any(), eq(ThirdParty.class), eq(cause))).willAnswer(inv -> {
                Thread.sleep(2_000);
                return List.of(TP_1, TP_2, TP_3);
            });

            ThirdPartiesResult recovered = timeoutHandler.recover(failover, METHOD, List.of(), ThirdPartiesResult.class, cause);

            assertThat(recovered).isNull();
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    // PayloadSplitterExecutionException — wraps user splitter exceptions
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PayloadSplitterExecutionException — user splitter exceptions wrapped with context")
    class PayloadSplitterExecutionExceptionTests {

        private static final String SPLITTER_BOOM = "boom";
        private static final RuntimeException SPLITTER_CAUSE = new RuntimeException("simulated splitter failure");

        @BeforeEach
        void stubBoomSplitter() {
            given(failover.payloadSplitter()).willReturn(SPLITTER_BOOM);
        }

        @Test
        @DisplayName("splitOnStore exception wrapped as PayloadSplitterExecutionException with failover name and splitter name")
        void splitOnStoreShouldWrapException() {
            PayloadSplitter<ThirdPartiesResult, ThirdParty> boomSplitter = mock();
            doReturn(boomSplitter).when(payloadSplitterLookup).lookup(SPLITTER_BOOM);
            given(boomSplitter.splitOnStore(any())).willThrow(SPLITTER_CAUSE);

            assertThatThrownBy(() -> handler.store(failover, METHOD, ARGS_1_2_3, result(TP_1)))
                    .isInstanceOf(PayloadSplitterExecutionException.class)
                    .hasMessageContaining(FAILOVER_NAME)
                    .hasMessageContaining(SPLITTER_BOOM)
                    .hasMessageContaining("splitOnStore")
                    .hasCause(SPLITTER_CAUSE);
        }

        @Test
        @DisplayName("splitOnRecover exception wrapped as PayloadSplitterExecutionException with failover name and splitter name")
        void splitOnRecoverShouldWrapException() {
            PayloadSplitter<ThirdPartiesResult, ThirdParty> boomSplitter = mock();
            doReturn(boomSplitter).when(payloadSplitterLookup).lookup(SPLITTER_BOOM);
            given(boomSplitter.splitOnRecover(any())).willThrow(SPLITTER_CAUSE);

            assertThatThrownBy(() -> handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause))
                    .isInstanceOf(PayloadSplitterExecutionException.class)
                    .hasMessageContaining(FAILOVER_NAME)
                    .hasMessageContaining(SPLITTER_BOOM)
                    .hasMessageContaining("splitOnRecover")
                    .hasCause(SPLITTER_CAUSE);
        }

        @Test
        @DisplayName("merge exception wrapped as PayloadSplitterExecutionException with failover name and splitter name")
        void mergeShouldWrapException() {
            PayloadSplitter<ThirdPartiesResult, ThirdParty> boomSplitter = mock();
            doReturn(boomSplitter).when(payloadSplitterLookup).lookup(SPLITTER_BOOM);
            given(boomSplitter.splitOnRecover(any())).willReturn(
                    List.of(RecoverContext.<ThirdParty>builder()
                            .failover(failover).args(ARGS_1).clazz(ThirdParty.class).cause(cause).build()));
            given(delegateR.recover(any(), any(), any(), any(), any())).willReturn(TP_1);
            given(boomSplitter.merge(any())).willThrow(SPLITTER_CAUSE);

            assertThatThrownBy(() -> handler.recover(failover, METHOD, ARGS_1_2_3, ThirdPartiesResult.class, cause))
                    .isInstanceOf(PayloadSplitterExecutionException.class)
                    .hasMessageContaining(FAILOVER_NAME)
                    .hasMessageContaining(SPLITTER_BOOM)
                    .hasMessageContaining("merge")
                    .hasCause(SPLITTER_CAUSE);
        }
    }

        // ════════════════════════════════════════════════════════════════════════
    // TEST DOMAIN TYPES
    // ════════════════════════════════════════════════════════════════════════

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    public static class ThirdParty extends Referential {
        private Long id;
        private String name;
        private int score;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    static class ThirdPartiesResult extends Referential {
        private List<ThirdParty> thirdParties;
    }

    /**
     * Composite args: ["active", "1,2,3", "India"] — index 1 holds the CSV of IDs.
     * Slice args:     ["active", "<id>",   "India"] — same prefix/suffix, single ID at index 1.
     */
    static class ThirdPartyPayloadSplitter implements PayloadSplitter<ThirdPartiesResult, ThirdParty> {

        @Override
        public List<StoreContext<ThirdParty>> splitOnStore(StoreContext<ThirdPartiesResult> context) {
            String prefix = (String) context.getArgs().getFirst();
            String suffix = (String) context.getArgs().getLast();
            return context.getPayload().getThirdParties().stream()
                    .map(tp -> StoreContext.<ThirdParty>builder()
                            .failover(context.getFailover())
                            .args(List.of(prefix, String.valueOf(tp.getId()), suffix))
                            .payload(tp)
                            .build())
                    .toList();
        }

        @Override
        public List<RecoverContext<ThirdParty>> splitOnRecover(RecoverContext<ThirdPartiesResult> context) {
            String prefix = (String) context.getArgs().getFirst();
            String csvArg = (String) context.getArgs().get(1);   // CSV is always at index 1
            String suffix = (String) context.getArgs().getLast();
            return Arrays.stream(csvArg.split(","))
                    .map(id -> RecoverContext.<ThirdParty>builder()
                            .failover(context.getFailover())
                            .args(List.of(prefix, id.trim(), suffix))
                            .clazz(ThirdParty.class)
                            .cause(context.getCause())
                            .build())
                    .toList();
        }

        @Override
        public RecoverContext<ThirdPartiesResult> merge(List<RecoverContext<ThirdParty>> contexts) {
            List<ThirdParty> parties = contexts.stream()
                    .map(RecoverContext::getPayload)
                    .toList();
            ThirdPartiesResult merged = new ThirdPartiesResult();
            merged.setThirdParties(parties);
            String prefix = (String) contexts.getFirst().getArgs().getFirst();
            String compositeIds = contexts.stream()
                    .map(ctx -> (String) ctx.getArgs().get(1))
                    .collect(Collectors.joining(","));
            String suffix = (String) contexts.getFirst().getArgs().getLast();
            return RecoverContext.<ThirdPartiesResult>builder()
                    .failover(contexts.getFirst().getFailover())
                    .args(List.of(prefix, compositeIds, suffix))
                    .clazz(ThirdPartiesResult.class)
                    .cause(contexts.getFirst().getCause())
                    .payload(merged)
                    .build();
        }
    }


    /**
     * Zero args: No arg or recoverAll  — findAll() scenario.
     * Slice args: Single slice with empty args.
     */
    static class ThirdPartyPayloadSplitterForRecoverAll implements PayloadSplitter<ThirdPartiesResult, ThirdParty> {

        @Override
        public List<StoreContext<ThirdParty>> splitOnStore(StoreContext<ThirdPartiesResult> context) {
            return context.getPayload().getThirdParties().stream()
                    .map(tp -> StoreContext.<ThirdParty>builder()
                            .failover(context.getFailover())
                            .args(List.of(tp.getId()))
                            .payload(tp)
                            .build())
                    .toList();
        }

        @Override
        public List<RecoverContext<ThirdParty>> splitOnRecover(RecoverContext<ThirdPartiesResult> context) {
            return List.of(RecoverContext.<ThirdParty>builder()
                    .failover(context.getFailover())
                    .args(context.getArgs())
                    .clazz(ThirdParty.class)
                    .cause(context.getCause())
                    .build());
        }

        @Override
        public RecoverContext<ThirdPartiesResult> merge(List<RecoverContext<ThirdParty>> contexts) {
            List<ThirdParty> parties = contexts.stream()
                    .map(RecoverContext::getPayload)
                    .toList();
            ThirdPartiesResult merged = new ThirdPartiesResult();
            merged.setThirdParties(parties);
            String compositeIds = parties.stream()
                    .map(tp -> String.valueOf(tp.getId()))
                    .collect(Collectors.joining(","));
            return RecoverContext.<ThirdPartiesResult>builder()
                    .failover(contexts.getFirst().getFailover())
                    .args(List.of(compositeIds))
                    .clazz(ThirdPartiesResult.class)
                    .cause(contexts.getFirst().getCause())
                    .payload(merged)
                    .build();
        }
    }
}