package com.societegenerale.failover.domain;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class Metadata {

    private final Map<String,String> info = new LinkedHashMap<>();

    public Metadata withInfo(String key, String value) {
        info.put(key,value);
        return this;
    }
}
