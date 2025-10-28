package com.walpro.autobuilder.engine;

import com.walpro.autobuilder.coordinator.AgentCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import com.walpro.autobuilder.lifecycle.CycleContextData;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveExecutionEngine {

    private final List<AgentCoordinator> coordinators;
    private final CoordinationStrategy coordinationStrategy;

    public CycleContextData runCycle(CycleContextData context) {
        List<AgentCoordinator> order = coordinationStrategy
                .determineExecutionOrder(coordinators, context);

        for (AgentCoordinator c : order) {
            if (c.canExecute(context)) {
                log.info("Executing coordinator: {}", c.role());
                context = c.execute(context);
            } else {
                log.debug("Skipping coordinator: {}", c.role());
            }
        }
        return context;
    }
} 