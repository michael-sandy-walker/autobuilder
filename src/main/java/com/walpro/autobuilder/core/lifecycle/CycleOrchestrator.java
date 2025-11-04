package com.walpro.autobuilder.core.lifecycle;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.model.MutationAcceptanceState;
import com.walpro.autobuilder.model.GoalProgressState;

@Component
public class CycleOrchestrator {

    public CycleResultState determineNextState(CycleContextData context) {
        MutationAcceptanceState mutationState = context.mutationState();
        GoalProgressState progressState = context.goalProgress();

        if (mutationState == MutationAcceptanceState.ACCEPTED) {
            return (progressState == GoalProgressState.PROGRESSING)
                ? CycleResultState.SUCCESSFUL
                : CycleResultState.STAGNATED;
        }

        if (mutationState == MutationAcceptanceState.REJECTED) {
            return CycleResultState.REJECTED;
        }

        return CycleResultState.FALLBACK_PLANNING;
    }
}
