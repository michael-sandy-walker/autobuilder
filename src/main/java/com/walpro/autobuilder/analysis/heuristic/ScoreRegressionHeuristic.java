package com.walpro.autobuilder.analysis.heuristic;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

@Component
public class ScoreRegressionHeuristic implements EvaluationHeuristic {

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input.lastScore() == null) {
            return HeuristicOpinion.of(
                    "ScoreRegressionHeuristic",
                    0.0,
                    outcome.mutationState(),
                    outcome.goalProgress(),
                    "No previous score — cannot detect regression"
            );
        }

        double score = outcome.score();
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
                "ScoreRegressionHeuristic",
                confidence,
                override ? MutationAcceptanceState.REJECTED : outcome.mutationState(),
                override ? GoalProgressState.STAGNATING : outcome.goalProgress(),
                rationale,
                suggestedScore
        );
    }
}
