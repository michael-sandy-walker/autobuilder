package com.walpro.autobuilder.config;

import com.walpro.autobuilder.core.coordinator.AgentCoordinator;
import com.walpro.autobuilder.core.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.coordinator.MergeCoordinator;
import com.walpro.autobuilder.core.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.engine.CoordinationStrategy;
import com.walpro.autobuilder.core.engine.SequentialCoordinationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentEngineConfig {

    @Bean
    public List<AgentCoordinator> coordinators(
            EvaluationCoordinator eval,
            EvolutionCoordinator evo,
            PlannerCoordinator plan,
            MergeCoordinator merge
    ) {
        return List.of(eval, evo, plan, merge);
    }

    @Bean
    public CoordinationStrategy coordinationStrategy() {
        return new SequentialCoordinationStrategy();
    }

    @Bean
    public AdaptiveExecutionEngine adaptiveExecutionEngine(
            List<AgentCoordinator> coordinators,
            CoordinationStrategy coordinationStrategy
    ) {
        return new AdaptiveExecutionEngine(coordinators, coordinationStrategy);
    }
}
