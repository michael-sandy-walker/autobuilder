package com.walpro.autobuilder.graph.stuck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.stuck.StuckPriors;
import com.walpro.autobuilder.graph.HistoryRead;
import com.walpro.autobuilder.graph.HistorySpan;
import com.walpro.autobuilder.graph.HistoryUse;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.GraphActions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-CT: the stuck ledger's outcomes kept in the graph, one {@code stuck_outcome} action each, and read
 * back for the same task by later runs. The graph keeps every lineage (T4-CJ), so nothing else is needed
 * for an outcome to outlive its run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Neo4jStuckPriors implements StuckPriors {

    static final String TYPE = "stuck_outcome";
    static final String AGENT_ID = "StuckLayer";

    private final AgentGraphService agentGraphService;
    private final Neo4jClient neo4jClient;
    private final RunIdentityService runIdentity;
    private final ObjectMapper objectMapper;

    @Override
    public void record(Recorded o, String cycleId) {
        if (o == null || o.taskKey() == null) return;
        try {
            CycleContextData ctx = CycleContextData.builder().cycleName(cycleId).build();
            String actionId = GraphActions.register(agentGraphService, TYPE, o.outcome().name(), AGENT_ID, ctx);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskKey", o.taskKey());
            payload.put("source", o.source());
            payload.put("signature", o.signature());
            payload.put("rung", o.rung());
            payload.put("question", o.question());
            payload.put("outcome", o.outcome().name());
            payload.put("cycleId", cycleId);
            GraphActions.link(agentGraphService, actionId, TYPE, GraphActions.json(objectMapper, payload, TYPE),
                    o.outcome() == Outcome.ENDED ? 1.0 : 0.0);
        } catch (Exception ex) {
            log.warn("T4-CT: the outcome of '{}' was not recorded in the graph: {}", o.signature(), ex.getMessage());
        }
    }

    @Override
    @HistoryRead(span = HistorySpan.ALL_LINEAGES, use = HistoryUse.BINDING,
            note = "StuckLayer: how earlier runs of the SAME task (seed hash) turned out on one stuck signature,"
                    + " so a signature they already noticed is asked about at its first sighting, starting with the"
                    + " question that ended it there. This run's own records are excluded.",
            crossLineageBecause = "Keyed by the complete input: the same seed hash is the same task and the same"
                    + " signature the same failure. It decides only WHEN a structural question is asked and which one"
                    + " first; the answer is read from the current tree, and nothing is blocked, promoted or skipped.")
    public Prior priorFor(String taskKey, String source, String signature) {
        if (taskKey == null || source == null || signature == null) return Prior.NONE;
        try {
            var rows = neo4jClient.query("""
                    MATCH (a:Action { type: $type })-[:PRODUCES]->(art:Artifact { type: $type })
                    WHERE art.runId <> $runId AND art.content CONTAINS $taskKey
                    RETURN art.runId AS runId, art.content AS content
                    """)
                    .bindAll(Map.of("type", TYPE, "runId", runIdentity.runId(), "taskKey", taskKey))
                    .fetch().all();
            List<Row> matching = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                JsonNode c = objectMapper.readTree(String.valueOf(r.get("content")));
                if (!taskKey.equals(c.path("taskKey").asText()) || !source.equals(c.path("source").asText())
                        || !signature.equals(c.path("signature").asText())) continue;
                matching.add(new Row(String.valueOf(r.get("runId")), c.path("rung").asInt(),
                        Outcome.valueOf(c.path("outcome").asText())));
            }
            return Prior.of(matching);
        } catch (Exception ex) {
            log.warn("T4-CT: earlier outcomes of '{}' could not be read: {}", signature, ex.getMessage());
            return Prior.NONE;
        }
    }
}
