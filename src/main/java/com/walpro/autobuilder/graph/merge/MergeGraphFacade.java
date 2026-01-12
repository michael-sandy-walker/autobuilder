package com.walpro.autobuilder.graph.merge;

import com.walpro.autobuilder.graph.util.GraphWeights;
import com.walpro.autobuilder.agent.merge.model.MergeNode;
import com.walpro.autobuilder.graph.core.Graph;
import com.walpro.autobuilder.graph.core.GraphEdge;
import com.walpro.autobuilder.graph.core.GraphNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MergeGraphFacade {

    private final Graph graph = new Graph();

    public void recordMerge(MergeNode node) {
        String nodeId = node.mergedInCycle() + "::" + node.path();

        // Create node for the merge result
        GraphNode mergedNode = new GraphNode(nodeId, "merge", GraphWeights.MERGE_NODE_WEIGHT);
        graph.addNode(mergedNode);

        // Link all contributing cycles to this merge
        for (String cycleId : node.contributingCycles()) {
            GraphNode parent = new GraphNode(cycleId, "cycle", GraphWeights.CYCLE_NODE_WEIGHT);
            graph.addNode(parent);
            graph.addEdge(new GraphEdge(cycleId, nodeId, GraphWeights.MERGE_EDGE_WEIGHT));
        }
    }
}
