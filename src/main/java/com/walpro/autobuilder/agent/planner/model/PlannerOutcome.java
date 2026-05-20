package com.walpro.autobuilder.agent.planner.model;

import java.util.List;
import java.util.Map;

public record PlannerOutcome(
        boolean updated,
        List<String> newGoals,
        List<String> goalsToRemove,
        String message,
        // T3-AW: keyed by goal description; null/absent means no criteria for that goal
        Map<String, List<String>> goalCriteria,
        // T3-BH: 1-2 sentence rationale describing what the proposed batch collectively achieves.
        // Required when >= 2 subgoals are proposed; null otherwise.
        String planRationale,
        // T1-EI: explicit #SubGoalReactivate goal ids parsed from the planner response.
        // Null/empty when the planner did not emit a reactivate block.
        List<String> reactivateGoalIds
) {
    public PlannerOutcome(boolean updated, List<String> newGoals, List<String> goalsToRemove,
                          String message, Map<String, List<String>> goalCriteria, String planRationale) {
        this(updated, newGoals, goalsToRemove, message, goalCriteria, planRationale, List.of());
    }
}