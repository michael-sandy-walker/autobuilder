package com.walpro.autobuilder.core.build;

/**
 * T1-DN / T5-K: shared constants identifying deterministic blocker-fix subgoals injected
 * by agents rather than proposed by the LLM planner.
 */
public final class BlockerTypes {
    public static final String METADATA_KEY = "blockerType";
    public static final String BUILD_FIX = "BUILD_FIX";

    /** Description prefix the planner prompt mandates for build-fix subgoals; creation-time
     *  stamping in {@code GoalPlannerService.addSubGoal} maps it to the metadata tag above. */
    public static final String BUILD_FIX_DESCRIPTION_PREFIX = "[BUILD_FIX]";

    /** Description prefix of divergence instructions ({@code BlockerSubgoalInjector} T2-DD/T2-EF). */
    public static final String DIVERGENCE_DESCRIPTION_PREFIX = "Diverge — ";

    /** Description prefix of plain compile-fix instructions ({@code BlockerSubgoalInjector} T1-DM). */
    public static final String COMPILE_FIX_DESCRIPTION_PREFIX = "Fix compile error";

    /**
     * T2-EQ: description prefix for a blocker the build tool reported AFTER the sources compiled —
     * the code is valid and the gap is elsewhere, so calling it a compile error misdescribes it to
     * the agent that has to act (observed 2026-07-27: "Fix compile error … The main class cannot be
     * found", which the model correctly refused as false).
     */
    public static final String BUILD_FIX_TOOL_ERROR_PREFIX = "Fix build error";

    /**
     * T4-CC: goal-metadata key naming the deterministic completion-edge check whose failure
     * created a blocker work item. A mutation on such a work item is accepted by re-running
     * that check against the staged candidate — the check's result takes precedence over the
     * LLM assessment (which falsely rejected the correct packaging fix on 2026-07-16).
     */
    public static final String ORIGINATING_GATE_KEY = "originatingGate";
    public static final String GATE_PACKAGE = "package-gate";
    public static final String GATE_BOOT = "boot-gate";
    public static final String GATE_TEST = "test-gate";

    /**
     * The completion edge's acceptance-criteria check (T4-BP-3). Unlike the gates above, whose
     * work items carry build-status facts, this one carries the SPECIFICATION's own unmet
     * requirements, copied from the set the verifier just refused to confirm. It wears the
     * {@link #BUILD_FIX} tag only because the injection templates share a description prefix, and
     * three decisions read that tag as "build health, owned by a deterministic gate": the
     * COMPILE_OK cleanup retired the item, the closure ledger skipped its criteria, and the
     * grounding stamp marked them AUTHORED. In run {@code 2026-08-30_00-06-00} that retired the
     * multi-line-row work item 48 seconds after it was raised, on a compile observation that
     * says nothing about it, and the root completed at 17/19. Each of those three sites now
     * discriminates on the originating gate rather than on the tag.
     */
    public static final String GATE_CRITERIA = "criteria-gate";

    /**
     * T2-ES: a work item raised because one deterministic heuristic reported the SAME structural
     * defect on consecutive cycles. It wears a gate stamp for the reason the stamp exists — the
     * defect it names compiles perfectly (a parallel store is invisible to javac), so without one
     * the COMPILE_OK cleanup would retire it on the next merge, which is the T2-EQ Cause B defect
     * arriving through a new door. Retired by the heuristic falling silent, or worked like any
     * other subgoal.
     */
    public static final String GATE_STRUCTURAL_DEFECT = "structural-defect";

    /**
     * True when the goal's work item was raised by the completion edge's acceptance-criteria
     * check, whose done-when is a requirement about the target application rather than a fact
     * about the build.
     */
    /**
     * True when the goal's work item was raised by a deterministic gate rather than proposed by
     * the planner — a recorded OBSERVATION of the artifact, not a plan.
     *
     * <p>The distinction decides who may retire it. See the planner's prune loop: a plan can be
     * rewritten, but a failure that was observed cannot be un-observed by rewriting one.
     */
    public static boolean isFromGate(java.util.Map<String, Object> metadata) {
        if (metadata == null) return false;
        Object gate = metadata.get(ORIGINATING_GATE_KEY);
        return gate != null && !String.valueOf(gate).isBlank();
    }

    public static boolean isFromCriteriaGate(java.util.Map<String, Object> metadata) {
        return metadata != null && GATE_CRITERIA.equals(metadata.get(ORIGINATING_GATE_KEY));
    }

    /**
     * True when the text is a framework-generated diagnostic INSTRUCTION (a work order about the
     * build/loop state at the time it was written), as opposed to a requirement about the target
     * application. Instruction texts must never be persisted as acceptance criteria: they describe
     * a past defect ("the type X is missing"), so once the defect is fixed they are unconfirmable
     * against the code forever and block root closure through completion verification (run 9,
     * 2026-07-04: two stale divergence instructions were the artifact's only remaining wall).
     * Matches only the framework's own templates, whose single definitions live above.
     */
    public static boolean isDiagnosticInstruction(String text) {
        if (text == null) return false;
        String t = text.strip();
        return t.startsWith(BUILD_FIX_DESCRIPTION_PREFIX)
                || t.startsWith(DIVERGENCE_DESCRIPTION_PREFIX)
                || t.startsWith(COMPILE_FIX_DESCRIPTION_PREFIX)
                || t.startsWith(BUILD_FIX_TOOL_ERROR_PREFIX);
    }

    private BlockerTypes() {}
}
