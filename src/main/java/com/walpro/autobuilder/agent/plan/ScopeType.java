package com.walpro.autobuilder.agent.plan;

public enum ScopeType {

    /** A single file or path */
    FILE,

    /** A logical module or package */
    MODULE,

    /** A specific goal or subgoal */
    GOAL,

    /** The goal graph or a subgraph */
    GOAL_GRAPH,

    /** The project as a whole */
    PROJECT,

    /** Agent-related structures (spawning, pruning, config) */
    AGENT,

    /** Evaluation / heuristic configuration */
    EVALUATION,

    /** Planning structures or goal selection logic */
    PLANNING,

    /** Diff, snapshot, or persistence-related structures */
    PERSISTENCE
}
