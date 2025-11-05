package com.walpro.autobuilder.core.lifecycle;

public record Signal(
        String name,
        double strength,
        double decayPerCycle,
        int age,
        String source
) {

    public static Signal of(String name, double strength, double decayPerCycle, String source) {
        return new Signal(name, strength, decayPerCycle, 0, source);
    }

    /** One cycle passes — apply decay and increase age */
    public Signal tick() {
        double newStrength = strength * Math.exp(-decayPerCycle);
        return new Signal(name, newStrength, decayPerCycle, age + 1, source);
    }

    public boolean isAlive(double threshold) {
        return strength > threshold;
    }

    /** Merge or reinforce another signal of the same kind */
    public Signal reinforce(Signal other) {
        if (!this.name.equals(other.name)) return this;
        double combined = Math.min(1.0, (this.strength + other.strength) / 2.0);
        double avgDecay = (this.decayPerCycle + other.decayPerCycle) / 2.0;
        return new Signal(name, combined, avgDecay, 0, source);
    }

    /** Symbolic mutation → new emergent semantics */
    public Signal mutate(String symbol) {
        String mutated = name + symbol;
        return new Signal(mutated, strength * 0.9, decayPerCycle, 0, "mutation");
    }
}

