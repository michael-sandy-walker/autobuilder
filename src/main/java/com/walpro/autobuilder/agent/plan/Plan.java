package com.walpro.autobuilder.agent.plan;

import com.walpro.autobuilder.agent.intent.Intent;

import java.util.List;

public record Plan(
        String agentId,
        Intent intent,
        List<Action> actions,
        String rationale
) {}
