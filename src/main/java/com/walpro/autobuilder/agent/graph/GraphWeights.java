package com.walpro.autobuilder.agent.graph;

public class GraphWeights {

    // General
    public static final double GOAL_NODE_WEIGHT = 1.0;
    public static final double OUTCOME_NODE_WEIGHT = 0.0;

    // Evaluation
    public static final double EVALUATION_NODE_WEIGHT = 1.0;
    public static final double GOAL_TO_EVALUATION_EDGE_WEIGHT = 0.9;
    public static final double EVALUATION_OUTCOME_NODE_WEIGHT = 0.1;
    public static final double EVALUATION_TO_OUTCOME_EDGE_WEIGHT = 0.3;

    // Evolution
    public static final double EVOLUTION_NODE_WEIGHT = 1.0;
    public static final double FILE_NODE_WEIGHT = 0.7;
    public static final double EVOLUTION_TO_FILE_EDGE_WEIGHT = 0.8;
    public static final double SUMMARY_NODE_WEIGHT = 0.4;
    public static final double EVOLUTION_TO_SUMMARY_EDGE_WEIGHT = 0.5;

    // Planning
    public static final double PLANNER_NODE_WEIGHT_POSITIVE = 1.0;
    public static final double PLANNER_NODE_WEIGHT_NEGATIVE = 0.2;
    public static final double PLANNER_EDGE_WEIGHT = 0.6;
    

    public static final double MERGE_NODE_WEIGHT = 0.8;
    public static final double CYCLE_NODE_WEIGHT = 0.7;
    public static final double MERGE_EDGE_WEIGHT = 0.6;

    private GraphWeights() {
        // Utility class
    }
}
