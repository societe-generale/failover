package com.societegenerale.failover.domain;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key-value bag for additional metadata attached to recovered failover payloads.
 * Entries are added via {@link #withInfo(String, String)} and preserved in insertion order.
 *
 * @author Anand Manissery
 */
@Data
public class Metadata {

    private final Map<String,String> info = new LinkedHashMap<>();

    /**
     * Adds a metadata entry and returns {@code this} for chaining.
     *
     * @param key   the metadata key
     * @param value the metadata value
     * @return this instance
     */
    public Metadata withInfo(String key, String value) {
        info.put(key,value);
        return this;
    }
}
