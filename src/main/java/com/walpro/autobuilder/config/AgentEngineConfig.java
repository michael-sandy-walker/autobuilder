package com.walpro.autobuilder.config;

import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.coordinator.MergeCoordinator;
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
    public ExecutionAgent plannerAgentWrapper(PlannerAgent plannerAgent) {
        return new PlannerAgentWrapper(plannerAgent);
    }

    @Bean
    public ExecutionAgent evolutionAgentWrapper(EvolutionCoordinator evolutionCoordinator,
                                                FileService fileService,
                                                MutationSnapshotService mutationSnapshotService) {
        return new EvolutionAgentWrapper(evolutionCoordinator, fileService, mutationSnapshotService);
    }

    @Bean
    public ExecutionAgent evaluationAgentWrapper(EvaluationCoordinator evaluationCoordinator) {
        return new EvaluationAgentWrapper(evaluationCoordinator);
    }

    @Bean
    public ExecutionAgent mergeAgentWrapper(MergeCoordinator mergeCoordinator) {
        return new MergeAgentWrapper(mergeCoordinator);
    }

    @Bean
    public CoordinationStrategy sequentialCoordinationStrategy() {
        return new SequentialCoordinationStrategy();
    }

    @Bean
    public AdaptiveExecutionEngine adaptiveExecutionEngine(List<ExecutionAgent> allAgents,
                                                           CoordinationStrategy coordinationStrategy) {
        return new AdaptiveExecutionEngine(allAgents, coordinationStrategy);
    }
}
