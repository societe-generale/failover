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
    // Recover path — all 5 scenarios on the wrapper composite
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("splitOnRecover — all scenarios")
    class RecoverPath {

        @DisplayName("Scenario 1 — findAll(): single placeholder slice typed as the slice class")
        @Test
        void scenario1FindAll() {
            var splitter = new ThirdPartiesResultSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of()).cause(cause).build();

            assertThat(splitter.splitOnRecover(in)).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of()).clazz(ThirdParty.class).cause(cause).build()
            );
        }

        @DisplayName("Scenario 2 — findAllByIdsIn(List<Long>): one slice per id")
        @Test
        void scenario2ByIds() {
            var splitter = new ThirdPartiesResultByIdsSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of(List.of(1L, 2L))).cause(cause).build();

            assertThat(splitter.splitOnRecover(in)).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }

        @DisplayName("Scenario 3 — findAllByIdsInAndActiveAndRegion: split ids, ignore filters")
        @Test
        void scenario3ByIdsAndFilters() {
            var splitter = new ThirdPartiesResultByIdsAndFiltersSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of(List.of(1L, 2L), true, "EU")).cause(cause).build();

            assertThat(splitter.splitOnRecover(in)).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }

        @DisplayName("Scenario 4 — findAllByStringIdsIn(String): split/parse CSV")
        @Test
        void scenario4ByStringIds() {
            var splitter = new ThirdPartiesResultByStringIdsSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of("1, 2 ,3")).cause(cause).build();

            assertThat(splitter.splitOnRecover(in)).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(3L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }

        @DisplayName("Scenario 5 — findAllByStringIdsInAndActiveAndRegion: split/parse CSV, ignore filters")
        @Test
        void scenario5ByStringIdsAndFilters() {
            var splitter = new ThirdPartiesResultByStringIdsAndFiltersSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of("1,2", false, "APAC")).cause(cause).build();

            assertThat(splitter.splitOnRecover(in)).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Defensive arg handling — guards suppress recovery, never throw
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("defensive arg handling — guards return empty, never throw")
    class DefensiveArgHandling {

        @DisplayName("id splitter should return empty (not IndexOutOfBounds) on empty args")
        @Test
        void idEmptyArgs() {
            var splitter = new ThirdPartiesResultByIdsSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of()).cause(cause).build();

            assertThat(splitter.splitOnRecover(in)).isEmpty();
        }

        @DisplayName("CSV splitter should return empty (not NullPointer) on a blank CSV")
        @Test
        void csvBlank() {
            var splitter = new ThirdPartiesResultByStringIdsSplitter();
            var in = RecoverContext.<ThirdPartiesResult>builder().failover(failover).args(List.of("  ")).cause(cause).build();

            assertThat(splitter.splitOnRecover(in)).isEmpty();
        }

        @DisplayName("store should tolerate a null inner list without NullPointer")
        @Test
        void storeNullInnerList() {
            var splitter = new ThirdPartiesResultSplitter();
            var in = StoreContext.<ThirdPartiesResult>builder().failover(failover).args(List.of()).payload(new ThirdPartiesResult(null)).build();

            assertThat(splitter.splitOnStore(in)).isEmpty();
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

        @DisplayName("should drop null slices when re-wrapping (compact policy in mergeSlices)")
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
        protected List<Object> keyArgsForSlice(ThirdParty payload, StoreContext<ThirdPartiesResult> context) {
            return List.of(payload.getId());
        }

        @Override
        protected List<ThirdParty> splitIntoSlices(ThirdPartiesResult payload) {
            if (payload == null || payload.getThirdParties() == null) {
                return List.of();
            }
            return payload.getThirdParties().stream().filter(Objects::nonNull).toList();
        }

        @Override
        protected List<List<Object>> keyArgsToRecover(List<Object> args, RecoverContext<ThirdPartiesResult> context) {
            return List.of(args);
        }

        @Override
        protected MergeResult<ThirdPartiesResult> mergeSlices(List<ThirdParty> payloads, List<List<Object>> args) {
            var result = new ThirdPartiesResult(payloads.stream().filter(Objects::nonNull).toList());
            return MergeResult.<ThirdPartiesResult>builder()
                    .payload(result)
                    .args(args.stream().flatMap(List::stream).toList())
                    .build();
        }
    }

    /** Scenario 2: id-based wrapper splitter — recover split to one group per id. */
    static class ThirdPartiesResultByIdsSplitter extends ThirdPartiesResultSplitter {

        @Override
        protected List<List<Object>> keyArgsToRecover(List<Object> args, RecoverContext<ThirdPartiesResult> context) {
            if (args.isEmpty() || !(args.getFirst() instanceof List<?> ids) || ids.isEmpty()) {
                return List.of();
            }
            return ids.stream().filter(Objects::nonNull).map(List::<Object>of).toList();
        }
    }

    /** Scenario 3: id-based wrapper splitter with filter args — split ids, ignore filters. */
    static class ThirdPartiesResultByIdsAndFiltersSplitter extends ThirdPartiesResultByIdsSplitter {
        // keyArgsToRecover inherited — args.get(0) is the id list; args 1/2 ignored
    }

    /** Scenario 4: CSV wrapper splitter — split CSV, parse to Long. */
    static class ThirdPartiesResultByStringIdsSplitter extends ThirdPartiesResultSplitter {

        @Override
        protected List<List<Object>> keyArgsToRecover(List<Object> args, RecoverContext<ThirdPartiesResult> context) {
            if (args.isEmpty() || !(args.getFirst() instanceof String csv) || csv.isBlank()) {
                return List.of();
            }
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .map(Long::valueOf)
                    .map(List::<Object>of)
                    .toList();
        }
    }

    /** Scenario 5: CSV wrapper splitter with filter args — split/parse CSV, ignore filters. */
    static class ThirdPartiesResultByStringIdsAndFiltersSplitter extends ThirdPartiesResultByStringIdsSplitter {
        // keyArgsToRecover inherited — args.get(0) is the CSV; args 1/2 ignored
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