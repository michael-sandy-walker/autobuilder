package com.walpro.autobuilder.agent.planner.model;

import java.util.List;
import java.util.Map;

public record PlannerOutcome(
        boolean updated,
        List<String> newGoals,
        List<String> goalsToRemove,
        String message,
        // T3-AW: keyed by goal description; null/absent means no criteria for that goal
        Map<String, List<String>> goalCriteria
) {}