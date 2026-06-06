/**
 * Scatter/gather splitter contracts and context types.
 *
 * <p>A {@code PayloadSplitter} breaks a composite payload into per-slice
 * {@link com.societegenerale.failover.core.payload.splitter.StoreContext} entries on store and
 * merges recovered {@link com.societegenerale.failover.core.payload.splitter.RecoverContext}
 * slices back into a single value on recovery.
 */
package com.societegenerale.failover.core.payload.splitter;
