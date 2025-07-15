package com.walpro.autobuilder.agent.planner.model;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.model.CycleContextData;

import java.util.List;

public record PlannerInput(
        String currentGoal,
        String currentSubGoal,
        List<GoalInfo> remainingGoals,
        List<GoalInfo> completedGoals,
        String evaluationFeedback,
        String recommendation,
        InterpretationResult interpretationResult
) {

    public static PlannerInput from(CycleContextData context) {
        List<GoalInfo> remaining = context.remainingGoals().stream()
                .map(PlannerInput::toGoalInfo)
                .toList();

        List<GoalInfo> completed = context.completedGoals().stream()
                .map(PlannerInput::toGoalInfo)
                .toList();

        return new PlannerInput(
                context.currentGoalDescription(),
                context.currentSubGoalDescription(),
                remaining,
                completed,
                context.evaluationFeedback(),
                context.recommendation(),
                context.evaluationInterpretation()
        );
    }

    private static GoalInfo toGoalInfo(GoalEntity entity) {
        return new GoalInfo(entity.getId(), entity.getDescription());
    }
    
    public static PlannerInput fromSubgoalPlanning(String rootGoal, String subGoal, GoalPlannerService planner) {
        return new PlannerInput(
                rootGoal,
                subGoal,
                planner.getRemainingGoals().stream().map(g -> new GoalInfo(g.getId(), g.getDescription())).toList(),
                planner.getCompletedGoals().stream().map(g -> new GoalInfo(g.getId(), g.getDescription())).toList(),
                "", "", null
        );
    }
}
