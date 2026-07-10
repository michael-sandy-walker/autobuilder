package com.walpro.autobuilder.core.lifecycle;

import java.util.Map;

/**
 * T4-BZ-5: a rendered readout entry's value carrier — the surviving shape of the former
 * stored-signal record after the mutation machinery (tick/decay/reinforce/attenuate/mutate,
 * strength caps, survival thresholds) was deleted with the store. Only {@link SignalReadout}
 * composes instances now (windows / requests / derived facts / toggles render through it so
 * the prompt and snapshot byte-shapes stay pinned, I7/I9).
 */
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
}
