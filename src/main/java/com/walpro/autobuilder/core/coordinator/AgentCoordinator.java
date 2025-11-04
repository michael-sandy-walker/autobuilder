package com.walpro.autobuilder.core.coordinator;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface AgentCoordinator {

    /** Returns the unique role type (e.g. "evaluation", "evolution", "planner", "merge"). */
    String role();

    /** Determines whether the coordinator is ready to execute in the given cycle context. */
    boolean canExecute(CycleContextData context);

    /** Executes this coordinator’s logic and returns the updated context. */
    CycleContextData execute(CycleContextData context);
}
