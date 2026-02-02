package com.walpro.autobuilder.graph.core;

import java.time.Instant;

public record ActionEntity(
    String id,
    String type,             // "evolution", "evaluation", "merge", "planner", "meta"
    String status,           // "proposed", "applied", "rejected"
    String createdByAgentId,
    String targetsGoalId,
    String rootGoalId,
    String occurredInCycle,
    Instant timestamp,
    String attemptId,
    String parentAttemptId
) {}
