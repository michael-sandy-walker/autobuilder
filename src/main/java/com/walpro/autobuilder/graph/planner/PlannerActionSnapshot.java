package com.walpro.autobuilder.graph.planner;

import java.time.Instant;

public record PlannerActionSnapshot(
        String actionId,
        String scopeGoalId,
        String fingerprint,
        Boolean updated,
        Integer newGoalsCount,
        String cycleName,
        String attemptId,
        Instant timestamp
) {}