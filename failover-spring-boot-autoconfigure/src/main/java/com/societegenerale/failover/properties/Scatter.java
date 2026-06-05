package com.societegenerale.failover.properties;

import lombok.Data;

/**
 * Scatter/gather configuration for the failover framework.
 *
 * <p>When a {@code payloadSplitter} is specified on a {@code @Failover}-annotated method,
 * the framework splits the composite payload into per-slice store entries and gathers
 * them back on recovery. This property controls whether the slice operations run in parallel.
 *
 * @author Anand Manissery
 */
@Data
public class Scatter {

    /**
     * Whether to dispatch scatter slices in parallel using virtual threads.
     *
     * <p>When {@code true} (default), each slice is submitted to the {@code scatterGatherExecutor}
     * concurrently. When {@code false}, slices are processed sequentially on the calling thread.
     */
    private boolean parallel = true;
}
