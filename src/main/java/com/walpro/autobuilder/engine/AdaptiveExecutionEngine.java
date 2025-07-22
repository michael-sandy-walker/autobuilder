package com.walpro.autobuilder.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import com.walpro.autobuilder.lifecycle.CycleContextData;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveExecutionEngine {

    private final List<ExecutionAgent> allAgents;
    private final CoordinationStrategy coordinationStrategy;

    public CycleContextData runCycle(CycleContextData context) {
        log.info("🚦 Starting adaptive execution cycle with {} agents.", allAgents.size());

        List<ExecutionAgent> orderedAgents = coordinationStrategy.determineExecutionOrder(allAgents, context);

        for (ExecutionAgent agent : orderedAgents) {
            if (agent.canExecute(context)) {
                log.info("⚙️ Executing agent: {}", agent.name());
                context = agent.execute(context);
            } else {
                log.info("⏭️ Skipping agent: {}", agent.name());
            }
        }

        return context;
    }
} 