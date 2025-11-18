package com.walpro.autobuilder.core.lifecycle;

public final class BasicSignals {

    // --- Role activation signals (backward compatible) ---
    public static final SignalType ROLE_PLANNER    = SignalType.of("ROLE_PLANNER");
    public static final SignalType ROLE_EVOLUTION  = SignalType.of("ROLE_EVOLUTION");
    public static final SignalType ROLE_EVALUATION = SignalType.of("ROLE_EVALUATION");
    public static final SignalType ROLE_MERGE      = SignalType.of("ROLE_MERGE");

    // --- Minimal semantic signals (new) ---

    /** Indicates that evolution produced changes. */
    public static final SignalType EVOLUTION_OCCURRED = SignalType.of("EVOLUTION_OCCURRED");

    /** Indicates that evaluation found potential issues, weaknesses, or drop in quality. */
    public static final SignalType LOW_CONFIDENCE = SignalType.of("LOW_CONFIDENCE");

    /** Indicates repeated similar outputs or structural stalling. */
    public static final SignalType STAGNATION = SignalType.of("STAGNATION");

    /** A periodic background tick (lightweight heart-beat / idle loop opportunity). */
    public static final SignalType BACKGROUND_TICK = SignalType.of("BACKGROUND_TICK");

    public static final SignalType RECOMBINATION_REQUEST = SignalType.of("RECOMBINATION_REQUEST");

    public static final SignalType REPETITION_DETECTED = SignalType.of("REPETITION_DETECTED");

    private BasicSignals() {}
}
