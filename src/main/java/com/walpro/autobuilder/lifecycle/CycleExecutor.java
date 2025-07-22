package com.walpro.autobuilder.lifecycle;

import com.walpro.autobuilder.coordinator.*;
import com.walpro.autobuilder.diff.ProjectDiffResult;
import com.walpro.autobuilder.diff.ProjectDiffService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.service.FingerprintService;
import com.walpro.autobuilder.service.MutationApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleExecutor {

    private final EvolutionCoordinator evolutionCoordinator;
    private final EvaluationCoordinator evaluationCoordinator;
    private final MergeCoordinator mergeCoordinator;
    private final PlannerCoordinator plannerCoordinator;
    private final GoalPlannerService goalPlannerService;
    private final MutationApplier mutationApplier;
    private final CycleOrchestrator cycleOrchestrator;
    private final FingerprintService fingerprintService;
    private final ProjectDiffService projectDiffService;

    public CycleContextData bootstrapCycle(CycleContextData context) {
        log.info("🚀 Starting bootstrap evolution cycle...");
        CycleContextData evolved = evolutionCoordinator.evolve(context);

        try {
            mutationApplier.applyBootstrapMutations(evolved);
            ProjectDiffResult diff = computeProjectDiff(evolved);
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
        return runCycleStep(context, 0, false);
    }

    private CycleContextData runCycleStep(CycleContextData context, int depth, boolean recombine) {
        if (depth >= 5) {
            log.warn("🛑 Max depth reached. Stopping recursion.");
            return context;
        }

        // 1. Evolution
        context = recombine
                ? evolutionCoordinator.recombineOnStagnation(context)
                : evolutionCoordinator.evolve(context);

        context = updateFingerprint(context);

        try {
            mutationApplier.applyRegularMutations(context);
            ProjectDiffResult diff = computeProjectDiff(context);
            context = context.withProjectDiff(diff);
        } catch (IOException e) {
            log.error("❌ Failed to apply mutations", e);
            return context;
        }

        // 2. Evaluation
        context = evaluationCoordinator.evaluate(context);
        if (context.evaluationInterpretation() != null) {
            context = context.withGoalProgress(context.evaluationInterpretation().goalProgress());
        }

        // 3. Routing
        CycleResultState result = cycleOrchestrator.determineNextState(context);
        context = context.withCycleResultState(result);

        return switch (result) {
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
    }

    private ProjectDiffResult computeProjectDiff(CycleContextData context) throws IOException {
        Path previous = context.previousAcceptedCycleName() != null
                ? mutationApplier.resolveMutationPath(context.previousAcceptedCycleName())
                : null; // this means we're (still) in the bootstrap phase
        Path current = context.previousAcceptedCycleName() != null ? 
        		mutationApplier.resolveMutationPath(context.cycleName()) :
        			mutationApplier.resolveOutputPath();
        return projectDiffService.computeDiff(current, previous);
    }

    private CycleContextData updateFingerprint(CycleContextData context) {
        String newFp = fingerprintService.computeFingerprint(context.evolutionOutcome().addedOrChangedFiles());
        boolean isRepeat = newFp.equals(context.lastRecombinationFingerprint());

        if (context.recombined()) {
            return context
                    .withRepeatedRecombination(isRepeat)
                    .withLastRecombinationFingerprint(isRepeat ? context.lastRecombinationFingerprint() : newFp)
                    .withLastEvolutionFingerprint(newFp);
        }

        return context
                .withRepeatedRecombination(false)
                .withLastEvolutionFingerprint(newFp);
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
