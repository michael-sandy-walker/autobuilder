package com.walpro.autobuilder.goal;

/**
 * Structured discriminator for why a goal ended up at {@link GoalStatus#SUBSUMED}, stored on
 * the goal's metadata under the key {@link #METADATA_KEY}. Stored alongside the free-text
 * resolution so consumers that want to branch on the cause (e.g. UI grouping, future closure
 * heuristics) don't have to parse the resolution string.
 */
public enum SubsumptionReason {

    /**
     * The planner retired this goal in favour of replacement subgoals — the replacement work
     * carries the obligation.
     */
    REPLACED,

    /**
     * The goal's purpose was met by external state without it having to ship its own work
     * (e.g. a BUILD_FIX subgoal whose target build cleared without action).
     */
    COVERED;

    public static final String METADATA_KEY = "subsumptionReason";
}
