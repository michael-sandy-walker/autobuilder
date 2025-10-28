package com.walpro.autobuilder.engine;

import java.util.List;

import com.walpro.autobuilder.coordinator.AgentCoordinator;
import com.walpro.autobuilder.lifecycle.CycleContextData;

public interface CoordinationStrategy {
    List<AgentCoordinator> determineExecutionOrder(List<AgentCoordinator> coordinators,
                                                   CycleContextData context);
}