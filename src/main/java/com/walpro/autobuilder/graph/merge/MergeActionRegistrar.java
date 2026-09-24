package com.walpro.autobuilder.graph.merge;

import com.walpro.autobuilder.graph.core.GraphActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.merge.PackageFragmentationReport;
import com.walpro.autobuilder.core.merge.RolledBackReport;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MergeActionRegistrar {
    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerMerge(String agentId, CycleContextData context, MergeResult result) {
        String state = result != null && result.wasSuccessful() ? "MERGED" : "SKIPPED";

        String actionId = GraphActions.register(agentGraphService, "merge", state,
                agentId, context);

        Map<String, Object> payload = GraphActions.basePayload(context);
        payload.put("merged", result != null && result.wasSuccessful());
        payload.put("reasoning", result != null ? result.reasoning() : null);
        payload.put("mergedContent", result != null ? result.currentContent() : null);

        String jsonPayload = GraphActions.json(objectMapper, payload, "merge_payload");

        GraphActions.link(agentGraphService, actionId, "merge_payload", jsonPayload, null);
    }

    /**
     * T4-BA: records a package-fragmentation rejection into the orchestration graph so the
     * meta-deliberation and analysis agents can see it without scraping behaviour logs —
     * mirrors {@link com.walpro.autobuilder.graph.inject.CompileFixInjectionRegistrar}. One
     * {@code ActionEntity} per cycle (type {@code package_fragmentation}, state
     * {@code REJECTED}), linked to a JSON artifact carrying every conflicting type with its
     * existing vs. attempted path. A generic {@code merge}/{@code SKIPPED} action cannot
     * distinguish "no ACCEPTED variants" from "rejected for fragmentation"; this can.
     */
    public void registerPackageFragmentationRejection(String agentId, CycleContextData context,
                                                      PackageFragmentationReport observation) {
        if (observation == null || observation.conflicts() == null || observation.conflicts().isEmpty()) return;

        String actionId = GraphActions.register(agentGraphService, "package_fragmentation",
                "REJECTED",
                agentId, context);

        Map<String, Object> payload = GraphActions.basePayload(context);
        payload.put("conflictCount", observation.conflicts().size());
        List<Map<String, String>> conflicts = new ArrayList<>();
        for (PackageFragmentationReport.Conflict c : observation.conflicts()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("typeName", c.typeName());
            entry.put("existingPath", c.existingPath());
            entry.put("attemptedPath", c.attemptedPath());
            conflicts.add(entry);
        }
        payload.put("conflicts", conflicts);
        String jsonPayload = GraphActions.json(objectMapper, payload, "package_fragmentation_payload");

        GraphActions.link(agentGraphService, actionId, "package_fragmentation_payload",
                jsonPayload, null);
    }

    /**
     * T5-X: records a post-merge rollback (T1-DW) into the orchestration graph. Without this,
     * the per-path {@code merge}/{@code MERGED} actions registered inside the merge loop fire
     * before the build gate runs, so a rolled-back cycle leaves phantom MERGED actions in the
     * graph for files that were immediately reverted on disk. The meta-deliberation and
     * analysis agents read the graph via {@link com.walpro.autobuilder.graph.snapshot.OrchestrationSnapshotService};
     * this {@code merge_rollback}/{@code ROLLED_BACK} action is the authoritative superseding
     * record. Mirrors {@link #registerPackageFragmentationRejection}.
     */
    public void registerMergeRollback(String agentId, CycleContextData context, RolledBackReport observation) {
        if (observation == null) return;

        String actionId = GraphActions.register(agentGraphService, "merge_rollback", "ROLLED_BACK",
                agentId, context);

        Map<String, Object> payload = GraphActions.basePayload(context);
        payload.put("restoredPaths", observation.restoredPaths());
        payload.put("stderrSummary", observation.stderrSummary());
        payload.put("errorKind", observation.errorKind() != null ? observation.errorKind().name() : null);
        String jsonPayload = GraphActions.json(objectMapper, payload, "merge_rollback_payload");

        GraphActions.link(agentGraphService, actionId, "merge_rollback_payload", jsonPayload, null);
    }
}
