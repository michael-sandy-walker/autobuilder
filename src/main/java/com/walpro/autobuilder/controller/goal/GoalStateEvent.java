package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * T4-AM: payload pushed on the {@code /autobuilder/goals/stream} SSE channel at the end of
 * every cycle. Carries a flat list of goal snapshots + the active signal map so the frontend
 * can reconcile node state without re-fetching the tree.
 *
 * <p>Two event kinds:
 * <ul>
 *     <li>{@code CYCLE_END} — emitted from {@code CycleExecutor.runCycle()} after all
 *         post-cycle hooks have run. Carries {@code cycleName}, {@code cycleResultState},
 *         {@code merged}, {@code signals}, and the full flat goals list.</li>
 *     <li>{@code TREE_CHANGED} — emitted after a write action mutated the goal tree
 *         (inject, force-given-up, etc.). Carries the full flat goals list so the
 *         frontend can rebuild without waiting for the next cycle.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalStateEvent(
        String kind,
        Instant emittedAt,
        String cycleName,
        String cycleResultState,
        boolean merged,
        String currentGoalId,
        String currentSubGoalId,
        Map<String, Double> signals,
        List<GoalStateSnapshotDto> goals
) {
    public static final String KIND_CYCLE_END = "CYCLE_END";
    public static final String KIND_TREE_CHANGED = "TREE_CHANGED";
}
