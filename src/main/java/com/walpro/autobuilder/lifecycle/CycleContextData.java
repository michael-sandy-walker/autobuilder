package com.walpro.autobuilder.lifecycle;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.diff.ProjectDiffResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MergeTrace;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import lombok.Builder;
import lombok.With;

import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
@With
public record CycleContextData(
    String cycleName,
    String outputPath,
    Map<String, String> previousOutput,
    String projectSummary,
    ProjectDiffResult projectDiff,

    // Goal tracking
    String currentGoalDescription,
    String currentSubGoalDescription,
    String currentGoalId,
    String currentSubGoalId,
    List<GoalEntity> remainingGoals,
    List<GoalEntity> completedGoals,

    // Evaluation
    String rejectionFeedback,
    String recommendation,
    EvolutionOutcome evolutionOutcome,
    EvaluationOutcome evaluationOutcome,
    PlannerOutcome plannerOutcome,
    String evaluationFeedback,
    InterpretationResult evaluationInterpretation,
    MutationAcceptanceState mutationState,
    GoalProgressState goalProgress,
    CycleResultState cycleResultState,

    // Status flags
    boolean subgoalPromoted,
    boolean merged,
    boolean recombined,
    boolean repeatedRecombination,

    // Fingerprints + lineage
    String lastEvolutionFingerprint,
    String lastRecombinationFingerprint,
    List<String> recentEvolutionFingerprints,
    String previousAcceptedCycleName,
    String parentCycleName,
    String recombinationSourceCycleName,
    EvolutionOutcome lastEvolutionOutcome,
    EvaluationOutcome lastEvaluationOutcome,
    PlannerOutcome lastPlannerOutcome,

    // Merge
    String mergedPath,
    String mergedBaseContent,
    List<FileVariant> mergedVariants,
    MergeTrace mergeTrace,

    // Evaluation context
    List<HeuristicFinding> findings,
    Map<String, List<Double>> scoreHistoryByFile,
    Map<String, Double> recentDeltas
) {}
