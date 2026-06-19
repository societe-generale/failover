package com.societegenerale.failover.core.payload.splitter;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.domain.Referential;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link AbstractPayloadSplitter} for a non-{@code List} composite — a wrapper
 * {@link ThirdPartiesResult} that holds the collection — exercising the store unwrap and the merge
 * re-wrap that {@link AbstractListPayloadSplitter} hides.
 */
@ExtendWith(MockitoExtension.class)
class AbstractPayloadSplitterTest {

    @Mock
    private Failover failover;

    @Mock
    private Throwable cause;

    // ════════════════════════════════════════════════════════════════════════
    // Store path — unwrap the collection out of the composite wrapper
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("splitOnStore — unwrap wrapper into per-slice contexts")
    class StorePath {

        private final AbstractPayloadSplitter<ThirdPartiesResult, ThirdParty> splitter = new ThirdPartiesResultSplitter();

        @DisplayName("should unwrap the wrapped list and emit one slice per element keyed by id")
        @Test
        void shouldUnwrapAndSplit() {
            var wrapper = new ThirdPartiesResult(List.of(buildThirdParty(1L), buildThirdParty(2L)));
            var in = StoreContext.<ThirdPartiesResult>builder().failover(failover).args(List.of()).payload(wrapper).build();

            var slices = splitter.splitOnStore(in);

            assertThat(slices).containsExactly(
                    StoreContext.<ThirdParty>builder().failover(failover).payload(buildThirdParty(1L)).args(List.of(1L)).build(),
                    StoreContext.<ThirdParty>builder().failover(failover).payload(buildThirdParty(2L)).args(List.of(2L)).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Recover path — findAll-style single placeholder, and id-based per-slice
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("splitOnRecover")
    class RecoverPath {

        @DisplayName("findAll-style splitter should return a single placeholder slice typed as the slice class")
        @Test
        void findAllStyleReturnsSinglePlaceholder() {
            var splitter = new ThirdPartiesResultSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of()).cause(cause).build();

            var slices = splitter.splitOnRecover(in);

            assertThat(slices).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of()).clazz(ThirdParty.class).cause(cause).build()
            );
        }

        @DisplayName("id-based splitter should emit one recover slice per id")
        @Test
        void idBasedReturnsOneSlicePerId() {
            var splitter = new ThirdPartiesResultByIdsSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of(List.of(1L, 2L))).cause(cause).build();

            var slices = splitter.splitOnRecover(in);

            assertThat(slices).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Merge path — re-wrap recovered slices into the composite wrapper
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("merge — re-wrap into composite")
    class MergePath {

        private final AbstractPayloadSplitter<ThirdPartiesResult, ThirdParty> splitter = new ThirdPartiesResultSplitter();

        @DisplayName("should re-wrap recovered slices into the wrapper and stamp the composite class")
        @Test
        void shouldRewrapRecoveredSlices() {
            var in = List.of(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).payload(buildThirdParty(1L)).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).payload(buildThirdParty(2L)).cause(cause).build()
            );

            var merged = splitter.merge(in);

            assertThat(merged.getClazz()).isEqualTo(ThirdPartiesResult.class);
            assertThat(merged.getArgs()).containsExactly(1L, 2L);
            assertThat(merged.getPayload().getThirdParties()).containsExactly(buildThirdParty(1L), buildThirdParty(2L));
        }

        @DisplayName("should drop null slices when re-wrapping (compact policy in doMergePayloadAndArgs)")
        @Test
        void shouldDropNullSlicesOnMerge() {
            var in = Arrays.asList(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).payload(buildThirdParty(1L)).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).payload(null).cause(cause).build()
            );

            var merged = splitter.merge(in);

            assertThat(merged.getPayload().getThirdParties()).containsExactly(buildThirdParty(1L));
        }
    }

    // ── test splitter implementations ────────────────────────────────────────

    /** findAll-style wrapper splitter: unwrap on store, single placeholder on recover, re-wrap on merge. */
    static class ThirdPartiesResultSplitter extends AbstractPayloadSplitter<ThirdPartiesResult, ThirdParty> {

        ThirdPartiesResultSplitter() {
            super(ThirdPartiesResult.class, ThirdParty.class);
        }

        @Override
        protected List<Object> payloadArgs(ThirdParty payload, StoreContext<ThirdPartiesResult> context) {
            return List.of(payload.getId());
        }

        @Override
        protected List<ThirdParty> doSplitPayloadOnStore(ThirdPartiesResult payload) {
            return payload.getThirdParties();
        }

        @Override
        protected List<List<Object>> doSplitCompositeArgsOnRecover(List<Object> args, RecoverContext<ThirdPartiesResult> context) {
            return List.of(args);
        }

        @Override
        protected MergeResult<ThirdPartiesResult> doMergePayloadAndArgs(List<ThirdParty> payloads, List<List<Object>> args) {
            var result = new ThirdPartiesResult(payloads.stream().filter(Objects::nonNull).toList());
            return MergeResult.<ThirdPartiesResult>builder()
                    .payload(result)
                    .args(args.stream().flatMap(List::stream).toList())
                    .build();
        }
    }

    /** id-based wrapper splitter: overrides only the recover split to one group per id. */
    static class ThirdPartiesResultByIdsSplitter extends ThirdPartiesResultSplitter {

        @Override
        @SuppressWarnings("unchecked")
        protected List<List<Object>> doSplitCompositeArgsOnRecover(List<Object> args, RecoverContext<ThirdPartiesResult> context) {
            List<Long> ids = (List<Long>) args.getFirst();
            return ids.stream().map(List::<Object>of).toList();
        }
    }

    private ThirdParty buildThirdParty(Long id) {
        return new ThirdParty(id, "TP" + id, (int) (id * 10));
    }

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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThirdPartiesResult extends Referential {
        private List<ThirdParty> thirdParties;
    }
}