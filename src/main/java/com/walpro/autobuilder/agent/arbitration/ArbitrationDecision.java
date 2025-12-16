package com.walpro.autobuilder.agent.arbitration;

public record ArbitrationDecision(
        DecisionType decision,
        String reason
) {}
