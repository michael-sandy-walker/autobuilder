package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.graph.core.OrchestrationGraphExplorerService;
import com.walpro.autobuilder.graph.core.OrchestrationGraphExplorerService.GraphNodeView;
import com.walpro.autobuilder.graph.core.OrchestrationGraphExplorerService.GraphOverview;
import com.walpro.autobuilder.graph.core.OrchestrationGraphExplorerService.NodeNeighborhood;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * T4-CM slice 1: read-only REST surface over the live orchestration graph — label and
 * T4-CJ run/lineage filtered browsing plus per-node neighborhood drill. Consumed by
 * {@code graph-explorer.html}; equally usable headlessly. No write endpoints by design —
 * connect gestures are a later slice behind the T4-CN compatibility check.
 */
@Slf4j
@RestController
@RequestMapping("/autobuilder/graph")
@RequiredArgsConstructor
public class GraphExplorerController {

    private final OrchestrationGraphExplorerService explorerService;

    /** Filter vocabulary: labels, lineages and runs present in the graph, with counts. */
    @GetMapping("/overview")
    public ResponseEntity<GraphOverview> overview() {
        return ResponseEntity.ok(explorerService.overview());
    }

    /** Nodes matching optional label / lineage / run filters (AND-composed, bounded). */
    @GetMapping("/nodes")
    public ResponseEntity<List<GraphNodeView>> nodes(
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String lineageId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Integer limit) {
        try {
            return ResponseEntity.ok(explorerService.nodes(label, lineageId, runId, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** One node plus its relationship neighborhood in both directions. */
    /** T5-BM: what was done about one goal, so the answer does not require reading the log. */
    @GetMapping("/goals/{goalId}/activity")
    public ResponseEntity<OrchestrationGraphExplorerService.GoalActivity> goalActivity(
            @PathVariable String goalId) {
        return explorerService.goalActivity(goalId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/nodes/{elementId}/neighbors")
    public ResponseEntity<NodeNeighborhood> neighbors(@PathVariable String elementId) {
        return explorerService.neighbors(elementId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
