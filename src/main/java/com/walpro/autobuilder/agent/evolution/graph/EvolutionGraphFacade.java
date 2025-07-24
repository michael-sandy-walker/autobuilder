package com.walpro.autobuilder.agent.evolution.graph;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.graph.GraphWeights;
import com.walpro.autobuilder.graph.model.Graph;
import com.walpro.autobuilder.graph.model.GraphEdge;
import com.walpro.autobuilder.graph.model.GraphNode;
import com.walpro.autobuilder.lifecycle.CycleContextData;

@Component
public class EvolutionGraphFacade {

    private final Graph graph = new Graph();

    public void recordEvolution(CycleContextData input, EvolutionOutcome result) {
        String evolutionNodeId = UUID.randomUUID().toString();
        GraphNode evoNode = new GraphNode(evolutionNodeId, "evolution", GraphWeights.EVOLUTION_NODE_WEIGHT);
        graph.addNode(evoNode);

        input.projectDiff().changedFiles().keySet().forEach(path -> {
            graph.addNode(new GraphNode(path, "file", GraphWeights.FILE_NODE_WEIGHT));
            graph.addEdge(new GraphEdge(evolutionNodeId, path, GraphWeights.EVOLUTION_TO_FILE_EDGE_WEIGHT));
        });


        graph.addNode(new GraphNode(result.summary(), "summary", GraphWeights.SUMMARY_NODE_WEIGHT));
        graph.addEdge(new GraphEdge(evolutionNodeId, result.summary(), GraphWeights.EVOLUTION_TO_SUMMARY_EDGE_WEIGHT));
    }

    public Graph getInternalGraph() {
        return graph;
    }
}
