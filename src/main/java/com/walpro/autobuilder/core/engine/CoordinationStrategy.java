package com.walpro.autobuilder.core.engine;

import java.util.List;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface CoordinationStrategy {
    List<ExecutionAgent> determineExecutionOrder(List<ExecutionAgent> executionAgents,
                                                   CycleContextData context);

    /**
     * Optional hook for adaptive systems to adjust coordinator priorities after evaluation.
     */
    default CycleContextData adaptAfterCycle(CycleContextData context) {
        return context;
    }
}