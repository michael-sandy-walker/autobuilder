package com.walpro.autobuilder.model;

import com.walpro.autobuilder.diff.DiffReport;
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
        boolean merged
) {

//    public static CycleContextData withEvolutionOutcome(CycleContextData context, EvolutionOutcome outcome) {
//        return CycleContextData.builder()
//                .cycleName(context.cycleName())
//                .outputPath(context.outputPath())
//                .previousOutput(context.previousOutput())
//                .projectSummary(context.projectSummary())
//                .diffReport(context.diffReport())
//                .currentGoalDescription(context.currentGoalDescription())
//                .currentSubGoalDescription(context.currentSubGoalDescription())
//                .currentGoalId(context.currentGoalId())
//                .currentSubGoalId(context.currentSubGoalId())
//                .remainingGoals(context.remainingGoals())
//                .completedGoals(context.completedGoals())
//                .rejectionFeedback(context.rejectionFeedback())
//                .recommendation(context.recommendation())
//                .evolutionOutcome(outcome)
//                .evaluationOutcome(context.evaluationOutcome())
//                .plannerOutcome(context.plannerOutcome())
//                .evaluationFeedback(context.evaluationFeedback())
//                .build();
//    }
//
//    public static CycleContextData withEvaluationOutcome(CycleContextData context, EvaluationOutcome outcome) {
//        return CycleContextData.builder()
//                .cycleName(context.cycleName())
//                .outputPath(context.outputPath())
//                .previousOutput(context.previousOutput())
//                .projectSummary(context.projectSummary())
//                .diffReport(context.diffReport())
//                .currentGoalDescription(context.currentGoalDescription())
//                .currentSubGoalDescription(context.currentSubGoalDescription())
//                .currentGoalId(context.currentGoalId())
//                .currentSubGoalId(context.currentSubGoalId())
//                .remainingGoals(context.remainingGoals())
//                .completedGoals(context.completedGoals())
//                .rejectionFeedback(context.rejectionFeedback())
//                .recommendation(context.recommendation())
//                .evolutionOutcome(context.evolutionOutcome())
//                .evaluationOutcome(outcome)
//                .plannerOutcome(context.plannerOutcome())
//                .evaluationFeedback(context.evaluationFeedback())
//                .build();
//    }
//
//    public static CycleContextData withPlannerOutcome(CycleContextData context, PlannerOutcome outcome) {
//        return CycleContextData.builder()
//                .cycleName(context.cycleName())
//                .outputPath(context.outputPath())
//                .previousOutput(context.previousOutput())
//                .projectSummary(context.projectSummary())
//                .diffReport(context.diffReport())
//                .currentGoalDescription(context.currentGoalDescription())
//                .currentSubGoalDescription(context.currentSubGoalDescription())
//                .currentGoalId(context.currentGoalId())
//                .currentSubGoalId(context.currentSubGoalId())
//                .remainingGoals(context.remainingGoals())
//                .completedGoals(context.completedGoals())
//                .rejectionFeedback(context.rejectionFeedback())
//                .recommendation(context.recommendation())
//                .evolutionOutcome(context.evolutionOutcome())
//                .evaluationOutcome(context.evaluationOutcome())
//                .plannerOutcome(outcome)
//                .evaluationFeedback(context.evaluationFeedback())
//                .build();
//    }
//
//    public static CycleContextData withEvaluationFeedback(CycleContextData context, String evaluationFeedback) {
//        return CycleContextData.builder()
//                .cycleName(context.cycleName())
//                .outputPath(context.outputPath())
//                .previousOutput(context.previousOutput())
//                .projectSummary(context.projectSummary())
//                .diffReport(context.diffReport())
//                .currentGoalDescription(context.currentGoalDescription())
//                .currentSubGoalDescription(context.currentSubGoalDescription())
//                .currentGoalId(context.currentGoalId())
//                .currentSubGoalId(context.currentSubGoalId())
//                .remainingGoals(context.remainingGoals())
//                .completedGoals(context.completedGoals())
//                .rejectionFeedback(context.rejectionFeedback())
//                .recommendation(context.recommendation())
//                .evolutionOutcome(context.evolutionOutcome())
//                .evaluationOutcome(context.evaluationOutcome())
//                .plannerOutcome(context.plannerOutcome())
//                .evaluationFeedback(evaluationFeedback)
//                .build();
//    }

}