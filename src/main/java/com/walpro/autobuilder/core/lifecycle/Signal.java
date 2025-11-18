package com.walpro.autobuilder.core.lifecycle;

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
                merge(meta, other.meta)
        );
    }
    private static Map<String,Object> merge(Map<String,Object> a, Map<String,Object> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        var m = new java.util.HashMap<>(a);
        m.putAll(b);
        return m;
    }

    /** Symbolic mutation → new emergent semantics */
    public Signal mutate(String symbol) {
        String mutated = type + symbol;
        return new Signal(new SignalType(mutated), strength * 0.9, decayPerCycle, 0, "mutation", Map.of());
    }
}

