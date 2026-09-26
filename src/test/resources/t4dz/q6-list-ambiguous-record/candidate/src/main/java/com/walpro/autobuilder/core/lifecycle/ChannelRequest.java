package com.walpro.autobuilder.core.lifecycle;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public record ChannelRequest(
        SignalType kind,
        double urgency,
        String source,
        Integer validForCycles,
        int age,
        Map<String, Object> meta
) {

    public static ChannelRequest of(SignalType kind, double urgency, String source) {
        return new ChannelRequest(kind, Math.min(urgency, MAX_URGENCY), source, null, 0, null, Map.of());
    }

    public static ChannelRequest of(SignalType kind, double urgency, String source, Integer validForCycles) {
        return new ChannelRequest(kind, Math.min(urgency, MAX_URGENCY), source, null, 0, validForCycles, Map.of());
    }

    public static Integer horizonFromDecay(double strength, double decayPerCycle) {
        if (decayPerCycle <= 0.0) return null;
        return (int) Math.ceil((strength - SURVIVAL_THRESHOLD) / decayPerCycle - 1e-9);
    }

    public ChannelRequest withMeta(String key, Object value) {
        Map<String, Object> updated = new HashMap<>(meta != null ? meta : Map.of());
        updated.put(key, value);
        return new ChannelRequest(kind, urgency, source, subGoalId, age, validForCycles, Map.copyOf(updated));
    }

    public ChannelRequest withoutMeta(String key) {
        if (!meta.containsKey(key)) {
            return this;
        }
        Map<String, Object> next = new LinkedHashMap<>(meta);
        next.remove(key);
        return new ChannelRequest(kind, urgency, source, validForCycles, age, next);
    }

    public ChannelRequest raise(ChannelRequest other) {
        if (!kind.equals(other.kind)) return this;
        Integer validity = (validForCycles == null || other.validForCycles == null)
                ? null
                : Math.max(validForCycles, other.validForCycles);
        return new ChannelRequest(
                kind,
                Math.min(urgency + other.urgency, MAX_URGENCY),
                source,
                subGoalId != null ? subGoalId : other.subGoalId,
                Math.min(age, other.age),
                validity,
                mergeMeta(meta, other.meta, source, other.source)
        );
    }

    public ChannelRequest tick() {
        return new ChannelRequest(kind, urgency, source, subGoalId, age + 1, validForCycles, meta);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean expired() {
        return validForCycles != null && age > validForCycles;
    }

    private static Integer mergeValidForCycles(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    private static Map<String, Object> mergeMeta(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = new LinkedHashMap<>(a);
        out.putAll(b);
        return out;
    }

    private static void addSources(LinkedHashSet<String> out, Map<String, Object> meta) {
        if (meta == null) return;
        Object v = meta.get("sources");
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        }
    }

    public static final double MAX_URGENCY = 3.0;

    private static final double SURVIVAL_THRESHOLD = 0.05;

    private static Map<String, Object> mergeMeta(Map<String, Object> a, Map<String, Object> b,
                                                 String sourceA, String sourceB) {
        Map<String, Object> merged = new HashMap<>(a != null ? a : Map.of());
        if (b != null && !b.isEmpty()) {
            for (Map.Entry<String, Object> e : b.entrySet()) {
                if ("sources".equals(e.getKey())) continue;
                merged.put(e.getKey(), e.getValue());
            }
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        addSources(sources, a);
        addSources(sources, b);
        if (sourceA != null && !sourceA.isBlank()) sources.add(sourceA);
        if (sourceB != null && !sourceB.isBlank()) sources.add(sourceB);
        merged.put("sources", new ArrayList<>(sources));
        return Map.copyOf(merged);
    }
}