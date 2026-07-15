package com.walpro.autobuilder.goal;

/**
 * T1-ET Part B / T5-AC: structured result of
 * {@link GoalPlannerService#markGoalCompleted(String, String)}, as a sealed hierarchy so callers
 * switch exhaustively — adding a fourth completion branch fails compilation at every call site
 * instead of silently falling through (the enum-kind carrier this replaces enforced nothing).
 *
 * <p>The original signature was {@code void} and the T1-DD refusal-and-re-point guard inside it
 * could silently change {@code currentSubGoalId} without any signal back to callers.
 * {@link GoalPlannerService#promoteCurrentSubGoal()} ignored that refusal, fell through to
 * {@code findNextSiblingOrAncestor}, and then unconditionally clobbered the freshly-set
 * {@code currentSubGoalId} (often to null) — the zero-work loop documented in
 * {@code behaviour-2026-05-25_00-31-18.ndjson} (16 cycles in 87 s).
 */
public sealed interface MarkCompletedResult {

    /** The goal id passed in (preserved for logging / debugging). */
    String goalId();

    /** Flip succeeded; standard post-completion flow runs. */
    record Completed(String goalId) implements MarkCompletedResult {}

    /**
     * T1-DD refused; {@code repointedCurrentSubGoalId} carries the leaf the guard re-pointed to
     * (may be null if no active leaf was reachable). Callers must NOT continue the "treat goal
     * as terminal" flow that would clobber that pointer.
     *
     * @param activeChildCount the number of ACTIVE direct descendants that triggered the refusal
     */
    record RefusedActiveDescendants(String goalId,
                                    String repointedCurrentSubGoalId,
                                    long activeChildCount) implements MarkCompletedResult {}

    /** Caller passed an id not in {@code goalMap}; nothing happened. */
    record UnknownGoal(String goalId) implements MarkCompletedResult {}
}
