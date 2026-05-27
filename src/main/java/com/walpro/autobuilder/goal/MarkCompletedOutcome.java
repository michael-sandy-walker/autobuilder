package com.walpro.autobuilder.goal;

/**
 * T1-ET Part B: structured outcome of {@link GoalPlannerService#markGoalCompleted(String, String)}.
 *
 * <p>The original signature was {@code void} and the T1-DD refusal-and-re-point guard inside it
 * could silently change {@code currentSubGoalId} without any signal back to callers.
 * {@link GoalPlannerService#promoteCurrentSubGoal()} ignored that refusal, fell through to
 * {@code findNextSiblingOrAncestor}, and then unconditionally clobbered the freshly-set
 * {@code currentSubGoalId} (often to null) — the zero-work loop documented in
 * {@code behaviour-2026-05-25_00-31-18.ndjson} (16 cycles in 87 s).
 *
 * <p>This carrier lets the caller distinguish:
 * <ul>
 *   <li>{@link Kind#COMPLETED} — flip succeeded; standard post-completion flow runs.</li>
 *   <li>{@link Kind#REFUSED_ACTIVE_DESCENDANTS} — T1-DD refused; {@code repointedCurrentSubGoalId}
 *       carries the leaf the guard re-pointed to. Callers must NOT continue the
 *       "treat goal as terminal" flow that would clobber that pointer.</li>
 *   <li>{@link Kind#UNKNOWN_GOAL} — caller passed an id not in {@code goalMap}; nothing happened.</li>
 * </ul>
 *
 * @param kind                          which branch fired
 * @param goalId                        the goal id passed in (preserved for logging / debugging)
 * @param repointedCurrentSubGoalId     for {@link Kind#REFUSED_ACTIVE_DESCENDANTS}, the leaf the
 *                                      T1-DD guard moved {@code currentSubGoalId} to (may be null
 *                                      if no active leaf was reachable); null otherwise
 * @param activeChildCount              for {@link Kind#REFUSED_ACTIVE_DESCENDANTS}, the number of
 *                                      ACTIVE direct descendants that triggered the refusal; 0 otherwise
 */
public record MarkCompletedOutcome(
        Kind kind,
        String goalId,
        String repointedCurrentSubGoalId,
        long activeChildCount
) {

    public enum Kind { COMPLETED, REFUSED_ACTIVE_DESCENDANTS, UNKNOWN_GOAL }

    public boolean isCompleted() { return kind == Kind.COMPLETED; }
    public boolean isRefused() { return kind == Kind.REFUSED_ACTIVE_DESCENDANTS; }

    public static MarkCompletedOutcome completed(String goalId) {
        return new MarkCompletedOutcome(Kind.COMPLETED, goalId, null, 0);
    }

    public static MarkCompletedOutcome refused(String goalId, long activeChildCount, String repointedTo) {
        return new MarkCompletedOutcome(Kind.REFUSED_ACTIVE_DESCENDANTS, goalId, repointedTo, activeChildCount);
    }

    public static MarkCompletedOutcome unknownGoal(String goalId) {
        return new MarkCompletedOutcome(Kind.UNKNOWN_GOAL, goalId, null, 0);
    }
}
