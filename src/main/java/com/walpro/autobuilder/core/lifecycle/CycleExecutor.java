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
    private final MergeCoordinator mergeCoordinator;
    private final PlannerCoordinator plannerCoordinator;
    private final GoalPlannerService goalPlannerService;
    private final MutationApplier mutationApplier;
    private final CycleOrchestrator cycleOrchestrator;
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
//        context = runCycleStep(context, 0, false);

        context = adaptiveExecutionEngine.runCycle(context);

        context = context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD);
        log.debug("Signals after completed cycle {}: {}", context.cycleName(), context.signals());

        metaAgent.analyzeAndEvolve(context);

        return context;
    }

    private CycleContextData runCycleStep(CycleContextData context, int depth, boolean recombine) {
        if (depth >= 5) {
            log.warn("🛑 Max depth reached. Stopping recursion.");
            return context;
        }

        // 1. Evolution
        context = evolutionCoordinator.execute(context);

        // 2. Evaluation
        context = evaluationCoordinator.evaluate(context);
        if (context.evaluationInterpretation() != null) {
            context = context.withGoalProgress(context.evaluationInterpretation().goalProgress());
        }

        // 3. Routing
        CycleResultState result = cycleOrchestrator.determineNextState(context);
        context = context.withCycleResultState(result);

        context = switch (result) {
            case SUCCESSFUL -> {
                context = tryPromoteSubgoal(context);
                if (context.subgoalPromoted()) {
                    log.info("🔀 Merging after subgoal promotion.");
                    try {
                        context = mergeCoordinator.mergeCompletedBranch(context);
                    } catch (IOException e) {
                        log.error("Error during merge of cycle {}: {}", context.cycleName(), e.getMessage());
                    }
                }
                yield context.withPreviousAcceptedCycleName(context.cycleName());
            }

            case STAGNATED -> {
                if (canPromoteAfterStagnation(context)) {
                    context = tryPromoteSubgoal(context);
                } else {
                    log.info("🛑 Stagnation not promotable (score: {}, state: {})",
                            context.evaluationInterpretation().current(),
                            context.evaluationInterpretation().mutationState());
                }
                yield runCycleStep(context, depth + 1, !context.subgoalPromoted());
            }

            case REJECTED, FALLBACK_PLANNING -> {
                context = plannerCoordinator.plan(context);
                yield runCycleStep(context, depth + 1, false);
            }
        };
        
        metaAgent.analyzeAndEvolve(context);
        
        return context;
    }

    private CycleContextData tryPromoteSubgoal(CycleContextData context) {
        GoalEntity current = goalPlannerService.getCurrentSubGoal();
        if (current != null) {
            log.info("🎯 Subgoal '{}':'{}' fulfilled. Promoting.", current.getId(), current.getDescription());
            boolean promoted = goalPlannerService.promoteCurrentSubGoal();
            return context.withSubgoalPromoted(promoted);
        }
        return context;
    }

    private boolean canPromoteAfterStagnation(CycleContextData context) {
        var interp = context.evaluationInterpretation();
        return interp != null
                && interp.mutationState() == MutationAcceptanceState.ACCEPTED
                && interp.current() >= 0.75;
    }
}
