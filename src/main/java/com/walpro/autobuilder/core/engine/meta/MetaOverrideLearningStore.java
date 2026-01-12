package com.walpro.autobuilder.core.engine.meta;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MetaOverrideLearningStore {

    private final Map<MetaSkipPatternKey, MetaSkipPatternStats> stats = new ConcurrentHashMap<>();

    public void record(MetaSkipPatternKey key) {
        if (key == null) return;
        stats.computeIfAbsent(key, k -> new MetaSkipPatternStats()).inc();
    }

    public List<Map.Entry<MetaSkipPatternKey, MetaSkipPatternStats>> top(int n) {
        return stats.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<MetaSkipPatternKey, MetaSkipPatternStats> e) -> e.getValue().count()).reversed())
                .limit(Math.max(0, n))
                .toList();
    }
}
