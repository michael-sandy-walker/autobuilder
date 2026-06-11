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

    private BlockerTypes() {}
}
