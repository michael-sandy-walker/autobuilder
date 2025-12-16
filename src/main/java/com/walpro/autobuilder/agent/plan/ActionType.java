package com.walpro.autobuilder.agent.plan;

public enum ActionType {

    /** Introduce something new (file, node, goal, structure) */
    ADD,

    /** Modify existing content or structure */
    MODIFY,

    /** Remove something explicitly */
    DELETE,

    /** Combine or reconcile existing elements */
    MERGE,

    /** Split or extract part of an existing structure */
    SPLIT,

    /** Reorganize without semantic change (move, rename, rewire) */
    RESTRUCTURE,

    /** Investigate or analyze without modifying the world */
    ANALYZE
}
