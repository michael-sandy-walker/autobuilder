package com.walpro.autobuilder.agent.planner.graph;

import com.walpro.autobuilder.agent.graph.GraphWeights;
import com.walpro.autobuilder.agent.planner.model.PlannerInput;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.graph.model.*;
import org.springframework.stereotype.Component;

@Component
public class PlannerGraphFacade {

    private final Graph graph = new Graph();

    public void recordPlanning(PlannerInput input, PlannerOutcome outcome) {
        String nodeId = input.currentSubGoal() != null ? input.currentSubGoal() : "unknown-subgoal";
        double weight = outcome.updated()
                ? GraphWeights.PLANNER_NODE_WEIGHT_POSITIVE
                : GraphWeights.PLANNER_NODE_WEIGHT_NEGATIVE;

        GraphNode node = new GraphNode(nodeId, "planner", weight);
        graph.addNode(node);

        // If we ever decide to use full goal/subgoal hierarchy:
        // Link to current goal if available
        if (input.currentGoal() != null && !input.currentGoal().equals(nodeId)) {
            graph.addNode(new GraphNode(input.currentGoal(), "goal", GraphWeights.GOAL_NODE_WEIGHT));
            GraphEdge edge = new GraphEdge(input.currentGoal(), nodeId, weight);
            graph.addEdge(edge);
        }
    }

    public Graph getInternalGraph() {
        return graph;
    }
}
