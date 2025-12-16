package com.walpro.autobuilder.agent.intent;

public record Intent(
        String description,
        String relatedGoalId,
        double priority,
        String successCriteria
) {}
