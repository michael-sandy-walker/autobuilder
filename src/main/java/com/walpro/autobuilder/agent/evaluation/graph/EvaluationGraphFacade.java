package com.walpro.autobuilder.agent.evaluation.graph;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationInput;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.graph.GraphWeights;
import com.walpro.autobuilder.graph.model.*;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class EvaluationGraphFacade {

    private final Graph graph = new Graph();

    public void recordEvaluation(EvaluationInput input, EvaluationOutcome outcome) {
        String nodeId = UUID.randomUUID().toString();
        double scoreWeight = input.score(); // or apply transformation if needed

        graph.addNode(new GraphNode(nodeId, "evaluation", scoreWeight));

        if (input.currentGoal() != null) {
            graph.addNode(new GraphNode(input.currentGoal(), "goal", GraphWeights.GOAL_NODE_WEIGHT));
            graph.addEdge(new GraphEdge(input.currentGoal(), nodeId, scoreWeight));
        }

        String outcomeStatus = outcome.mutationState().name() + ":" + outcome.goalProgress().name();
        graph.addNode(new GraphNode(outcomeStatus, "outcome", GraphWeights.OUTCOME_NODE_WEIGHT));
        graph.addEdge(new GraphEdge(nodeId, outcomeStatus, GraphWeights.EVALUATION_TO_OUTCOME_EDGE_WEIGHT));
    }

    public Graph getInternalGraph() {
        return graph;
    }
}
