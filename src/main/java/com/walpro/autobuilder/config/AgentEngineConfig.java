package com.walpro.autobuilder.config;

import com.walpro.autobuilder.agent.evaluation.EvaluationAgent;
import com.walpro.autobuilder.agent.evolution.EvolutionAgent;
import com.walpro.autobuilder.agent.merge.MergeAgent;
import com.walpro.autobuilder.agent.planner.PlannerAgent;
import com.walpro.autobuilder.core.deliberation.DeliberationService;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.core.engine.DeliberationExecutionGate;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.engine.ExecutionEligibilityExplainer;
import com.walpro.autobuilder.core.engine.meta.MetaOverrideLearningStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentEngineConfig {

    @Bean
    public List<ExecutionAgent> executionAgents(
            EvaluationAgent evaluation,
            EvolutionAgent evolution,
            PlannerAgent planner,
            MergeAgent merge
    ) {
        return List.of(evaluation, evolution, planner, merge);
    }

    @Bean
    public AdaptiveExecutionEngine adaptiveExecutionEngine(
            List<ExecutionAgent> executionAgents,
            DeliberationService deliberationService,
            DeliberationExecutionGate deliberationExecutionGate,
            ExecutionEligibilityExplainer executionEligibilityExplainer,
            MetaOverrideLearningStore metaOverrideLearningStore
    ) {
        return new AdaptiveExecutionEngine(executionAgents, deliberationService, deliberationExecutionGate, executionEligibilityExplainer, metaOverrideLearningStore);
    }
}
