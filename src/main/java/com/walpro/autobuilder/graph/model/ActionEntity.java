package com.walpro.autobuilder.graph.model;

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
