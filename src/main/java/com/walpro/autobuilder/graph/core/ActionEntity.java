package com.walpro.autobuilder.graph.core;

import java.time.Instant;

public record ActionEntity(
    String id,
    String type,             // "plan", "mutation", "evaluation", etc.
    String status,           // "proposed", "applied", "rejected"
    String createdByAgentId,
    String targetsGoalId,
    String occurredInCycle,
    Instant timestamp
) {}
