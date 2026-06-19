package com.societegenerale.failover.core.payload.splitter;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Template-method base class for {@link PayloadSplitter} implementations that removes the
 * boilerplate of building {@link StoreContext} / {@link RecoverContext} slices and of merging
 * recovered slices back into a composite result.
 *
 * <p>The three public {@code PayloadSplitter} operations are implemented here as fixed templates;
 * subclasses supply only the four domain-specific hooks below. This keeps every concrete splitter
 * focused on <em>what</em> a slice key is and <em>how</em> a composite is taken apart and put back
 * together, never on the surrounding context plumbing.
 *
 * <table class="striped">
 *   <caption>Public operation &rarr; subclass hook(s) it delegates to</caption>
 *   <thead><tr><th>Public operation</th><th>Delegates to</th><th>Purpose</th></tr></thead>
 *   <tbody>
 *     <tr><td>{@link #splitOnStore(StoreContext)}</td>
 *         <td>{@link #splitIntoSlices(Object)} then {@link #keyArgsForSlice(Object, StoreContext)} per slice</td>
 *         <td>Break the composite payload into slices and derive each slice's key args.</td></tr>
 *     <tr><td>{@link #splitOnRecover(RecoverContext)}</td>
 *         <td>{@link #keyArgsToRecover(List, RecoverContext)}</td>
 *         <td>Turn the aggregate method args into one arg-group (hence one {@code recoverAll}/{@code find} call) per slice.</td></tr>
 *     <tr><td>{@link #merge(List)}</td>
 *         <td>{@link #mergeSlices(List, List)}</td>
 *         <td>Re-assemble the recovered slices into the composite payload and aggregate args.</td></tr>
 *   </tbody>
 * </table>
 *
 * <p><b>Slice-key contract.</b> The args produced by {@link #keyArgsForSlice} on the store path and the
 * args produced by {@link #keyArgsToRecover} on the recover path must derive the
 * <em>same store key</em> for the same entity. If they diverge, a slice is stored under one key and
 * looked up under another, so recovery silently returns nothing.
 *
 * <p>For the common {@code List<R>} composite, prefer the ready-made
 * {@link AbstractListPayloadSplitter} which fixes {@code T = List<R>} and supplies sensible defaults
 * for the store split and merge.
 *
 * @param <T> composite type — the type seen by the annotated method (e.g. {@code List<Country>})
 * @param <R> slice type — the type stored/recovered per individual entry (e.g. {@code Country})
 * @author Anand Manissery
 * @see PayloadSplitter
 * @see AbstractListPayloadSplitter
 * @see MergeResult
 */
@RequiredArgsConstructor
public abstract class AbstractPayloadSplitter<T, R> implements PayloadSplitter<T, R> {

    /** Composite type; stamped onto the merged {@link RecoverContext} returned by {@link #merge}. */
    private final Class<T> clazzT;

    /** Slice type; stamped onto every per-slice {@link RecoverContext} so the delegate recovers the right type. */
    private final Class<R> clazzR;

    /**
     * {@inheritDoc}
     *
     * <p>Template: splits the composite payload via {@link #splitIntoSlices(Object)}, then for
     * each slice builds a {@link StoreContext} whose args come from
     * {@link #keyArgsForSlice(Object, StoreContext)}. The original {@link com.societegenerale.failover.annotations.Failover}
     * is carried through unchanged so every slice stores under the same domain/expiry config.
     */
    @Override
    public List<StoreContext<R>> splitOnStore(StoreContext<T> context) {
        return splitIntoSlices(context.getPayload()).stream().map(payload -> StoreContext.<R>builder()
                .failover(context.getFailover())
                .args(keyArgsForSlice(payload, context))
                .payload(payload)
                .build())
            .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Template: turns the aggregate method args into one arg-group per slice via
     * {@link #keyArgsToRecover(List, RecoverContext)}, then builds one
     * {@link RecoverContext} per group. Each context is stamped with the slice type {@code R} (so the
     * delegate recovers the correct type) and carries the original failover and cause through.
     */
    @Override
    public List<RecoverContext<R>> splitOnRecover(RecoverContext<T> context) {
        List<List<Object>> compositeArgs = keyArgsToRecover(context.getArgs(), context);
        return compositeArgs.stream().map(args -> RecoverContext.<R>builder()
                .failover(context.getFailover())
                .args(args)
                .clazz(clazzR)
                .cause(context.getCause())
                .build())
            .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Template: collects the recovered payload and args of every slice (preserving order), hands
     * them to {@link #mergeSlices(List, List)}, and wraps the resulting {@link MergeResult}
     * in a composite {@link RecoverContext} stamped with the composite type {@code T}. The failover
     * and cause are taken from the first slice.
     *
     * <p>Slice payloads may be {@code null} (cache miss / expired / timed-out slice). The null policy
     * — keep positionally, drop, or reject the whole composite — belongs to
     * {@link #mergeSlices(List, List)}, not here. The framework only short-circuits to
     * {@code null} when the slice list is empty, in which case this method is not called.
     *
     * @param recoverContexts per-slice contexts after each slice's payload has been recovered; never
     *                        empty (the framework skips {@code merge} on an empty list)
     */
    @Override
    public RecoverContext<T> merge(List<RecoverContext<R>> recoverContexts) {
        List<R> payloads = new ArrayList<>();
        List<List<Object>> args = new ArrayList<>();
        recoverContexts.forEach(ctx -> {
            payloads.add(ctx.getPayload());
            args.add(ctx.getArgs());
        });
        var mergeResult = mergeSlices(payloads, args);
        return RecoverContext.<T>builder()
                .failover(recoverContexts.getFirst().getFailover())
                .payload(mergeResult.getPayload())
                .args(mergeResult.getArgs())
                .clazz(clazzT)
                .cause(recoverContexts.getFirst().getCause())
                .build();
    }

    /**
     * Derives the store-key args for a single slice on the <b>store</b> path.
     *
     * <p>The returned list is fed to the configured {@code KeyGenerator}, so it must produce the same
     * key that a direct single-entity call would (e.g. {@code List.of(payload.getId())}). It must
     * also match what {@link #keyArgsToRecover(List, RecoverContext)} produces per slice
     * on the recover path, otherwise stored and recovered keys diverge.
     *
     * @param payload the individual slice being stored
     * @param context the composite store context (full payload + original method args)
     * @return key args identifying {@code payload}; typically a single-element list of the entity id
     */
    protected abstract List<Object> keyArgsForSlice(R payload, StoreContext<T> context);

    /**
     * Breaks the composite payload into individual slices on the <b>store</b> path.
     *
     * <p>One {@link StoreContext} is built per returned slice. For a {@code List<R>} composite this is
     * the identity (each element is a slice) — see
     * {@link AbstractListPayloadSplitter#splitIntoSlices(List)}.
     *
     * @param payload the composite payload returned by the annotated method
     * @return the slices to store individually, in order
     */
    protected abstract List<R> splitIntoSlices(T payload);

    /**
     * Splits the aggregate method args into one arg-group per slice on the <b>recover</b> path.
     *
     * <p>Each returned {@code List<Object>} drives exactly one recover/find call on the slice delegate
     * and must derive the same key {@link #keyArgsForSlice(Object, StoreContext)} produced when storing.
     *
     * <p>Shapes by scenario (assuming the slice key is the entity id):
     * <ul>
     *   <li><b>{@code findAll()} — no args:</b> return a single group {@code List.of(args)} (the
     *       default in {@link AbstractListPayloadSplitter}); the delegate recovers all slices by name.</li>
     *   <li><b>{@code findByIdsIn(List<Long> ids)}:</b> return one group per id —
     *       {@code ids.stream().map(List::of).toList()}.</li>
     *   <li><b>{@code findByStringIdsIn(String csv)}:</b> split the CSV, then one group per id.</li>
     *   <li><b>with extra filter args (active, region, ...):</b> ignore the filters for keying — they
     *       are not entity identity — and split only the id portion.</li>
     * </ul>
     *
     * @param args    the original aggregate method args (possibly empty for {@code findAll()})
     * @param context the composite recover context (failover, args, cause)
     * @return one arg-group per slice to recover; an empty list suppresses recovery
     */
    protected abstract List<List<Object>> keyArgsToRecover(List<Object> args, RecoverContext<T>  context);

    /**
     * Re-assembles the recovered slices into the composite payload on the <b>recover</b> path.
     *
     * <p>Owns the <b>null/partial-recovery policy</b>: a slice payload may be {@code null} (cache
     * miss, expired, or timed-out). Choose to keep nulls positionally, drop them, deduplicate, or
     * reject the whole composite — this method has the domain knowledge to decide. It also owns how
     * the per-slice args are aggregated back into the composite args.
     *
     * @param payloads recovered slice payloads in slice order; entries may be {@code null}
     * @param args     the per-slice arg-groups in the same order as {@code payloads}
     * @return the merged composite payload plus aggregated args, wrapped in a {@link MergeResult}
     */
    protected abstract MergeResult<T> mergeSlices(List<R> payloads, List<List<Object>> args);

}
