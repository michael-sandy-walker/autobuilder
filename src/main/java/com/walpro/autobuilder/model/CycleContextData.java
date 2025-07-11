package com.walpro.autobuilder.model;

import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import lombok.Builder;
import lombok.With;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
@With
public record CycleContextData(
        String cycleName,
        Path outputPath,
        Map<String, String> previousOutput,
        String projectSummary,
        DiffReport diffReport,
        String currentGoalDescription,
        String currentSubGoalDescription,
        String currentGoalId,
        String currentSubGoalId,
        List<String> remainingGoals,
        List<String> completedGoals,
        String rejectionFeedback,
        String recommendation,
        EvolutionOutcome evolutionOutcome,
        EvaluationOutcome evaluationOutcome,
        PlannerOutcome plannerOutcome,
        String evaluationFeedback,
        boolean subgoalPromoted,
        boolean merged,
        InterpretationResult evaluationInterpretation,
        boolean recombined
) {

}