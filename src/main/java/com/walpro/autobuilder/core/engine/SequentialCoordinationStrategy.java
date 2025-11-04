package com.walpro.autobuilder.core.engine;

import java.util.List;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public class SequentialCoordinationStrategy implements CoordinationStrategy {
    @Override
    public List<AgentCoordinator> determineExecutionOrder(List<AgentCoordinator> coordinators,
                                                          CycleContextData context) {
        // Keep declared order for now; we can add smarter logic later.
        return coordinators;
    }
}
