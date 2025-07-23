package com.walpro.autobuilder.parser.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MetadataBuilder {
    private final Map<String, Object> map = new HashMap<>();

    public MetadataBuilder put(String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
        return this;
    }

    public Map<String, Object> build() {
        return Collections.unmodifiableMap(map);
    }
}
