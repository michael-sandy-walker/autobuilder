package com.walpro.autobuilder.graph.planner;

import com.walpro.autobuilder.graph.core.GraphActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.util.HashingUtils;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PlannerActionRegistrar {
    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerPlannerAction(String agentId, CycleContextData ctx, PlannerOutcome outcome) {
        if (ctx == null || outcome == null) return;

        String scopeGoalId = GraphActions.activeGoalId(ctx);
        String actionId = GraphActions.register(agentGraphService, "planner",
                outcome.updated() ? "applied" : "rejected",
                agentId, ctx);

        String fingerprint = fingerprint(outcome);

        agentGraphService.attachPlannerMetadata(
                actionId,
                fingerprint,
                outcome.updated(),
                outcome.newGoals() != null ? outcome.newGoals().size() : 0
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("updated", outcome.updated());
        payload.put("newGoals", outcome.newGoals());
        payload.put("newGoalsCount", outcome.newGoals() != null ? outcome.newGoals().size() : 0);
        payload.put("message", outcome.message());
        payload.put("fingerprint", fingerprint);
        payload.put("scopeGoalId", scopeGoalId);
        payload.put("rootGoalId", ctx.currentGoalId());
        payload.put("cycleId", ctx.cycleName());
        payload.put("attemptId", ctx.lastAttemptId());
        payload.put("parentAttemptId", ctx.parentAttemptId());

        String content;
        try {
            content = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            content = "{\"error\":\"failed_to_serialize_planner_outcome\"}";
        }

        GraphActions.link(agentGraphService, actionId, "planner_outcome", content, null);
    }

    private static String fingerprint(PlannerOutcome outcome) {
        String msg = outcome.message() == null ? "" : outcome.message().trim();

        List<String> goals = outcome.newGoals() == null ? List.of() : outcome.newGoals().stream()
                .map(s -> s == null ? "" : s.replaceAll("\\s+", " ").trim())
                .filter(s -> !s.isBlank())
                .toList();

        // order-insensitive canonical form
        List<String> sortedGoals = goals.stream()
                .map(String::toLowerCase)
                .sorted()
                .toList();

        String normalized = (outcome.updated() ? "1" : "0")
                + "|goals=" + String.join("||", sortedGoals)
                + "|msg=" + msg;

        return HashingUtils.hashSha256(normalized);
    }
}
