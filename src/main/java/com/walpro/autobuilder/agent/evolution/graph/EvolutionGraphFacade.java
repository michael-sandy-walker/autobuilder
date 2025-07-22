package com.walpro.autobuilder.agent.evolution.graph;

import com.walpro.autobuilder.agent.evolution.model.EvolutionInput;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.graph.GraphWeights;
import com.walpro.autobuilder.graph.model.*;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class EvolutionGraphFacade {

    private final Graph graph = new Graph();

    public void recordEvolution(EvolutionInput input, EvolutionOutcome result) {
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
