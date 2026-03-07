package com.walpro.autobuilder.agent.planner.model;

import java.util.List;

public record PlannerOutcome(
        boolean updated,
        List<String> newGoals,
        List<String> goalsToRemove,
        String message
) {}