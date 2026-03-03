package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class ScoreRegressionHeuristic implements EvaluationHeuristic {

    private static final String NAME = "ScoreRegressionHeuristic";

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);

        if (input.lastScore() == null) {
            return HeuristicOpinion.of(
                    NAME,
                    0.0,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    "No previous score — cannot detect regression"
            );
        }

        Double scoreObj = outcome.score();
        if (scoreObj == null) {
            return HeuristicOpinion.of(
                    NAME,
                    0.0,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    "No current score — cannot detect regression"
            );
        }

        double score = scoreObj;
        double delta = score - input.lastScore();
        double confidence;
        String rationale;

        if (delta <= -0.15) {
            confidence = 0.95;
            rationale = "Severe score drop (Δ=%.2f) — likely regression".formatted(delta);
        } else if (delta <= -0.10) {
            confidence = 0.75;
            rationale = "Major score drop (Δ=%.2f)".formatted(delta);
        } else if (delta <= -0.05) {
            confidence = 0.5;
            rationale = "Moderate score drop (Δ=%.2f)".formatted(delta);
        } else if (delta < 0.0) {
            confidence = 0.25;
            rationale = "Minor regression (Δ=%.2f)".formatted(delta);
        } else {
            confidence = 0.0;
            rationale = "No regression detected (Δ=%.2f)".formatted(delta);
        }

        boolean override = confidence >= 0.75;
        double suggestedScore = override ? score + delta : score;

        return HeuristicOpinion.ofWithScore(
                NAME,
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                suggestedScore
        );
    }
}
