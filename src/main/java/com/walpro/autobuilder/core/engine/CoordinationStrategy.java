package com.walpro.autobuilder.core.engine;

import java.util.List;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface CoordinationStrategy {
    List<AgentCoordinator> determineExecutionOrder(List<AgentCoordinator> coordinators,
                                                   CycleContextData context);
}