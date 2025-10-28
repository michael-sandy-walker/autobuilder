package com.walpro.autobuilder.config;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.coordinator.AgentCoordinator;
import com.walpro.autobuilder.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.coordinator.MergeCoordinator;
import com.walpro.autobuilder.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.engine.*;
import com.walpro.autobuilder.engine.agent.*;
import com.walpro.autobuilder.persistence.MutationSnapshotService;
import com.walpro.autobuilder.service.FileService;
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
