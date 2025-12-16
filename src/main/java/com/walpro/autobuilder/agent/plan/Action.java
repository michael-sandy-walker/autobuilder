package com.walpro.autobuilder.agent.plan;

import java.util.Map;

public record Action(
        ActionType type,
        ActionScope scope,
        Map<String, Object> payload,
        double expectedImpact,
        double risk
) {}
