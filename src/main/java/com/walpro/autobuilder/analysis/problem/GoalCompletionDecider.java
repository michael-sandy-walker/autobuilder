package com.walpro.autobuilder.analysis.problem;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.springframework.stereotype.Component;

@Component
public class GoalCompletionDecider {

    public CompletionDecision decide(CycleContextData ctx, boolean completionSignal) {
        String goalId = ctx.currentGoalId();
        String subGoalId = ctx.currentSubGoalId();

        boolean blocked = ctx.problemContext() != null
                && ctx.problemContext().hasBlockingProblemsFor(goalId, subGoalId);

        if (!completionSignal) {
            return new CompletionDecision(false, "Completion signal not met.");
        }

        if (blocked) {
            return new CompletionDecision(false, "Blocking problems exist for current goal/subgoal.");
        }

        return new CompletionDecision(true, "Completion signal met and no blocking problems.");
    }

    public record CompletionDecision(boolean complete, String reason) {}
}
