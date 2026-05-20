package com.societegenerale.failover.core.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class CastingUtils {

    @SuppressWarnings("unchecked")
    public static <T> T cast(Object payload) {
        return  payload == null ? null : (T) payload;
    }
}
