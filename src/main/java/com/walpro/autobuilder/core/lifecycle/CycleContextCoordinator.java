package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.analysis.structure.DefaultProjectStructureSummarizer;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.core.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleContextCoordinator {

    private final FileService fileService;
    private final GoalPlannerService goalPlannerService;
    private final DefaultProjectStructureSummarizer projectStructureSummarizer;
    private final ContextLineageTracker lineageTracker;
    private final CycleStartSignalSeeder cycleStartSignalSeeder;

    public Optional<CycleContextData> prepareRuntimeContext(CycleContextData previous) {
        try {
            String cycleName = "cycle-" + Instant.now().toEpochMilli();
            Path outputPath = fileService.resolveSecurePath("output");
            Map<String, String> currentOutput = fileService.readFolderContentsResolved(outputPath);
            String summary = projectStructureSummarizer.summarizeProjectStructure(outputPath.toString());

            GoalEntity goal = goalPlannerService.getRootGoal();
            GoalEntity subgoal = goalPlannerService.getCurrentSubGoal();
            
            int depth = computeGoalDepth(subgoal == null ? null : subgoal.getId());

            CycleContextData base = (previous == null ? CycleContextData.builder() : previous.toBuilder())
                .cycleName(cycleName)
                .outputPath(outputPath.toString())
                .previousOutput(currentOutput)
                .projectSummary(summary)
                .currentGoalDescription(goal != null ? goal.getDescription() : null)
                .currentGoalId(goal != null ? goal.getId() : null)
                .currentSubGoalDescription(subgoal != null ? subgoal.getDescription() : null)
                .currentSubGoalId(subgoal != null ? subgoal.getId() : null)
                .remainingGoals(goalPlannerService.getRemainingGoals())
                .completedGoals(goalPlannerService.getCompletedGoals())
                .abandonedGoals(goalPlannerService.getAbandonedGoals())
                .rejectionFeedback(null)
                .recommendation(null)
                .evolutionOutcome(null)
                .evaluationOutcome(null)
                .plannerOutcome(null)
                .evaluationFeedback(null)
                .evaluationInterpretation(null)
                .mutationState(null)
                .subgoalPromoted(false)
                .merged(false)
                .mergedPath(null)
                .mergedBaseContent(null)
                .mergedVariants(List.of())
                .mergeTrace(lineageTracker.emptyMergeTrace())
                .findings(List.of())
                .goalDepth(depth)
                .recentEvolutionFingerprints(previous != null && previous.recentEvolutionFingerprints() != null
                        ? previous.recentEvolutionFingerprints()
                        : List.of())
                .scoringComputed(false)
                .build();

            base = cycleStartSignalSeeder.seedIfEmpty(base);

            if (goal == null) {
                base = base.withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "no root goal"));
            } else if (subgoal == null && goal.getStatus() == GoalStatus.COMPLETED) {
                // Fix T2-AD/A: root is completed and no subgoals remain — nominal terminal state
                log.info("Root goal COMPLETED and no active subgoals remain; injecting GOAL_EXHAUSTED to halt loop.");
                base = base.withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "completion-terminal"));
            } else if (subgoal == null) {
                // Fix T2-AD/B: subgoals exhausted but root not yet completed — allow one replan attempt
                base = base.withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "no active subgoal"));
            }

            return Optional.of(lineageTracker.enrichLineage(base, previous));
        } catch (Exception e) {
            log.error("Failed to prepare runtime context", e);
            return Optional.empty();
        }
    }
    
    private int computeGoalDepth(String goalId) {
        int depth = 0;
        while (goalId != null) {
            GoalEntity g = goalPlannerService.getGoalById(goalId);
            if (g == null) break;
            goalId = g.getParentId();
            depth++;
        }
        return depth;
    }
}
