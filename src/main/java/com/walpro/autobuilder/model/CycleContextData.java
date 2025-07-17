package com.walpro.autobuilder.model;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.lifecycle.CycleResultState;
import com.walpro.autobuilder.tools.diff.ChangedMethod;

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
    DiffReport diffReport,

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

    // Merge
    String mergedPath,
    String mergedBaseContent,
    List<FileVariant> mergedVariants,
    MergeTrace mergeTrace,

    // Mutation context
    List<String> changedPaths,
    List<String> deletedPaths,
    List<String> oldFiles,

    // Evaluation context
    List<HeuristicFinding> findings,
    Map<String, List<Double>> scoreHistoryByFile,
    Map<String, Double> recentDeltas,
    List<ChangedMethod> changedMethods
) {}
