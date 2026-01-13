package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.agent.evaluation.history.ScoreHistoryService;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CycleScoreRecorder {

    private final ScoreHistoryService scoreHistoryService;

    public void recordGoalEventsIfPresent(CycleContextData ctx) {
        if (ctx == null) return;

        String cycleId = ctx.cycleName();
        if (cycleId == null || cycleId.isBlank()) return;

        String goalId = (ctx.currentSubGoalId() != null && !ctx.currentSubGoalId().isBlank())
                ? ctx.currentSubGoalId()
                : ctx.currentGoalId();

        if (goalId == null || goalId.isBlank()) return;

        Double score = resolveScoringScore(ctx);
        if (score == null) return;

        try {
            scoreHistoryService.recordGoalEvaluation(cycleId, goalId, score);
        } catch (Exception e) {
            log.warn("Failed to record goal evaluation (cycleId={}, goalId={})", cycleId, goalId, e);
        }

        if (ctx.mutationState() == MutationAcceptanceState.ACCEPTED) {
            try {
                scoreHistoryService.recordGoalAcceptance(cycleId, goalId, score);
            } catch (Exception e) {
                log.warn("Failed to record goal acceptance (cycleId={}, goalId={})", cycleId, goalId, e);
            }
        }
    }

    private Double resolveScoringScore(CycleContextData ctx) {
        if (ctx == null) return null;
        if (!ctx.scoringComputed()) return null;
        return ctx.score();
    }
}
