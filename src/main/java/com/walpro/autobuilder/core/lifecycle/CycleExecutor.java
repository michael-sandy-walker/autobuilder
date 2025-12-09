package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.agent.meta.MetaAgent;
import com.walpro.autobuilder.core.coordinator.EvaluationCoordinator;
import com.walpro.autobuilder.core.coordinator.EvolutionCoordinator;
import com.walpro.autobuilder.core.coordinator.MergeCoordinator;
import com.walpro.autobuilder.core.coordinator.PlannerCoordinator;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.engine.AdaptiveExecutionEngine;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.core.service.MutationApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleExecutor {

    /** Minimum signal strength below which a signal disappears. */
    public static final double SIGNAL_SURVIVAL_THRESHOLD = 0.05;

    private final EvolutionCoordinator evolutionCoordinator;
    private final EvaluationCoordinator evaluationCoordinator;
    private final MutationApplier mutationApplier;
    private final MetaAgent metaAgent;
    private final AdaptiveExecutionEngine adaptiveExecutionEngine;

    public CycleContextData bootstrapCycle(CycleContextData context) {
        log.info("🚀 Starting bootstrap evolution cycle...");
        CycleContextData evolved = null;
        try {
            evolved = evolutionCoordinator.evolve(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            mutationApplier.applyBootstrapMutations(evolved);
            ProjectDiffResult diff = evolutionCoordinator.computeProjectDiff(evolved);
            CycleContextData evaluated = evaluationCoordinator.evaluate(evolved.withProjectDiff(diff));

            if (evaluated.evaluationInterpretation() != null) {
                evaluated = evaluated.withGoalProgress(evaluated.evaluationInterpretation().goalProgress());
            }

            return evaluated;
        } catch (IOException e) {
            log.error("❌ Failed to apply bootstrap mutations", e);
            return evolved;
        }
    }

    public CycleContextData runCycle(CycleContextData context) {
        context = adaptiveExecutionEngine.runCycle(context);

        context = context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD);
        log.debug("Signals after completed cycle {}: {}", context.cycleName(), context.signals());

        metaAgent.analyzeAndEvolve(context);

        return context;
    }
}
