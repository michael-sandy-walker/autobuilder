package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.agent.evaluation.score.ScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CycleScoreRecorder {

    private final ScoringService scoringService;

    public void recordGoalScoreIfPresent(CycleContextData ctx) {
        if (ctx == null) return;

        var outcome = ctx.evaluationOutcome();
        var interpretation = ctx.evaluationInterpretation();
        if (outcome == null || interpretation == null) return;

        String goalId = ctx.currentSubGoalId() != null ? ctx.currentSubGoalId() : ctx.currentGoalId();
        if (goalId == null) return;

        ScoreHistoryService history = scoringService.getScoreHistoryService();
        history.recordGoalEvaluation(goalId, outcome.score());
        if (interpretation.success()) {
            history.recordGoalAcceptance(goalId, outcome.score());
        }
    }
}
