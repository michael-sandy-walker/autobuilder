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
