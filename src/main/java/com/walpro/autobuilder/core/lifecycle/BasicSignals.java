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
    public static final SignalType RECOMBINATION_REQUEST   = SignalType.of("RECOMBINATION_REQUEST"); // legacy: fallback evolution within same cycle, currently reserved for future intra-cycle fallback when engine supports re-running evolution in same cycle again.
    public static final SignalType DIVERGENCE_REQUEST   = SignalType.of("DIVERGENCE_REQUEST"); // "breakout" evolution within new cycle
    public static final SignalType REPETITION_DETECTED     = SignalType.of("REPETITION_DETECTED");
    public static final SignalType REPLAN_REQUESTED     = SignalType.of("REPLAN_REQUESTED");
    public static final SignalType PLANNING_THROTTLED = SignalType.of("PLANNING_THROTTLED");
    public static final SignalType SUBGOAL_SCOPE_MISMATCH = SignalType.of("SUBGOAL_SCOPE_MISMATCH");
    public static final SignalType SCOPE_OUT_OF_SCOPE = SignalType.of("SCOPE_OUT_OF_SCOPE");

    // T2-AP: emitted after K consecutive accepted mutations with a flat score band (|Δ| < 0.03)
    public static final SignalType SCORE_PLATEAU      = SignalType.of("SCORE_PLATEAU");

    // T2-R: emitted by ReplanPatternDetector when planner fingerprint repetition is a leading indicator of thrash
    public static final SignalType REPLAN_THRASH_DETECTED = SignalType.of("REPLAN_THRASH_DETECTED");

    public static final SignalType SUBGOAL_EXHAUSTED  = SignalType.of("SUBGOAL_EXHAUSTED");
    public static final SignalType GOAL_EXHAUSTED     = SignalType.of("GOAL_EXHAUSTED");

    public static final SignalType SYSTEM_PAUSED     = SignalType.of("SYSTEM_PAUSED");

    // T2-D: emitted by AnalysisAgent when it believes the current goal may already be complete;
    // triggers a human-review pause instead of continued cycling.
    public static final SignalType POTENTIALLY_COMPLETE = SignalType.of("POTENTIALLY_COMPLETE");

    // T2-CC: emitted by EvolutionAgent when the LLM responded with #NoDiff (implementation already present).
    // AutobuilderService checks this after runCycle() and promotes to FULFILLED if all criteria are met.
    public static final SignalType EVOLUTION_NOOP = SignalType.of("EVOLUTION_NOOP");

    // T1-DU: emitted by ContextLineageTracker when BuildOutcome.result() == COMPILE_ERROR is carried.
    // decayPerCycle=0.0 (only cleared on COMPILE_OK by MergeAgent, never by age) and strength=1.0.
    // Boosts evolution/merge fitness against the active BUILD_FIX subgoal; depresses planner/evaluation.
    public static final SignalType BUILD_BROKEN = SignalType.of("BUILD_BROKEN");

    // T4-BN-3: emitted by ContextLineageTracker when the goal spec requires tests AND the
    // resolved build state (TestOnlyCompileFailure.resolveOutcome — same ground truth
    // BuildResultPromptBlock renders) is a compile failure confined to test sources (main
    // compiles). Gated on spec-requires-tests because BrokenTestQuarantine already
    // deterministically resolves this trap when the spec does not require tests.
    // decayPerCycle=0.0 (re-derived every cycle from build state alone, same shape as
    // BUILD_BROKEN). Drives EvolutionProtocolContract's focused test-repair prompt path.
    public static final SignalType TEST_REPAIR_FOCUS = SignalType.of("TEST_REPAIR_FOCUS");

    // T2-DC: emitted by SymbolGroundingService when an analysis or planner LLM citation names a
    // Java identifier that has no class|interface|record|enum declaration anywhere under output/
    // and is not in the JDK/framework allowlist. Source string carries the symbol name so the
    // planner can frame the next subgoal as a creation task rather than as a reference.
    public static final SignalType MISSING_SYMBOL = SignalType.of("MISSING_SYMBOL");

    // T2-DD: emitted by ContextLineageTracker when ≥2 consecutive rollbacks share the same
    // (errorKind, primaryRolledBackPath). Drives BlockerSubgoalInjector to produce a divergence
    // subgoal (create-the-missing-artifact OR remove-the-dangling-reference) instead of another
    // same-locus retry, and surfaces as a hard constraint in the planner protocol contract.
    public static final SignalType BUILD_FIX_LOCUS_STUCK = SignalType.of("BUILD_FIX_LOCUS_STUCK");

    // T2-EF: emitted by BuildBlockGate when RecurringDefectTracker reports a failure shape that
    // dominates the run's residual failure mix across ≥2 distinct subgoals — the cross-subgoal
    // recurrence the per-subgoal BUILD_FIX_LOCUS_STUCK streak resets away. Forces the targeted
    // build-fix redirection even when sameFailureStreak < 2. Source carries errorKind|token|share.
    public static final SignalType RECURRING_DEFECT_DOMINANT = SignalType.of("RECURRING_DEFECT_DOMINANT");

    // T1-EI: emitted by GoalPlannerService when a GIVEN_UP goal has been rehabilitated and
    // re-given-up at least N times (default 2) without an intervening merge. Forces the
    // planner to propose at a different abstraction level or remove+replace instead of
    // re-attempting the same line of work.
    public static final SignalType IRREDUCIBLE_GAP = SignalType.of("IRREDUCIBLE_GAP");

    // T4-BE: meta-agent opt-out for the deferred-mutation restage scheduler. Set on context
    // when the meta-agent has reasons not to fire a ready candidate this cycle (system paused,
    // intent goal closure in flight, deliberation has a higher priority). Absence of the signal
    // is the default green light — suppression is an active choice.
    public static final SignalType RESTAGE_SUPPRESS = SignalType.of("RESTAGE_SUPPRESS");

    // T2-DT: emitted by EvolutionAgent when the PREVIOUS COMPILE ERRORS prompt block fires —
    // i.e. the last cycle's compile probe rolled back a mutation with author-error diagnostics
    // and the active subgoal is unchanged. Surfaces "we are steering on real diagnostics this
    // cycle" to deliberation + behaviour log without re-parsing the prompt text.
    public static final SignalType COMPILE_DIAGNOSTIC_FED_BACK = SignalType.of("COMPILE_DIAGNOSTIC_FED_BACK");

    // T2-DT: meta-agent opt-out for the compile-diagnostic steering block. Set on context when
    // the meta-agent considers the diagnostics irrelevant (e.g. firing on a different concern than
    // the current focus). Suppresses the block + signal; emits evolution_steering_suppressed.
    public static final SignalType STEERING_SUPPRESS = SignalType.of("STEERING_SUPPRESS");

    private BasicSignals() {}

    // Focused per-agent “attention” sets
    public static final Set<SignalType> PLANNER_SIGNALS = Set.of(
            ROLE_PLANNER,
            STAGNATION,
            LOW_CONFIDENCE,
            PLANNING_THROTTLED,
            REPLAN_REQUESTED,
            REPLAN_THRASH_DETECTED,
            SCOPE_OUT_OF_SCOPE,
            MISSING_SYMBOL
    );

    public static final Set<SignalType> EVOLUTION_SIGNALS = Set.of(
            ROLE_EVOLUTION,
            STAGNATION,
            RECOMBINATION_REQUEST,
            DIVERGENCE_REQUEST,
            REPETITION_DETECTED,
            SCORE_PLATEAU,
            BUILD_BROKEN,
            TEST_REPAIR_FOCUS
    );

    public static final Set<SignalType> EVALUATION_SIGNALS = Set.of(
            ROLE_EVALUATION,
            EVOLUTION_OCCURRED,
            LOW_CONFIDENCE,
            REPETITION_DETECTED
    );

    public static final Set<SignalType> MERGE_SIGNALS = Set.of(
            ROLE_MERGE,
            EVOLUTION_OCCURRED,
            STAGNATION,
            SUBGOAL_SCOPE_MISMATCH,
            BUILD_BROKEN
    );

    public static final Set<SignalType> ANALYSIS_SIGNALS = Set.of(
            ROLE_ANALYSIS,
            STAGNATION,
            REPLAN_REQUESTED,
            LOW_CONFIDENCE,
            PLANNING_THROTTLED,
            POTENTIALLY_COMPLETE,
            MISSING_SYMBOL,
            BUILD_BROKEN // T2-DE: analysis is the diagnostic escape hatch when build is broken
    );

    // T2-BK: signals that describe per-subgoal history and must be isolated between subgoals.
    // Persisted to GoalEntity.metadata["subgoalSignals"] at end of each cycle and restored
    // from there at the start of the next, so subgoal switches automatically get a clean slate.
    public static final Set<SignalType> SUBGOAL_SCOPED_SIGNALS = Set.of(
            STAGNATION, REPETITION_DETECTED, DIVERGENCE_REQUEST, LOW_CONFIDENCE, SCORE_PLATEAU
    );

    // Debug / bootstrap: everything
    public static final Set<SignalType> ALL = Set.of(
            REPLAN_THRASH_DETECTED,
            ROLE_PLANNER,
            ROLE_EVOLUTION,
            ROLE_EVALUATION,
            ROLE_MERGE,
            ROLE_ANALYSIS,
            EVOLUTION_OCCURRED,
            EVOLUTION_FINGERPRINT_CHANGED,
            LOW_CONFIDENCE,
            STAGNATION,
            RECOMBINATION_REQUEST,
            DIVERGENCE_REQUEST,
            REPETITION_DETECTED,
            REPLAN_REQUESTED,
            PLANNING_THROTTLED,
            SUBGOAL_SCOPE_MISMATCH,
            SCOPE_OUT_OF_SCOPE,
            SCORE_PLATEAU,
            SUBGOAL_EXHAUSTED,
            GOAL_EXHAUSTED,
            SYSTEM_PAUSED,
            POTENTIALLY_COMPLETE,
            BUILD_BROKEN,
            TEST_REPAIR_FOCUS,
            MISSING_SYMBOL
    );
}