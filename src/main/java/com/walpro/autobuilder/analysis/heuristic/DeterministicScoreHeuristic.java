package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evaluation.score.ScoringResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.springframework.stereotype.Component;

@Component
public class DeterministicScoreHeuristic implements EvaluationHeuristic {

    private static final String NAME = "DeterministicScoreHeuristic";

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null) return HeuristicOpinion.none(NAME);

        ScoringResult scoring = input.scoringResult();
        if (scoring == null || scoring.globalScore() == null) return HeuristicOpinion.none(NAME);

        Double s = scoring.globalScore();

        // Conservative: deterministic score is "solid evidence" but not absolute truth
        double confidence = 0.55;

        String rationale = "Deterministic score from ScoringService.globalScore=%.3f".formatted(s);

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                original.mutationState(),
                original.goalProgress(),
                rationale,
                s
        );
    }
}