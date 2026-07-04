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
                || t.startsWith(COMPILE_FIX_DESCRIPTION_PREFIX);
    }

    private BlockerTypes() {}
}
