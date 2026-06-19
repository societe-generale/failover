package com.societegenerale.failover.core.payload.splitter;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.domain.Referential;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
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

@ExtendWith(MockitoExtension.class)
class AbstractListPayloadSplitterTest {

    @Mock
    private Failover failover;

    @Mock
    private Throwable cause;

    @SuppressWarnings("unchecked")
    private static final Class<List<ThirdParty>> LIST_CLASS = (Class<List<ThirdParty>>) (Class<?>) List.class;

    // ════════════════════════════════════════════════════════════════════════
    // Store path — shared by every scenario (payloadArgs = id, identity split)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("splitOnStore — shared store path")
    class StorePath {

        private final AbstractListPayloadSplitter<ThirdParty> splitter = new ThirdPartyListSplitter(ThirdParty.class);

        @DisplayName("should split the list into one slice per element, each keyed by its id")
        @Test
        void shouldSplitListIntoOneSlicePerElement() {
            var payloads = List.of(buildThirdParty(1L), buildThirdParty(2L), buildThirdParty(3L));
            var in = StoreContext.<List<ThirdParty>>builder().failover(failover).args(List.of()).payload(payloads).build();

            var slices = splitter.splitOnStore(in);

            assertThat(slices).containsExactly(
                    StoreContext.<ThirdParty>builder().failover(failover).payload(buildThirdParty(1L)).args(List.of(1L)).build(),
                    StoreContext.<ThirdParty>builder().failover(failover).payload(buildThirdParty(2L)).args(List.of(2L)).build(),
                    StoreContext.<ThirdParty>builder().failover(failover).payload(buildThirdParty(3L)).args(List.of(3L)).build()
            );
        }

        @DisplayName("should produce an empty slice list for an empty payload")
        @Test
        void shouldProduceEmptySliceListForEmptyPayload() {
            var in = StoreContext.<List<ThirdParty>>builder().failover(failover).args(List.of()).payload(List.of()).build();

            assertThat(splitter.splitOnStore(in)).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 1 — findAll() with zero args (default doSplitCompositeArgsOnRecover)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 1 — findAll() with zero args")
    class FindAllScenario {

        private final AbstractListPayloadSplitter<ThirdParty> splitter = new ThirdPartyListSplitter(ThirdParty.class);

        @DisplayName("should return a single placeholder slice with the original (empty) args for recover-all")
        @Test
        void shouldReturnSinglePlaceholderSlice() {
            var in = RecoverContext.<List<ThirdParty>>builder().failover(failover).args(List.of()).cause(cause).build();

            var slices = splitter.splitOnRecover(in);

            assertThat(slices).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of()).clazz(ThirdParty.class).cause(cause).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 2 — findAllByIdsIn(List<Long> ids)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 2 — findAllByIdsIn(List<Long> ids)")
    class FindAllByIdsInScenario {

        private final AbstractListPayloadSplitter<ThirdParty> splitter = new ThirdPartyByIdsSplitter(ThirdParty.class);

        @DisplayName("should emit one recover slice per id, each keyed by that id")
        @Test
        void shouldEmitOneSlicePerId() {
            var in = RecoverContext.<List<ThirdParty>>builder().failover(failover).args(List.of(List.of(1L, 2L, 3L))).cause(cause).build();

            var slices = splitter.splitOnRecover(in);

            assertThat(slices).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(3L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 3 — findAllByIdsInAndActiveAndRegion(List<Long> ids, Boolean active, String region)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 3 — findAllByIdsInAndActiveAndRegion(List<Long>, Boolean, String)")
    class FindAllByIdsInAndFiltersScenario {

        private final AbstractListPayloadSplitter<ThirdParty> splitter = new ThirdPartyByIdsAndFiltersSplitter(ThirdParty.class);

        @DisplayName("should split only the id list and ignore the active/region filter args for the slice key")
        @Test
        void shouldSplitIdsAndIgnoreFilters() {
            var in = RecoverContext.<List<ThirdParty>>builder().failover(failover).args(List.of(List.of(1L, 2L), true, "EU")).cause(cause).build();

            var slices = splitter.splitOnRecover(in);

            assertThat(slices).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 4 — findAllByStringIdsIn(String commaSeparatedIds)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 4 — findAllByStringIdsIn(String commaSeparatedIds)")
    class FindAllByStringIdsInScenario {

        private final AbstractListPayloadSplitter<ThirdParty> splitter = new ThirdPartyByStringIdsSplitter(ThirdParty.class);

        @DisplayName("should split the CSV and parse each token to Long so the key matches the stored id")
        @Test
        void shouldSplitCsvAndParseToLong() {
            var in = RecoverContext.<List<ThirdParty>>builder().failover(failover).args(List.of("1, 2 ,3")).cause(cause).build();

            var slices = splitter.splitOnRecover(in);

            assertThat(slices).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(3L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 5 — findAllByStringIdsInAndActiveAndRegion(String, Boolean, String)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 5 — findAllByStringIdsInAndActiveAndRegion(String, Boolean, String)")
    class FindAllByStringIdsInAndFiltersScenario {

        private final AbstractListPayloadSplitter<ThirdParty> splitter = new ThirdPartyByStringIdsAndFiltersSplitter(ThirdParty.class);

        @DisplayName("should split/parse the CSV and ignore the active/region filter args")
        @Test
        void shouldSplitCsvAndIgnoreFilters() {
            var in = RecoverContext.<List<ThirdParty>>builder().failover(failover).args(List.of("1,2", false, "APAC")).cause(cause).build();

            var slices = splitter.splitOnRecover(in);

            assertThat(slices).containsExactly(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).clazz(ThirdParty.class).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).clazz(ThirdParty.class).cause(cause).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Merge path — default policy and an overriding policy
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("merge — default and overridden policies")
    class MergePath {

        private final AbstractListPayloadSplitter<ThirdParty> splitter = new ThirdPartyListSplitter(ThirdParty.class);

        @DisplayName("default merge should collect all slices into a list and flatten the per-slice args")
        @Test
        void defaultMergeCollectsAllSlicesAndFlattensArgs() {
            var in = List.of(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).payload(buildThirdParty(1L)).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).payload(buildThirdParty(2L)).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(3L)).payload(buildThirdParty(3L)).cause(cause).build()
            );

            var merged = splitter.merge(in);

            assertThat(merged.getPayload()).containsExactly(buildThirdParty(1L), buildThirdParty(2L), buildThirdParty(3L));
            assertThat(merged).isEqualTo(RecoverContext.<List<ThirdParty>>builder()
                    .failover(failover)
                    .args(List.of(1L, 2L, 3L))
                    .payload(List.of(buildThirdParty(1L), buildThirdParty(2L), buildThirdParty(3L)))
                    .clazz(LIST_CLASS)
                    .cause(cause)
                    .build());
        }

        @DisplayName("default merge should keep a null payload positionally for a missing slice (partial recovery)")
        @Test
        void defaultMergeKeepsNullPositionally() {
            var in = Arrays.asList(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).payload(buildThirdParty(1L)).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).payload(null).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(3L)).payload(buildThirdParty(3L)).cause(cause).build()
            );

            var merged = splitter.merge(in);

            assertThat(merged.getPayload()).containsExactly(buildThirdParty(1L), null, buildThirdParty(3L));
            assertThat(merged.getArgs()).containsExactly(1L, 2L, 3L);
        }

        @DisplayName("overriding doMergePayloadAndArgs should be able to drop null slices (compact policy)")
        @Test
        void overriddenMergeDropsNulls() {
            var dropNullSplitter = new ThirdPartyDropNullSplitter(ThirdParty.class);
            var in = Arrays.asList(
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(1L)).payload(buildThirdParty(1L)).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(2L)).payload(null).cause(cause).build(),
                    RecoverContext.<ThirdParty>builder().failover(failover).args(List.of(3L)).payload(buildThirdParty(3L)).cause(cause).build()
            );

            var merged = dropNullSplitter.merge(in);

            assertThat(merged.getPayload()).containsExactly(buildThirdParty(1L), buildThirdParty(3L));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Round trip — store args feed back as recover keys (slice-key contract)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("round trip — recover keys match store keys")
    class RoundTrip {

        @DisplayName("findAllByIdsIn store args should equal the recover slice args for the same ids")
        @Test
        void storeAndRecoverKeysAlign() {
            var splitter = new ThirdPartyByIdsSplitter(ThirdParty.class);
            var payloads = List.of(buildThirdParty(1L), buildThirdParty(2L));
            var storeIn = StoreContext.<List<ThirdParty>>builder().failover(failover).args(List.of(List.of(1L, 2L))).payload(payloads).build();
            var recoverIn = RecoverContext.<List<ThirdParty>>builder().failover(failover).args(List.of(List.of(1L, 2L))).cause(cause).build();

            var storeArgs = splitter.splitOnStore(storeIn).stream().map(StoreContext::getArgs).toList();
            var recoverArgs = splitter.splitOnRecover(recoverIn).stream().map(RecoverContext::getArgs).toList();

            assertThat(storeArgs).isEqualTo(recoverArgs).containsExactly(List.of(1L), List.of(2L));
        }
    }

    // ── test splitter implementations (one per documented scenario) ──────────

    /** Scenario 1: findAll() — only payloadArgs; default single-group recover split. */
    static class ThirdPartyListSplitter extends AbstractListPayloadSplitter<ThirdParty> {
        ThirdPartyListSplitter(Class<ThirdParty> clazz) {
            super(clazz);
        }

        @Override
        protected List<Object> payloadArgs(ThirdParty payload, StoreContext<List<ThirdParty>> context) {
            return List.of(payload.getId());
        }
    }

    /** Scenario 2: findAllByIdsIn(List<Long>) — split the id list into one group per id. */
    static class ThirdPartyByIdsSplitter extends ThirdPartyListSplitter {
        ThirdPartyByIdsSplitter(Class<ThirdParty> clazz) {
            super(clazz);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected List<List<Object>> doSplitCompositeArgsOnRecover(List<Object> args, RecoverContext<List<ThirdParty>> context) {
            List<Long> ids = (List<Long>) args.getFirst();
            return ids.stream().map(List::<Object>of).toList();
        }
    }

    /** Scenario 3: findAllByIdsInAndActiveAndRegion — split ids, ignore filter args. */
    static class ThirdPartyByIdsAndFiltersSplitter extends ThirdPartyByIdsSplitter {
        ThirdPartyByIdsAndFiltersSplitter(Class<ThirdParty> clazz) {
            super(clazz);
        }
        // doSplitCompositeArgsOnRecover inherited — args.get(0) is the id list; args 1/2 ignored
    }

    /** Scenario 4: findAllByStringIdsIn(String) — split CSV, parse to Long. */
    static class ThirdPartyByStringIdsSplitter extends ThirdPartyListSplitter {
        ThirdPartyByStringIdsSplitter(Class<ThirdParty> clazz) {
            super(clazz);
        }

        @Override
        protected List<List<Object>> doSplitCompositeArgsOnRecover(List<Object> args, RecoverContext<List<ThirdParty>> context) {
            String csv = (String) args.getFirst();
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .map(Long::valueOf)
                    .map(List::<Object>of)
                    .toList();
        }
    }

    /** Scenario 5: findAllByStringIdsInAndActiveAndRegion — split/parse CSV, ignore filters. */
    static class ThirdPartyByStringIdsAndFiltersSplitter extends ThirdPartyByStringIdsSplitter {
        ThirdPartyByStringIdsAndFiltersSplitter(Class<ThirdParty> clazz) {
            super(clazz);
        }
        // doSplitCompositeArgsOnRecover inherited — args.get(0) is the CSV; args 1/2 ignored
    }

    /** Overriding merge to drop null slices (compact policy). */
    static class ThirdPartyDropNullSplitter extends ThirdPartyListSplitter {
        ThirdPartyDropNullSplitter(Class<ThirdParty> clazz) {
            super(clazz);
        }

        @Override
        protected MergeResult<List<ThirdParty>> doMergePayloadAndArgs(List<ThirdParty> payloads, List<List<Object>> args) {
            return MergeResult.<List<ThirdParty>>builder()
                    .payload(payloads.stream().filter(Objects::nonNull).toList())
                    .args(args.stream().flatMap(List::stream).toList())
                    .build();
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
}