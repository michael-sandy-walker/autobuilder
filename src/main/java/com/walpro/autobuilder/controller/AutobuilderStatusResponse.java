package com.walpro.autobuilder.controller;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.Getter;
import lombok.Setter;

/**
 * Lightweight view of the current / last Autobuilder state for REST clients.
 */
@Setter
@Getter
public class AutobuilderStatusResponse {

    private String cycleName;
    private Double score;

    private String currentGoalId;
    private String currentGoalDescription;

    private String currentSubGoalId;
    private String currentSubGoalDescription;

    private String goalProgress;   // GoalProgressState.name()
    private String mutationState;  // MutationAcceptanceState.name()

    public static AutobuilderStatusResponse from(CycleContextData ctx) {
        AutobuilderStatusResponse dto = new AutobuilderStatusResponse();
        dto.setCycleName(ctx.cycleName());
        dto.setScore(ctx.score());

        dto.setCurrentGoalId(ctx.currentGoalId());
        dto.setCurrentGoalDescription(ctx.currentGoalDescription());
        dto.setCurrentSubGoalId(ctx.currentSubGoalId());
        dto.setCurrentSubGoalDescription(ctx.currentSubGoalDescription());

        GoalProgressState gp = ctx.goalProgress();
        dto.setGoalProgress(gp != null ? gp.name() : null);

        MutationAcceptanceState ms = ctx.mutationState();
        dto.setMutationState(ms != null ? ms.name() : null);

        return dto;
    }

    public static AutobuilderStatusResponse empty() {
        AutobuilderStatusResponse dto = new AutobuilderStatusResponse();
        dto.setCycleName(null);
        dto.setScore(null);
        dto.setCurrentGoalId(null);
        dto.setCurrentGoalDescription(null);
        dto.setCurrentSubGoalId(null);
        dto.setCurrentSubGoalDescription(null);
        dto.setGoalProgress(null);
        dto.setMutationState(null);
        return dto;
    }

}
