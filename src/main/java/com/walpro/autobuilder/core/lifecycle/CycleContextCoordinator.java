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
            String detectedBasePackage = projectStructureSummarizer.detectBasePackage(outputPath);

            GoalEntity goal = goalPlannerService.getRootGoal();
            GoalEntity subgoal = goalPlannerService.getCurrentSubGoal();

            int depth = computeGoalDepth(subgoal == null ? null : subgoal.getId());
            int cumulativeStagnating = subgoal != null
                    ? goalPlannerService.getGoalMetadataInt(subgoal, "cumulativeStagnatingCycles")
                    : 0;

            // T1-BG/T3-BI: load cumulative criteria pass status from the subgoal entity.
            // Always read from the entity so subgoal changes automatically yield a fresh map,
            // and restarts recover previously banked criterion passes.
            Map<Integer, Boolean> criteriaPassStatus = subgoal != null
                    ? goalPlannerService.getCriteriaPassStatus(subgoal)
                    : null;

            @SuppressWarnings("unchecked")
            List<String> subgoalCriteria = subgoal != null && subgoal.getMetadata() != null
                    ? (List<String>) subgoal.getMetadata().getOrDefault("acceptanceCriteria", List.of())
                    : List.of();

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
                .evolutionRanThisCycle(false)
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
                .cumulativeStagnatingCycles(cumulativeStagnating)
                .establishedBasePackage(detectedBasePackage)
                .currentSubGoalCriteria(subgoalCriteria.isEmpty() ? null : subgoalCriteria)
                // T1-BG/T3-BI: always sourced from the entity so subgoal switches and restarts are handled transparently
                .cumulativeCriteriaStatus(criteriaPassStatus != null && !criteriaPassStatus.isEmpty() ? criteriaPassStatus : null)
                .build();

            base = cycleStartSignalSeeder.seedIfEmpty(base);

            if (goal == null) {
                base = base.withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "no root goal"));
            } else if (subgoal == null && goal.getStatus() == GoalStatus.COMPLETED) {
                // Fix T1-AD/A: root is completed and no subgoals remain — nominal terminal state
                log.info("Root goal COMPLETED and no active subgoals remain; injecting GOAL_EXHAUSTED to halt loop.");
                base = base.withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "completion-terminal"));
            } else if (subgoal == null) {
                // Fix T1-AD/B: subgoals exhausted but root not yet completed — allow one replan attempt
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
