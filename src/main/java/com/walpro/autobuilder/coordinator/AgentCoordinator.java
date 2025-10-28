package com.walpro.autobuilder.coordinator;

import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface AgentCoordinator {

    /** Returns the unique role type (e.g. "evaluation", "evolution", "planner", "merge"). */
    String role();

    /** Determines whether the coordinator is ready to execute in the given cycle context. */
    boolean canExecute(CycleContextData context);

    /** Executes this coordinator’s logic and returns the updated context. */
    CycleContextData execute(CycleContextData context);
}
