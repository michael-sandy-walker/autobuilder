package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.GraphActions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-DZ: every question the terminal agent was asked, as a graph record of the run -- the user's
 * decision (2026-09-26): "it's just data regarding the specific session... I would go for the graph,
 * like it's also the case for other agents." One {@code terminal_agent} action per question, its
 * payload the question, each command with its output or refusal, and whether it answered.
 */
@Slf4j
@Component
public class TerminalAgentRecorder {

    static final String AGENT_ID = "TerminalAgent";

    @Autowired(required = false)
    private AgentGraphService agentGraphService;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /** The cycle context the record is attributed to; set by the stuck layer when it asks. */
    private volatile CycleContextData context;

    void attribute(CycleContextData ctx) {
        this.context = ctx;
    }

    public void record(String cycleId, TerminalAgent.Exchange e) {
        if (agentGraphService == null) return;
        CycleContextData ctx = context != null ? context : CycleContextData.builder().cycleName(cycleId).build();
        try {
            String actionId = GraphActions.register(agentGraphService, "terminal_agent",
                    e.answered() ? "ANSWERED" : "UNANSWERED", AGENT_ID, ctx);
            Map<String, Object> payload = GraphActions.basePayload(ctx);
            payload.put("signature", e.signature());
            payload.put("question", e.proposedQuestion());
            List<Map<String, Object>> commands = new ArrayList<>();
            for (ReadOnlyCopy.Result r : e.commands()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("argv", r.argv());
                if (r.refused() != null) c.put("refused", r.refused());
                else c.put("output", r.output());
                if (r.timedOut()) c.put("timedOut", true);
                commands.add(c);
            }
            payload.put("commands", commands);
            payload.put("answered", e.answered());
            payload.put("wroteToCopy", e.wroteToCopy());
            payload.put("modelReply", e.modelReply());
            GraphActions.link(agentGraphService, actionId, "terminal_agent_exchange",
                    GraphActions.json(objectMapper == null ? new ObjectMapper() : objectMapper, payload, "terminal_agent"),
                    e.answered() ? 1.0 : 0.0);
        } catch (Exception ex) {
            log.warn("T4-DZ: the exchange for '{}' was not recorded in the graph: {}", e.signature(), ex.getMessage());
        }
    }
}
