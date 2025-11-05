package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface AgentCoordinator {

    /** Returns the unique role type (e.g. "evaluation", "evolution", "planner", "merge"). */
    String role();

    /** Determines whether the coordinator is ready to execute in the given cycle context. */
    boolean canExecute(CycleContextData context);

    /** Executes this coordinator’s logic and returns the updated context. */
    CycleContextData execute(CycleContextData context);

    /**
     * Provides the current coordinator priority for ordering decisions.
     * Lower values indicate higher priority.
     */
    default double priority(CycleContextData context) {
        // Default sequential order; can be overridden per coordinator if needed
        return switch (role()) {
            case "planner" -> 1.0;
            case "evolution" -> 2.0;
            case "evaluation" -> 3.0;
            case "merge" -> 4.0;
            default -> 99.0;
        };
    }
}
