package com.walpro.autobuilder.graph.planner;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.graph.util.GraphWeights;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.graph.core.Graph;
import com.walpro.autobuilder.graph.core.GraphEdge;
import com.walpro.autobuilder.graph.core.GraphNode;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

@Component
public class PlannerGraphFacade {

    private final Graph graph = new Graph();

    public void recordPlanning(CycleContextData input, PlannerOutcome outcome) {
        String nodeId = (input.currentSubGoalId() != null && !input.currentSubGoalId().isBlank())
                ? input.currentSubGoalId()
                : "unknown-subgoal-id";

        double weight = outcome.updated()
                ? GraphWeights.PLANNER_NODE_WEIGHT_POSITIVE
                : GraphWeights.PLANNER_NODE_WEIGHT_NEGATIVE;

        GraphNode node = new GraphNode(nodeId, "planner", weight);
        graph.addNode(node);

        // If we ever decide to use full goal/subgoal hierarchy:
        // Link to current goal if available
        if (input.currentGoalId() != null && !input.currentGoalId().isBlank() && !input.currentGoalId().equals(nodeId)) {
            graph.addNode(new GraphNode(input.currentGoalId(), "goal", GraphWeights.GOAL_NODE_WEIGHT));
            graph.addEdge(new GraphEdge(input.currentGoalId(), nodeId, weight));
        }
    }

    public Graph getInternalGraph() {
        return graph;
    }
}
