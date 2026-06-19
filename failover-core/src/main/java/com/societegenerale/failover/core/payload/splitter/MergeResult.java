package com.societegenerale.failover.core.payload.splitter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Value object returned by {@link AbstractPayloadSplitter#doMergePayloadAndArgs(List, List)} carrying
 * the outcome of merging recovered slices back into a composite.
 *
 * <p>It bundles the two things {@link AbstractPayloadSplitter#merge(List)} needs to build the final
 * composite {@link RecoverContext}: the merged {@code payload} and the aggregated {@code args}. Using
 * a single return type keeps the {@code doMergePayloadAndArgs} hook simple — the subclass decides both
 * the payload (applying its null/dedup policy) and the args in one place.
 *
 * @param <T> composite payload type (e.g. {@code List<Country>})
 * @author Anand Manissery
 * @see AbstractPayloadSplitter#doMergePayloadAndArgs(List, List)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeResult<T> {

    /** The merged composite payload — the final recovered value, after any null/dedup policy. */
    private T payload;

    /** The aggregated method args reconstructed from the per-slice arg-groups. */
    private List<Object> args;

}
