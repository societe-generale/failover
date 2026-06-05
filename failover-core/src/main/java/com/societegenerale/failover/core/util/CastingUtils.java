package com.societegenerale.failover.core.util;

import lombok.experimental.UtilityClass;

/**
 * Utility for unchecked generic casts needed to bridge raw types at framework boundaries.
 *
 * @author Anand Manissery
 */
@UtilityClass
public class CastingUtils {

    /**
     * Casts {@code payload} to {@code T} without a checked cast warning.
     * Returns {@code null} if {@code payload} is {@code null}.
     *
     * @param <T>     the target type
     * @param payload the object to cast
     * @return the cast object, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T> T cast(Object payload) {
        return  payload == null ? null : (T) payload;
    }
}
