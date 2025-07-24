package com.walpro.autobuilder.agent.planner.graph;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.graph.GraphWeights;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.graph.model.Graph;
import com.walpro.autobuilder.graph.model.GraphEdge;
import com.walpro.autobuilder.graph.model.GraphNode;
import com.walpro.autobuilder.lifecycle.CycleContextData;

@Component
public class PlannerGraphFacade {

    private final Graph graph = new Graph();

    public void recordPlanning(CycleContextData input, PlannerOutcome outcome) {
        String nodeId = input.currentSubGoalDescription() != null ? input.currentSubGoalDescription() : "unknown-subgoal";
        double weight = outcome.updated()
                ? GraphWeights.PLANNER_NODE_WEIGHT_POSITIVE
                : GraphWeights.PLANNER_NODE_WEIGHT_NEGATIVE;

        GraphNode node = new GraphNode(nodeId, "planner", weight);
        graph.addNode(node);

        // If we ever decide to use full goal/subgoal hierarchy:
        // Link to current goal if available
        if (input.currentGoalDescription() != null && !input.currentGoalDescription().equals(nodeId)) {
            graph.addNode(new GraphNode(input.currentGoalDescription(), "goal", GraphWeights.GOAL_NODE_WEIGHT));
            GraphEdge edge = new GraphEdge(input.currentGoalDescription(), nodeId, weight);
            graph.addEdge(edge);
        }
    }

    public Graph getInternalGraph() {
        return graph;
    }
}
