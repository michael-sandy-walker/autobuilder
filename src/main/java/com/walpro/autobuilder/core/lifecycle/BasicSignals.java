package com.walpro.autobuilder.core.lifecycle;

import java.util.Set;

public final class BasicSignals {

    // --- Role activation signals (backward compatible) ---
    public static final SignalType ROLE_PLANNER    = SignalType.of("ROLE_PLANNER");
    public static final SignalType ROLE_EVOLUTION  = SignalType.of("ROLE_EVOLUTION");
    public static final SignalType ROLE_EVALUATION = SignalType.of("ROLE_EVALUATION");
    public static final SignalType ROLE_MERGE      = SignalType.of("ROLE_MERGE");
    public static final SignalType ROLE_ANALYSIS= SignalType.of("ROLE_ANALYSIS");

    // --- Minimal semantic signals (new) ---
    public static final SignalType EVOLUTION_OCCURRED      = SignalType.of("EVOLUTION_OCCURRED");
    public static final SignalType EVOLUTION_FINGERPRINT_CHANGED = SignalType.of("EVOLUTION_FINGERPRINT_CHANGED");
    public static final SignalType LOW_CONFIDENCE          = SignalType.of("LOW_CONFIDENCE");
    public static final SignalType STAGNATION              = SignalType.of("STAGNATION");
    public static final SignalType BACKGROUND_TICK         = SignalType.of("BACKGROUND_TICK");
    public static final SignalType RECOMBINATION_REQUEST   = SignalType.of("RECOMBINATION_REQUEST"); // legacy: fallback evolution within same cycle, currently reserved for future intra-cycle fallback when engine supports re-running evolution in same cycle again.
    public static final SignalType DIVERGENCE_REQUEST   = SignalType.of("DIVERGENCE_REQUEST"); // "breakout" evolution within new cycle
    public static final SignalType REPETITION_DETECTED     = SignalType.of("REPETITION_DETECTED");
    public static final SignalType EXPLORATION_REQUESTED     = SignalType.of("EXPLORATION_REQUESTED");
    public static final SignalType REPLAN_REQUESTED     = SignalType.of("REPLAN_REQUESTED");
    public static final SignalType PLANNING_THROTTLED = SignalType.of("PLANNING_THROTTLED");
    public static final SignalType SUBGOAL_SCOPE_MISMATCH = SignalType.of("SUBGOAL_SCOPE_MISMATCH");

    public static final SignalType GOAL_EXHAUSTED     = SignalType.of("GOAL_EXHAUSTED");
    public static final SignalType AGENT_EXHAUSTED     = SignalType.of("AGENT_EXHAUSTED");

    public static final SignalType SYSTEM_PAUSED     = SignalType.of("SYSTEM_PAUSED");

    private BasicSignals() {}

    // Focused per-agent “attention” sets
    public static final Set<SignalType> PLANNER_SIGNALS = Set.of(
            ROLE_PLANNER,
            STAGNATION,
            LOW_CONFIDENCE,
            BACKGROUND_TICK,
            PLANNING_THROTTLED
    );

    public static final Set<SignalType> EVOLUTION_SIGNALS = Set.of(
            ROLE_EVOLUTION,
            STAGNATION,
            RECOMBINATION_REQUEST,
            DIVERGENCE_REQUEST,
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
            BACKGROUND_TICK,
            SUBGOAL_SCOPE_MISMATCH
    );

    public static final Set<SignalType> ANALYSIS_SIGNALS = Set.of(
            ROLE_ANALYSIS,
            STAGNATION,
            REPLAN_REQUESTED,
            LOW_CONFIDENCE,
            BACKGROUND_TICK,
            PLANNING_THROTTLED
    );

    // Debug / bootstrap: everything
    public static final Set<SignalType> ALL = Set.of(
            ROLE_PLANNER,
            ROLE_EVOLUTION,
            ROLE_EVALUATION,
            ROLE_MERGE,
            ROLE_ANALYSIS,
            EVOLUTION_OCCURRED,
            EVOLUTION_FINGERPRINT_CHANGED,
            LOW_CONFIDENCE,
            STAGNATION,
            BACKGROUND_TICK,
            RECOMBINATION_REQUEST,
            DIVERGENCE_REQUEST,
            REPETITION_DETECTED,
            EXPLORATION_REQUESTED,
            REPLAN_REQUESTED,
            PLANNING_THROTTLED,
            SUBGOAL_SCOPE_MISMATCH,
            GOAL_EXHAUSTED,
            AGENT_EXHAUSTED,
            SYSTEM_PAUSED
    );
}