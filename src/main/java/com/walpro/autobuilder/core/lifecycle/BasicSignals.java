package com.walpro.autobuilder.core.lifecycle;

import java.util.Set;

public final class BasicSignals {

    // --- Role activation signals (backward compatible) ---
    public static final SignalType ROLE_PLANNER    = SignalType.of("ROLE_PLANNER");
    public static final SignalType ROLE_EVOLUTION  = SignalType.of("ROLE_EVOLUTION");
    public static final SignalType ROLE_EVALUATION = SignalType.of("ROLE_EVALUATION");
    public static final SignalType ROLE_MERGE      = SignalType.of("ROLE_MERGE");

    // --- Minimal semantic signals (new) ---
    public static final SignalType EVOLUTION_OCCURRED      = SignalType.of("EVOLUTION_OCCURRED");
    public static final SignalType LOW_CONFIDENCE          = SignalType.of("LOW_CONFIDENCE");
    public static final SignalType STAGNATION              = SignalType.of("STAGNATION");
    public static final SignalType BACKGROUND_TICK         = SignalType.of("BACKGROUND_TICK");
    public static final SignalType RECOMBINATION_REQUEST   = SignalType.of("RECOMBINATION_REQUEST");
    public static final SignalType REPETITION_DETECTED     = SignalType.of("REPETITION_DETECTED");

    public static final SignalType GOAL_EXHAUSTED     = SignalType.of("GOAL_EXHAUSTED");
    public static final SignalType AGENT_EXHAUSTED     = SignalType.of("AGENT_EXHAUSTED");

    public static final SignalType SYSTEM_PAUSED     = SignalType.of("SYSTEM_PAUSED");

    private BasicSignals() {}

    // Focused per-agent “attention” sets
    public static final Set<SignalType> PLANNER_SIGNALS = Set.of(
            ROLE_PLANNER,
            STAGNATION,
            LOW_CONFIDENCE,
            BACKGROUND_TICK
    );

    public static final Set<SignalType> EVOLUTION_SIGNALS = Set.of(
            ROLE_EVOLUTION,
            STAGNATION,
            RECOMBINATION_REQUEST,
            REPETITION_DETECTED,
            BACKGROUND_TICK
    );

    public static final Set<SignalType> EVALUATION_SIGNALS = Set.of(
            ROLE_EVALUATION,
            EVOLUTION_OCCURRED,
            LOW_CONFIDENCE,
            REPETITION_DETECTED,
            BACKGROUND_TICK
    );

    public static final Set<SignalType> MERGE_SIGNALS = Set.of(
            ROLE_MERGE,
            EVOLUTION_OCCURRED,
            STAGNATION,
            BACKGROUND_TICK
    );

    // Debug / bootstrap: everything
    public static final Set<SignalType> ALL = Set.of(
            ROLE_PLANNER,
            ROLE_EVOLUTION,
            ROLE_EVALUATION,
            ROLE_MERGE,
            EVOLUTION_OCCURRED,
            LOW_CONFIDENCE,
            STAGNATION,
            BACKGROUND_TICK,
            RECOMBINATION_REQUEST,
            REPETITION_DETECTED,
            GOAL_EXHAUSTED,
            AGENT_EXHAUSTED,
            SYSTEM_PAUSED
    );
}