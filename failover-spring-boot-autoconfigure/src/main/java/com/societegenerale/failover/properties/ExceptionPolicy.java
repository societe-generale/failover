package com.societegenerale.failover.properties;

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
