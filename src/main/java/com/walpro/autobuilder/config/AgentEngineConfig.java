package com.walpro.autobuilder.config;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.coordinator.MergeCoordinator;
import com.walpro.autobuilder.core.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.engine.DeliberationExecutionGate;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.execution.CoordinatorExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.core.messaging.MessageBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
public class AgentEngineConfig {

    @Bean
    public List<ExecutionAgent> coordinators(
            EvaluationCoordinator eval,
            EvolutionCoordinator evo,
            PlannerCoordinator plan,
            MergeCoordinator merge
    ) {

        return List.of(
                wrap("evaluation", eval, BasicSignals.EVALUATION_SIGNALS),
                wrap("evolution", evo, BasicSignals.EVOLUTION_SIGNALS),
                wrap("planner", plan, BasicSignals.PLANNER_SIGNALS),
                wrap("merge", merge, BasicSignals.MERGE_SIGNALS)
        );
    }

    private static ExecutionAgent wrap(String id, AgentCoordinator coordinator, Set<SignalType> supportedSignalTypes) {
        return new CoordinatorExecutionAgent(id, coordinator, supportedSignalTypes);
    }

    

    @Bean
    public AdaptiveExecutionEngine adaptiveExecutionEngine(
            List<ExecutionAgent> executionAgents,
            DeliberationService deliberationService,
            DeliberationExecutionGate deliberationExecutionGate,
            MessageBus messageBus
    ) {
        return new AdaptiveExecutionEngine(executionAgents, deliberationService, deliberationExecutionGate, messageBus);
    }
}
