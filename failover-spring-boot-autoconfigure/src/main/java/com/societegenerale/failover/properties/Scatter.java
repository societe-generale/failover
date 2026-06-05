package com.societegenerale.failover.properties;

import lombok.Getter;
import lombok.Setter;

/**
 * @author Anand Manissery
 */
@Getter
@Setter
public class Scatter {
    /**
     * When payload splitter provided, it enables scatter/gather mode where the payload is split into multiple slices and processed in parallel (if enabled).
     * The 'parallel' property indicates whether the failover execution for the slices should be performed in parallel or sequentially.
     * By default, it is set to true, meaning that the failover execution will be performed in parallel. If set to false, the failover execution will be performed sequentially.
     */
    private boolean parallel = true;
}
