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

import static com.walpro.autobuilder.core.lifecycle.BasicSignals.*;

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

            // T3-BK: load per-criterion consecutive-fail streak counts from the subgoal entity.
            Map<Integer, Integer> criteriaFailStreak = subgoal != null
                    ? goalPlannerService.getCriteriaFailStreak(subgoal)
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
                // T2-BL: reset lastCriteriaPassCount when the active subgoal changes so the detector
                // does not compare the new subgoal's pass count against the old subgoal's baseline.
                .lastCriteriaPassCount(subgoalChanged(previous, subgoal) ? 0
                        : (previous != null ? previous.lastCriteriaPassCount() : 0))
                // T1-BS: clear lastAttemptId on subgoal change so isAcceptedNotMergedAttempt cannot
                // look up a stale ACCEPTED+unmerged attempt from the previous subgoal and permanently
                // block evolution on the incoming subgoal.
                .lastAttemptId(subgoalChanged(previous, subgoal) ? null
                        : (previous != null ? previous.lastAttemptId() : null))
                // T3-BK: always sourced from the entity; new subgoals have no streak data (null → fresh start).
                .criteriaConsecutiveFailCount(criteriaFailStreak != null && !criteriaFailStreak.isEmpty() ? criteriaFailStreak : null)
                .build();

            // T2-BK: replace subgoal-scoped signals in the carried context with the entity-backed
            // state for the current subgoal. This isolates per-subgoal signal history: switching
            // to a new subgoal gives a clean slate (its entity has no persisted signals yet), while
            // same-subgoal continuation and restarts recover the correctly persisted decayed state.
            // Structural signals (ROLE_*, REPLAN_REQUESTED, etc.) are not in SUBGOAL_SCOPED_SIGNALS
            // and pass through unchanged from the carried context.
            Map<SignalType, Signal> entitySignals = subgoal != null
                    ? goalPlannerService.getSubgoalSignals(subgoal)
                    : Map.of();
            for (SignalType type : BasicSignals.SUBGOAL_SCOPED_SIGNALS) {
                base = base.withoutSignal(type);
            }
            for (Signal s : entitySignals.values()) {
                base = base.withSignal(s);
            }
            if (!entitySignals.isEmpty()) {
                log.debug("[T2-BK] Restored {} subgoal-scoped signal(s) from entity for subgoal {}",
                        entitySignals.size(), subgoal != null ? subgoal.getId() : null);
            }

            base = cycleStartSignalSeeder.seedIfEmpty(base);

            if (goal == null) {
                base = base.withSignal(Signal.of(BasicSignals.ROLE_PLANNER, 1.0, 0.1, "no root goal"));
            } else if (subgoal == null && goal.getStatus() == GoalStatus.COMPLETED) {
                // Fix T1-AD/A: root is completed and no subgoals remain — nominal terminal state
                log.info("Root goal COMPLETED and no active subgoals remain; injecting GOAL_EXHAUSTED to halt loop.");
                base = base.withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "completion-terminal"));
            } else if (subgoal == null && goal.getStatus() == GoalStatus.GIVEN_UP) {
                // T1-BZ: root was given up (majority of direct children failed) — halt rather than replanning.
                log.warn("T1-BZ: Root goal GIVEN_UP with no active subgoals — injecting GOAL_EXHAUSTED to halt loop.");
                base = base.withSignal(Signal.of(BasicSignals.GOAL_EXHAUSTED, 1.0, 0.0, "root-given-up-terminal"));
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
    
    private static boolean subgoalChanged(CycleContextData previous, GoalEntity subgoal) {
        if (previous == null) return false;
        String prevId = previous.currentSubGoalId();
        String newId = subgoal != null ? subgoal.getId() : null;
        if (prevId == null && newId == null) return false;
        if (prevId == null || newId == null) return true;
        return !prevId.equals(newId);
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
