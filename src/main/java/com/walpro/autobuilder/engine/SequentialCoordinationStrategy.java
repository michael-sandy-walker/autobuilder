package com.walpro.autobuilder.engine;

import java.util.List;

import com.walpro.autobuilder.lifecycle.CycleContextData;

public class SequentialCoordinationStrategy implements CoordinationStrategy {
    @Override
    public List<ExecutionAgent> determineExecutionOrder(List<ExecutionAgent> agents, CycleContextData context) {
        return agents; // as-is
    }
}
