package com.societegenerale.failover.core.payload.splitter;

import java.util.Collection;
import java.util.List;

/**
 * {@link AbstractPayloadSplitter} specialised for the most common scatter/gather shape: a composite
 * payload that is a {@code List<T>} of slices of type {@code T}.
 *
 * <p>Fixes {@code T = List<T>} and {@code R = T} and supplies working defaults for three of the four
 * hooks, so the minimum a subclass must provide is {@link #keyArgsForSlice(Object, StoreContext)} — the
 * slice key. The supplied defaults are:
 * <ul>
 *   <li>{@link #splitIntoSlices(List)} — identity: each list element is one slice.</li>
 *   <li>{@link #keyArgsToRecover(List, RecoverContext)} — a single arg-group
 *       ({@code List.of(args)}), which is correct <b>only for the {@code findAll()} / no-id-args
 *       case</b>. Every id-based scenario must override it.</li>
 *   <li>{@link #mergeSlices(List, List)} — returns the recovered slices as-is (nulls kept
 *       positionally) and flattens the per-slice arg-groups into one aggregate arg list. Override to
 *       deduplicate, drop nulls, or reject partial recoveries.</li>
 * </ul>
 *
 * <p><b>When to override {@link #keyArgsToRecover}:</b>
 * <table class="striped">
 *   <caption>Method shape &rarr; what to override</caption>
 *   <thead><tr><th>Method</th><th>Override needed</th></tr></thead>
 *   <tbody>
 *     <tr><td>{@code findAll()}</td><td>none — default single-group is correct</td></tr>
 *     <tr><td>{@code findByIdsIn(List<Long> ids)}</td><td>split {@code ids} into one group per id</td></tr>
 *     <tr><td>{@code findByIdsInAndActiveAndRegion(List<Long>, Boolean, String)}</td><td>split ids; ignore the filter args for keying</td></tr>
 *     <tr><td>{@code findByStringIdsIn(String csv)}</td><td>split the CSV into one group per id</td></tr>
 *     <tr><td>{@code findByStringIdsInAndActiveAndRegion(String, Boolean, String)}</td><td>split the CSV; ignore the filter args</td></tr>
 *   </tbody>
 * </table>
 *
 * @param <T> slice type — the element type of the {@code List<T>} the annotated method returns
 * @author Anand Manissery
 * @see AbstractPayloadSplitter
 * @see PayloadSplitter
 */
public abstract class AbstractListPayloadSplitter<T> extends AbstractPayloadSplitter<List<T>,T> {

    /**
     * @param clazz the slice type {@code T}; used to stamp every per-slice {@link RecoverContext} so
     *              the delegate recovers the right type
     */
    @SuppressWarnings("unchecked")
    public AbstractListPayloadSplitter(Class<T> clazz) {
        super((Class<List<T>>) (Class<?>) List.class, clazz);
    }

    /**
     * Derives the store-key args for a single slice. The only mandatory hook — typically
     * {@code List.of(payload.getId())}. See {@link AbstractPayloadSplitter#keyArgsForSlice}.
     */
    @Override
    protected abstract List<Object> keyArgsForSlice(T payload, StoreContext<List<T>> context);

    /**
     * {@inheritDoc}
     *
     * <p>Identity split: the composite list <em>is</em> the slice list, so each element becomes one
     * stored slice.
     */
    @Override
    protected List<T> splitIntoSlices(List<T> payloads) {
        return payloads;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Default is for the {@code findAll()} / no-id-args case only:</b> returns a single
     * arg-group wrapping the original args, producing one recover call (the delegate fetches all
     * slices by name). For any id-based method ({@code findByIdsIn}, {@code findByStringIdsIn}, with or
     * without extra filters) <b>override this</b> to return one group per entity id as a
     * {@code List<List<Object>>}.
     */
    @Override
    protected List<List<Object>> keyArgsToRecover(List<Object> args, RecoverContext<List<T>> context) {
        return List.of(args); // only for findAll() use case where there is no args passed. all other use case please override and provide the ids as a List<List<Object>>
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default merge: returns the recovered slices as the composite list (nulls kept positionally)
     * and flattens the per-slice arg-groups into a single aggregate arg list. Override to deduplicate,
     * drop null slices, or reject partial recoveries — see the null-policy discussion on
     * {@link AbstractPayloadSplitter#mergeSlices}.
     */
    @Override
    protected MergeResult<List<T>> mergeSlices(List<T> payloads, List<List<Object>> args) {
        return MergeResult.<List<T>>builder().payload(payloads).args(args.stream().flatMap(Collection::stream).toList()).build();
    }
}
