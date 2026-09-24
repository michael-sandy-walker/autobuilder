package com.walpro.autobuilder.graph.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The shape every action registrar shares: one {@code Action} per decision, stamped with the
 * cycle's goal, attempt and lineage, and its payload linked as an {@code Artifact}. Static rather
 * than a bean or interface default so that tests mocking {@link AgentGraphService} still see the
 * {@code registerAction} / {@code linkActionOutput} calls themselves.
 */
public final class GraphActions {

    private GraphActions() {}

    /** The goal an action targets: the current subgoal when there is one, else the root. */
    public static String activeGoalId(CycleContextData ctx) {
        return ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank()
                ? ctx.currentSubGoalId()
                : ctx.currentGoalId();
    }

    /** Registers one action of {@code type} in {@code state} for this cycle; returns its id. */
    public static String register(AgentGraphService graph, String type, String state, String agentId,
                                  CycleContextData ctx) {
        String actionId = UUID.randomUUID().toString();
        graph.registerAction(new ActionEntity(
                actionId,
                type,
                state,
                agentId,
                activeGoalId(ctx),
                ctx.currentGoalId(),
                ctx.cycleName(),
                Instant.now(),
                ctx.lastAttemptId(),
                ctx.parentAttemptId()));
        return actionId;
    }

    /** The attempt / cycle / goal / subgoal fields a payload starts with, in that order. */
    public static Map<String, Object> basePayload(CycleContextData ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", ctx.lastAttemptId());
        payload.put("cycleId", ctx.cycleName());
        payload.put("goalId", ctx.currentGoalId());
        payload.put("subGoalId", ctx.currentSubGoalId());
        return payload;
    }

    /** {@code payload} as JSON, or an error marker naming {@code what} when it cannot be written. */
    public static String json(ObjectMapper mapper, Map<String, Object> payload, String what) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"failed_to_serialize_" + what + "\"}";
        }
    }

    /** Links {@code content} to the action as an artifact of {@code type}. */
    public static void link(AgentGraphService graph, String actionId, String type, String content, Double score) {
        graph.linkActionOutput(actionId, new ArtifactEntity(UUID.randomUUID().toString(), type, null, content, score));
    }
}
