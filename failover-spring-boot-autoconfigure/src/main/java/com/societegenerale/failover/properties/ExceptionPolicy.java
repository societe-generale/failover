package com.societegenerale.failover.properties;

/**
 * Strategy that governs how the failover framework handles exceptions thrown by
 * the primary method when recovery is unavailable or the stored entry has expired.
 *
 * @author Anand Manissery
 */
public enum ExceptionPolicy {

    /**
     * For re throw the same exception when unable to recover or expired
     */
    RETHROW,

    /**
     * For always suppress the error and return payload / null in case of failover failure or expired
     */
    NEVER_THROW,

    /**
     * For any other custom implementation, please use custom type
     */
    CUSTOM
}
