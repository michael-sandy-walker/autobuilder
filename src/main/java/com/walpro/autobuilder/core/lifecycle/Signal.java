package com.walpro.autobuilder.core.lifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record Signal(
        SignalType type,
        double strength,
        double decayPerCycle,
        int age,
        String source,
        Map<String, Object> meta
) {
    public static Signal of(SignalType type, double strength, double decayPerCycle, String source) {
        return new Signal(type, strength, decayPerCycle, 0, source, Map.of());
    }
    public Signal tick() {
        if (age == 0) {
            return new Signal(type, strength, decayPerCycle, age + 1, source, meta);
        }
        return new Signal(type, Math.max(0, strength - decayPerCycle), decayPerCycle, age + 1, source, meta);
    }

    public boolean isAlive(double threshold) {
        return strength > threshold;
    }

    public Signal reinforce(Signal other) {
        if (!type.equals(other.type)) return this;
        return new Signal(
                type,
                strength + other.strength,
                Math.min(decayPerCycle, other.decayPerCycle),
                Math.min(age, other.age),
                source,
                merge(meta, other.meta, source, other.source)
        );
    }
    private static Map<String,Object> merge(Map<String,Object> a, Map<String,Object> b, String sourceA, String sourceB) {
        Map<String, Object> merged = new HashMap<>(a != null ? a : Map.of());

        // merge other meta first (but handle sources specially)
        if (b != null && !b.isEmpty()) {
            for (Map.Entry<String, Object> e : b.entrySet()) {
                if ("sources".equals(e.getKey())) continue; // handled below
                merged.put(e.getKey(), e.getValue());
            }
        }

        merged.put("sources", mergeSources(a, b, sourceA, sourceB));
        return Map.copyOf(merged);
    }

    private static List<String> mergeSources(
            Map<String, Object> a,
            Map<String, Object> b,
            String sourceA,
            String sourceB
    ) {
        LinkedHashSet<String> out = new LinkedHashSet<>();

        // 1) sources from meta(a)
        addSourcesFromMeta(out, a);

        // 2) sources from meta(b)
        addSourcesFromMeta(out, b);

        // 3) explicit sources from records
        if (sourceA != null && !sourceA.isBlank()) out.add(sourceA);
        if (sourceB != null && !sourceB.isBlank()) out.add(sourceB);

        return new ArrayList<>(out);
    }

    private static void addSourcesFromMeta(LinkedHashSet<String> out, Map<String, Object> meta) {
        if (meta == null) return;
        Object v = meta.get("sources");
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        }
    }

    /** Symbolic mutation → new emergent semantics */
//    public Signal mutate(String symbol) {
//        String mutated = type.name() + symbol;
//        return new Signal(new SignalType(mutated), strength * 0.9, decayPerCycle, 0, "mutation", Map.of());
//    }
    public Signal mutate(String symbol) {
        return new Signal(type, strength * 0.9, decayPerCycle, 0, "mutation",
                Map.of("symbol", symbol));
    }
}

