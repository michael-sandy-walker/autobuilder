package com.walpro.autobuilder.graph.scope;

import java.time.Instant;

public record ScopeActionSnapshot(
        String actionId,
        String subGoalId,
        String status,
        String cycleName,
        Instant timestamp
) {}
