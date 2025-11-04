package com.walpro.autobuilder.graph.evaluation;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.graph.util.GraphWeights;
import com.walpro.autobuilder.graph.core.*;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class EvaluationGraphFacade {

    private final Graph graph = new Graph();

    public void recordEvaluation(CycleContextData input, EvaluationOutcome outcome) {
        String nodeId = UUID.randomUUID().toString();
        double scoreWeight = input.score(); // or apply transformation if needed

        graph.addNode(new GraphNode(nodeId, "evaluation", scoreWeight));

        if (input.currentSubGoalDescription() != null) {
            graph.addNode(new GraphNode(input.currentSubGoalDescription(), "goal", GraphWeights.GOAL_NODE_WEIGHT));
            graph.addEdge(new GraphEdge(input.currentSubGoalDescription(), nodeId, scoreWeight));
        }

        String outcomeStatus = outcome.mutationState().name() + ":" + outcome.goalProgress().name();
        graph.addNode(new GraphNode(outcomeStatus, "outcome", GraphWeights.OUTCOME_NODE_WEIGHT));
        graph.addEdge(new GraphEdge(nodeId, outcomeStatus, GraphWeights.EVALUATION_TO_OUTCOME_EDGE_WEIGHT));
    }

    public Graph getInternalGraph() {
        return graph;
    }
}
